package com.browserlite.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import org.junit.Test;

public class HtmlRewriterTest {
    private static final String P = "<script src=\"x.js\"></script>";

    /** Delivers the input in chunks of at most {@code max} bytes. */
    static final class Trickle extends InputStream {
        final byte[] data; int pos; final Random rnd; final int max;
        Trickle(byte[] d, int max, long seed) { data = d; this.max = max; rnd = new Random(seed); }
        @Override public int read() { return pos < data.length ? data[pos++] & 0xff : -1; }
        @Override public int read(byte[] b, int off, int len) {
            if (pos >= data.length) return -1;
            int n = Math.min(Math.min(len, 1 + rnd.nextInt(max)), data.length - pos);
            System.arraycopy(data, pos, b, off, n); pos += n; return n;
        }
    }

    static boolean deferLazy;

    static String run(String html, int maxChunk, long seed) throws IOException {
        HtmlRewriter.Options o = new HtmlRewriter.Options();
        o.headPayload = P;
        o.deferLazy = deferLazy;
        o.registry = new CssCompat.VarRegistry();
        InputStream in = new HtmlRewriter(new Trickle(html.getBytes("UTF-8"), maxChunk, seed), o);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[7];
        int n;
        while ((n = in.read(buf, 0, buf.length)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    static void check(String expected, String html) throws IOException {
        assertEquals(expected, run(html, 100000, 1));
        for (int seed = 0; seed < 40; seed++) {
            assertEquals("chunk seed " + seed, expected, run(html, 1 + seed % 9, seed));
        }
    }

    @Test public void injectsAfterHead() throws IOException {
        check("<!DOCTYPE html><html><head>" + P + "<title>t</title></head><body>x</body></html>",
                "<!DOCTYPE html><html><head><title>t</title></head><body>x</body></html>");
    }

    @Test public void injectsBeforeFirstElementWithoutHead() throws IOException {
        check("<!doctype html><!-- c --><html>" + P + "<meta charset=utf-8><p>hi",
                "<!doctype html><!-- c --><html><meta charset=utf-8><p>hi");
    }

    @Test public void injectsAtEndForTextOnly() throws IOException {
        check("hello" + P, "hello");
    }

    @Test public void headerIsNotHead() throws IOException {
        check("<html>" + P + "<header>h</header>", "<html><header>h</header>");
    }

    @Test public void stripsIntegrityAndCrossorigin() throws IOException {
        check("<head>" + P + "<script src=a.js data-bl-integrity=\"sha384-x\" data-bl-crossorigin></script>",
                "<head><script src=a.js integrity=\"sha384-x\" crossorigin></script>");
    }

    @Test public void neutralisesCspMeta() throws IOException {
        check("<head>" + P + "<meta data-bl-http-equiv=\"Content-Security-Policy\" content=\"script-src 'self'\">",
                "<head><meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'self'\">");
    }

    @Test public void disablesPrerender() throws IOException {
        check("<head>" + P + "<link data-bl-rel=\"prerender\" href=\"/next\"><link rel=\"dns-prefetch\" href=\"//x\">",
                "<head><link rel=\"prerender\" href=\"/next\"><link rel=\"dns-prefetch\" href=\"//x\">");
    }

    @Test public void mediaAutoplay() throws IOException {
        check("<head>" + P + "<video src=v.mp4 data-bl-autoplay muted preload=\"none\"></video>",
                "<head><video src=v.mp4 autoplay muted preload=auto></video>");
        check("<head>" + P + "<video src=v.mp4 preload=\"none\"></video>", "<head><video src=v.mp4></video>");
    }

    @Test public void unlocksZoom() throws IOException {
        check("<head>" + P + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
                "<head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">");
    }

    @Test public void scriptContentUntouched() throws IOException {
        String js = "<head><script>var a = '<head><style>x{}</style>' + \"</scr\" + \"ipt>\"; if (a<b) {}</script><p>";
        check("<head>" + P + "<script>var a = '<head><style>x{}</style>' + \"</scr\" + \"ipt>\"; if (a<b) {}</script><p>", js);
        check("<head>" + P + "<script>x</SCRIPT ><p>ok", "<head><script>x</SCRIPT ><p>ok");
        check("<head>" + P + "<script>a</scriptx>b</script>c", "<head><script>a</scriptx>b</script>c");
    }

    @Test public void transformsInlineStyle() throws IOException {
        check("<head>" + P + "<style>a{color:red;}</style><style media=print>b{-webkit-transform:none;transform:none;}</style>",
                "<head><style>:root{--c:red}a{color:var(--c)}</style><style media=print>b{transform:none}</style>");
    }

    @Test public void commentsPassThrough() throws IOException {
        check("<head>" + P + "<!-- <style>a{b:var(--x)}</style> --><p>", "<head><!-- <style>a{b:var(--x)}</style> --><p>");
    }

    @Test public void lessThanInText() throws IOException {
        check("<head>" + P + "<p>1 < 2 and 3<4</p>", "<head><p>1 < 2 and 3<4</p>");
    }

    @Test public void quotedGreaterThanInAttribute() throws IOException {
        check("<head>" + P + "<a title=\"a>b\" href=x>y</a>", "<head><a title=\"a>b\" href=x>y</a>");
    }

    @Test public void utf8Preserved() throws IOException {
        check("<head>" + P + "<p>Tiếng Việt có dấu ✓</p><style>a:before{content:\"→\";}</style>",
                "<head><p>Tiếng Việt có dấu ✓</p><style>a:before{content:\"→\"}</style>");
    }

    @Test public void hugeAttributeStreams() throws IOException {
        StringBuilder sb = new StringBuilder("<head><img src=\"data:image/png;base64,");
        for (int i = 0; i < 300000; i++) sb.append('A');
        sb.append("\">x");
        String out = run(sb.toString(), 5000, 3);
        assertTrue(out.startsWith("<head>" + P + "<img src=\"data:"));
        assertTrue(out.endsWith("\">x"));
        assertEquals(sb.length() + P.length(), out.length());
    }

    @Test public void utf16PassThrough() throws IOException {
        byte[] b = {(byte) 0xFF, (byte) 0xFE, '<', 0, 'p', 0};
        HtmlRewriter.Options o = new HtmlRewriter.Options();
        o.headPayload = P;
        InputStream in = new HtmlRewriter(new ByteArrayInputStream(b), o);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) out.write(c);
        byte[] r = out.toByteArray();
        assertEquals((byte) 0xFF, r[0]);
        assertEquals(6, r.length);
    }

    @Test public void defersLazyImages() throws IOException {
        deferLazy = true;
        try {
            check("<head>" + P + "<img data-bl-src=\"a.jpg\" data-bl-srcset=\"a.jpg 1x\" loading=\"lazy\" alt=x><img src=b.jpg>"
                    + "<iframe loading=lazy data-bl-src=\"https://e.com/\"></iframe>",
                    "<head><img src=\"a.jpg\" srcset=\"a.jpg 1x\" loading=\"lazy\" alt=x><img src=b.jpg>"
                    + "<iframe loading=lazy src=\"https://e.com/\"></iframe>");
        } finally {
            deferLazy = false;
        }
        check("<head>" + P + "<img src=a.jpg loading=lazy>", "<head><img src=a.jpg loading=lazy>");
    }
}
