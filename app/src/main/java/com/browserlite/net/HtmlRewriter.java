package com.browserlite.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Streaming HTML filter working directly on bytes.
 *
 * <p>Everything it touches (tag names, attribute names, ASCII payload) is ASCII, so it is safe for every
 * ASCII-compatible charset (UTF-8, windows-125x, GBK, Shift_JIS, ...) without knowing which one is used.
 * Text flows through as soon as it arrives, so the page still renders progressively on slow links.
 *
 * <p>What it does:
 * <ul>
 *   <li>injects {@link Options#headPayload} right after {@code <head>} (or before the first element);</li>
 *   <li>renames {@code integrity}/{@code crossorigin} (responses we serve have no CORS headers) and
 *       neutralises {@code <meta http-equiv=Content-Security-Policy>} so the injected script can run;</li>
 *   <li>disables {@code <link rel=prerender|prefetch>} (a hidden prerendered page can cost 30+ MB) and
 *       {@code autoplay}/{@code preload} on media;</li>
 *   <li>removes {@code user-scalable=no}/{@code maximum-scale} so pages can always be zoomed;</li>
 *   <li>runs {@link CssCompat} over inline {@code <style>} blocks.</li>
 * </ul>
 */
public final class HtmlRewriter extends InputStream {

    public static final class Options {
        public String headPayload;
        public boolean transformCss = true;
        public CssCompat.VarRegistry registry;
        public CssCompat.Options cssOptions;
        public boolean unlockZoom = true;
        /**
         * Rename src/srcset of {@code loading=lazy} images and iframes to data-bl-*, restored near the viewport by
         * page.js. Chromium 30 ignores the attribute and would load every one at once. Needs JavaScript.
         */
        public boolean deferLazy;
        public int maxStyleBuffer = 1 << 20;
    }

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final int MAX_TAG = 256 * 1024;

    private static final int TEXT = 0, TAG = 1, COMMENT = 2, RAW = 3, STYLE = 4, PASS = 5, PASS_TAG = 6;

    private final InputStream in;
    private final Options opt;
    private final byte[] chunk = new byte[8192];

    private byte[] out = new byte[16384];
    private int outPos, outLen;

    private int state = TEXT;
    private boolean eof;
    private boolean first = true;
    private boolean injected;

    // TAG state
    private byte[] tag = new byte[512];
    private int tagLen;
    private byte quote;
    private byte lastNonSpace;
    // PASS_TAG state
    private byte passQuote;
    private byte passLast;
    // COMMENT state: count of trailing '-'
    private int dashes;
    // RAW / STYLE state
    private byte[] endMarker; // lower-case "</script"
    private int matchLen;
    private byte[] style;
    private int styleLen;

    public HtmlRewriter(InputStream in, Options opt) {
        this.in = in;
        this.opt = opt;
    }

    // ------------------------------------------------------------------ InputStream

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (outPos >= outLen) {
            if (eof) return -1;
            outPos = 0;
            outLen = 0;
            int n = in.read(chunk, 0, chunk.length);
            if (n < 0) {
                eof = true;
                finish();
            } else if (n > 0) {
                process(chunk, 0, n);
            }
        }
        int n = Math.min(len, outLen - outPos);
        System.arraycopy(out, outPos, b, off, n);
        outPos += n;
        return n;
    }

    @Override
    public int available() {
        // Never report a size: the WebView would treat it as the content length.
        return 0;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ------------------------------------------------------------------ output helpers

    private void emit(byte[] b, int off, int len) {
        if (len <= 0) return;
        ensure(len);
        System.arraycopy(b, off, out, outLen, len);
        outLen += len;
    }

    private void emit(byte b) {
        ensure(1);
        out[outLen++] = b;
    }

    private void emit(String s) {
        byte[] b = s.getBytes(LATIN1);
        emit(b, 0, b.length);
    }

    private void ensure(int extra) {
        if (outLen + extra > out.length) {
            byte[] n = new byte[Math.max(out.length * 2, outLen + extra)];
            System.arraycopy(out, 0, n, 0, outLen);
            out = n;
        }
    }

    private void tagAppend(byte c) {
        if (tagLen == tag.length) {
            byte[] n = new byte[tag.length * 2];
            System.arraycopy(tag, 0, n, 0, tagLen);
            tag = n;
        }
        tag[tagLen++] = c;
    }

    private void injectPayload() {
        if (injected) return;
        injected = true;
        if (opt.headPayload != null) emit(opt.headPayload);
    }

    // ------------------------------------------------------------------ state machine

    private void process(byte[] b, int off, int len) {
        int i = off;
        int end = off + len;
        if (first) {
            first = false;
            if (len >= 2 && ((b[off] == (byte) 0xFE && b[off + 1] == (byte) 0xFF)
                    || (b[off] == (byte) 0xFF && b[off + 1] == (byte) 0xFE))) {
                state = PASS; // UTF-16: not ASCII compatible, leave it alone
                injected = true;
            }
        }
        while (i < end) {
            switch (state) {
                case PASS:
                    emit(b, i, end - i);
                    return;
                case TEXT: {
                    int start = i;
                    while (i < end && b[i] != '<') i++;
                    emit(b, start, i - start);
                    if (i < end) {
                        tagLen = 0;
                        tagAppend(b[i]);
                        quote = 0;
                        lastNonSpace = '<';
                        state = TAG;
                        i++;
                    }
                    break;
                }
                case TAG:
                    i = processTagBytes(b, i, end);
                    break;
                case PASS_TAG:
                    while (i < end) {
                        byte c = b[i++];
                        emit(c);
                        if (passQuote != 0) {
                            if (c == passQuote) passQuote = 0;
                        } else if ((c == '"' || c == '\'') && passLast == '=') {
                            passQuote = c;
                        } else if (c == '>') {
                            state = TEXT;
                            break;
                        }
                        if (c != ' ' && c != '\n' && c != '\t' && c != '\r') passLast = c;
                    }
                    break;
                case COMMENT:
                    while (i < end) {
                        byte c = b[i++];
                        emit(c);
                        if (c == '>' && dashes >= 2) {
                            state = TEXT;
                            break;
                        }
                        dashes = c == '-' ? dashes + 1 : 0;
                    }
                    break;
                case RAW:
                    i = processRaw(b, i, end, false);
                    break;
                case STYLE:
                    i = processRaw(b, i, end, true);
                    break;
                default:
                    emit(b, i, end - i);
                    return;
            }
        }
    }

    /** Collects a tag; returns the new index. */
    private int processTagBytes(byte[] b, int i, int end) {
        while (i < end) {
            byte c = b[i];
            if (tagLen == 1) {
                // Decide what '<' starts.
                boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
                if (!letter && c != '/' && c != '!' && c != '?') {
                    emit((byte) '<');
                    state = TEXT;
                    return i;
                }
            }
            if (tagLen == 3 && tag[1] == '!' && tag[2] == '-' && c == '-') {
                // "<!--": pass the comment straight through.
                emit(tag, 0, tagLen);
                emit(c);
                dashes = 0;
                state = COMMENT;
                return i + 1;
            }
            tagAppend(c);
            i++;
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if ((c == '"' || c == '\'') && lastNonSpace == '=') {
                quote = c;
                continue;
            }
            if (c == '>') {
                completeTag();
                return i;
            }
            if (c != ' ' && c != '\n' && c != '\t' && c != '\r' && c != '\f') lastNonSpace = c;
            if (tagLen > MAX_TAG) {
                // Enormous inline attribute (data: URI). Stop buffering and stream the rest of the tag.
                if (!injected && isElementStart()) injectPayload();
                emit(tag, 0, tagLen);
                passQuote = quote;
                passLast = lastNonSpace;
                state = PASS_TAG;
                return i;
            }
        }
        return i;
    }

    private boolean isElementStart() {
        if (tagLen < 2) return false;
        byte c = tag[1];
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private String tagName() {
        int start = tag[1] == '/' ? 2 : 1;
        int j = start;
        while (j < tagLen) {
            byte c = tag[j];
            if (c == ' ' || c == '>' || c == '/' || c == '\n' || c == '\t' || c == '\r' || c == '\f') break;
            j++;
        }
        return new String(tag, start, j - start, LATIN1).toLowerCase(Locale.US);
    }

    private void completeTag() {
        state = TEXT;
        byte second = tag[1];
        if (second == '!' || second == '?') {
            emit(tag, 0, tagLen); // doctype, CDATA, processing instruction
            return;
        }
        boolean endTag = second == '/';
        String name = tagName();
        if (endTag) {
            if (!injected && !name.equals("html")) injectPayload();
            emit(tag, 0, tagLen);
            return;
        }
        if (name.equals("head")) {
            emit(tag, 0, tagLen);
            injectPayload();
        } else {
            if (!injected && !name.equals("html")) injectPayload();
            String raw = new String(tag, 0, tagLen, LATIN1);
            String rewritten = rewriteStartTag(name, raw, opt.unlockZoom, opt.deferLazy);
            if (rewritten == raw) emit(tag, 0, tagLen);
            else emit(rewritten);
        }
        boolean selfClosing = tagLen >= 2 && tag[tagLen - 2] == '/';
        switch (name) {
            case "script":
            case "textarea":
            case "title":
            case "xmp":
            case "iframe":
            case "noembed":
            case "noframes":
            case "noscript":
                if (!selfClosing || name.equals("script") || name.equals("iframe")) enterRaw(name, false);
                break;
            case "style":
                if (!selfClosing) enterRaw(name, opt.transformCss);
                break;
            case "plaintext":
                state = PASS;
                injected = true;
                break;
            default:
                break;
        }
    }

    private void enterRaw(String name, boolean bufferStyle) {
        endMarker = ("</" + name).getBytes(LATIN1);
        matchLen = 0;
        if (bufferStyle) {
            if (style == null) style = new byte[4096];
            styleLen = 0;
            state = STYLE;
        } else {
            state = RAW;
        }
    }

    private void styleAppend(byte[] b, int off, int len) {
        if (styleLen + len > style.length) {
            byte[] n = new byte[Math.max(style.length * 2, styleLen + len)];
            System.arraycopy(style, 0, n, 0, styleLen);
            style = n;
        }
        System.arraycopy(b, off, style, styleLen, len);
        styleLen += len;
    }

    private final byte[] held = new byte[16];

    private static boolean isTerminator(byte c) {
        return c == '>' || c == ' ' || c == '/' || c == '\n' || c == '\t' || c == '\r' || c == '\f';
    }

    /** Raw text content: buffered while transforming a style block, emitted otherwise. */
    private void content(byte[] b, int off, int len) {
        if (len <= 0) return;
        if (state == STYLE) styleAppendSafe(b, off, len);
        else emit(b, off, len);
    }

    /**
     * Passes raw text through (or buffers it for STYLE) until the end marker such as {@code </script}.
     * Bytes that might start the marker are held in {@link #held} so a marker split across chunks is found.
     */
    private int processRaw(byte[] b, int i, int end, boolean buffer) {
        int run = i;
        while (i < end) {
            byte c = b[i];
            if (matchLen == endMarker.length) {
                if (isTerminator(c)) {
                    content(b, run, i - run);
                    finishRaw(buffer);
                    tagLen = 0;
                    for (int k = 0; k < matchLen; k++) tagAppend(held[k]);
                    matchLen = 0;
                    quote = 0;
                    lastNonSpace = 'x';
                    state = TAG;
                    return i;
                }
                content(held, 0, matchLen);
                matchLen = 0;
                run = i;
            }
            byte lc = (c >= 'A' && c <= 'Z') ? (byte) (c + 32) : c;
            if (lc == endMarker[matchLen]) {
                if (matchLen == 0) content(b, run, i - run);
                held[matchLen++] = c;
                i++;
                run = i;
                continue;
            }
            if (matchLen > 0) {
                content(held, 0, matchLen);
                matchLen = 0;
                if (lc == endMarker[0]) {
                    held[0] = c;
                    matchLen = 1;
                    i++;
                    run = i;
                    continue;
                }
                run = i;
            }
            i++;
        }
        content(b, run, end - run);
        return end;
    }

    private void styleAppendSafe(byte[] b, int off, int len) {
        if (styleLen + len > opt.maxStyleBuffer) {
            // Too large to transform: give up on this block and stream it raw.
            emit(style, 0, styleLen);
            styleLen = 0;
            emit(b, off, len);
            state = RAW;
            return;
        }
        styleAppend(b, off, len);
    }

    private void finishRaw(boolean buffer) {
        if (state == STYLE && styleLen > 0) {
            String css = new String(style, 0, styleLen, LATIN1);
            String fixed = CssCompat.transform(css, opt.registry, opt.cssOptions);
            emit(fixed);
            styleLen = 0;
        }
        if (style != null && style.length > 64 * 1024) style = null; // don't pin big buffers
    }

    private void finish() {
        switch (state) {
            case TAG:
                emit(tag, 0, tagLen);
                break;
            case STYLE:
            case RAW:
                content(held, 0, matchLen);
                matchLen = 0;
                if (state == STYLE) emit(style, 0, styleLen);
                break;
            default:
                break;
        }
        if (!injected) injectPayload();
    }

    // ------------------------------------------------------------------ attribute rewriting

    /** Returns {@code raw} itself when nothing needs to change. */
    static String rewriteStartTag(String name, String raw, boolean unlockZoom, boolean deferLazy) {
        String lower = raw.toLowerCase(Locale.US);
        boolean lazy = deferLazy && (name.equals("img") || name.equals("iframe"))
                && (lower.indexOf("loading=\"lazy\"") >= 0 || lower.indexOf("loading='lazy'") >= 0
                        || lower.indexOf("loading=lazy") >= 0);
        boolean interesting = lazy || lower.indexOf("integrity") >= 0 || lower.indexOf("crossorigin") >= 0
                || (name.equals("meta") && (lower.indexOf("http-equiv") >= 0 || lower.indexOf("viewport") >= 0))
                || (name.equals("link") && (lower.indexOf("prerender") >= 0 || lower.indexOf("prefetch") >= 0))
                || ((name.equals("video") || name.equals("audio")) && (lower.indexOf("autoplay") >= 0
                        || lower.indexOf("preload") >= 0 || name.equals("video")));
        if (!interesting) return raw;

        StringBuilder sb = new StringBuilder(raw.length() + 32);
        int n = raw.length();
        int i = 1 + name.length();
        sb.append(raw, 0, i);
        boolean isCsp = false;
        boolean hasPreload = false;
        boolean viewport = name.equals("meta") && lower.indexOf("viewport") >= 0 && lower.indexOf("name") >= 0;
        if (name.equals("meta")) {
            isCsp = lower.indexOf("content-security-policy") >= 0;
        }
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '>' || (c == '/' && i + 1 < n && raw.charAt(i + 1) == '>')) break;
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\f' || c == '/') {
                sb.append(c);
                i++;
                continue;
            }
            int nameStart = i;
            while (i < n) {
                char d = raw.charAt(i);
                if (d == '=' || d == '>' || d == ' ' || d == '\n' || d == '\t' || d == '\r' || d == '\f' || d == '/') break;
                i++;
            }
            String attr = raw.substring(nameStart, i).toLowerCase(Locale.US);
            int j = i;
            while (j < n && (raw.charAt(j) == ' ' || raw.charAt(j) == '\n' || raw.charAt(j) == '\t' || raw.charAt(j) == '\r')) j++;
            String valuePart = "";
            String value = null;
            if (j < n && raw.charAt(j) == '=') {
                int k = j + 1;
                while (k < n && (raw.charAt(k) == ' ' || raw.charAt(k) == '\n' || raw.charAt(k) == '\t' || raw.charAt(k) == '\r')) k++;
                int vStart = k;
                if (k < n && (raw.charAt(k) == '"' || raw.charAt(k) == '\'')) {
                    char q = raw.charAt(k);
                    int close = raw.indexOf(q, k + 1);
                    if (close < 0) close = n - 1;
                    value = raw.substring(k + 1, close);
                    k = close + 1;
                } else {
                    while (k < n && raw.charAt(k) != ' ' && raw.charAt(k) != '>' && raw.charAt(k) != '\n'
                            && raw.charAt(k) != '\t' && raw.charAt(k) != '\r') {
                        k++;
                    }
                    value = raw.substring(vStart, k);
                }
                valuePart = raw.substring(i, k);
                i = k;
            }
            String attrName = raw.substring(nameStart, nameStart + attr.length());
            switch (attr) {
                case "integrity":
                case "crossorigin":
                    sb.append("data-bl-").append(attr).append(valuePart);
                    continue;
                case "http-equiv":
                    if (isCsp) {
                        sb.append("data-bl-http-equiv").append(valuePart);
                        continue;
                    }
                    break;
                case "rel":
                    if (name.equals("link") && value != null) {
                        String v = value.toLowerCase(Locale.US);
                        if (v.indexOf("prerender") >= 0 || v.indexOf("prefetch") >= 0 && v.indexOf("dns-prefetch") < 0) {
                            sb.append("data-bl-rel").append(valuePart);
                            continue;
                        }
                    }
                    break;
                case "src":
                case "srcset":
                    if (lazy) {
                        sb.append("data-bl-").append(attr).append(valuePart);
                        continue;
                    }
                    break;
                case "autoplay":
                    if (name.equals("video") || name.equals("audio")) {
                        sb.append("data-bl-autoplay").append(valuePart);
                        continue;
                    }
                    break;
                case "preload":
                    if (name.equals("video") || name.equals("audio")) {
                        hasPreload = true;
                        sb.append("preload=\"none\"");
                        continue;
                    }
                    break;
                case "content":
                    if (viewport && unlockZoom && value != null) {
                        sb.append(attrName).append("=\"").append(unlockViewport(value)).append('"');
                        continue;
                    }
                    break;
                default:
                    break;
            }
            sb.append(attrName).append(valuePart);
        }
        if ((name.equals("video") || name.equals("audio")) && !hasPreload) sb.append(" preload=\"none\"");
        sb.append(raw, i, n);
        return sb.toString();
    }

    static String unlockViewport(String content) {
        StringBuilder sb = new StringBuilder();
        for (String part : content.split("[,;]")) {
            String p = part.trim();
            String lp = p.toLowerCase(Locale.US);
            if (lp.startsWith("maximum-scale") || lp.startsWith("user-scalable")) continue;
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.replace("\"", "&quot;"));
        }
        return sb.toString();
    }
}
