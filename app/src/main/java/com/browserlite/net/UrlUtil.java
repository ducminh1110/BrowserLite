package com.browserlite.net;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/** Small URL helpers. Pure Java so they can be unit tested on the build machine. */
public final class UrlUtil {
    private UrlUtil() {}

    /** Turns what the user typed into a URL, or a search URL when it doesn't look like one. */
    public static String fromInput(String input, String searchTemplate) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file://")
                || lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("javascript:")) {
            return s;
        }
        if (s.indexOf(' ') < 0 && looksLikeHost(s)) {
            return "https://" + s;
        }
        return searchUrl(searchTemplate, s);
    }

    public static String searchUrl(String template, String query) {
        String q;
        try {
            q = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            q = query;
        }
        if (template == null || template.indexOf("%s") < 0) template = "https://html.duckduckgo.com/html/?q=%s";
        return template.replace("%s", q);
    }

    static boolean looksLikeHost(String s) {
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String host = s.substring(0, end);
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(']') < 0) {
            String port = host.substring(colon + 1);
            if (port.isEmpty() || !isDigits(port)) return false;
            host = host.substring(0, colon);
        }
        if (host.isEmpty()) return false;
        if (host.equalsIgnoreCase("localhost")) return true;
        if (host.startsWith("[") && host.endsWith("]")) return true;
        int dot = host.lastIndexOf('.');
        if (dot <= 0 || dot == host.length() - 1) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = c == '.' || c == '-' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c > 127;
            if (!ok) return false;
        }
        String tld = host.substring(dot + 1);
        if (isDigits(tld)) return host.split("\\.").length == 4; // IPv4
        for (int i = 0; i < tld.length(); i++) {
            char c = tld.charAt(i);
            if (c >= '0' && c <= '9') return false;
        }
        return tld.length() >= 2;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static boolean isHttp(String url) {
        if (url == null) return false;
        return url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8);
    }

    public static String stripFragment(String url) {
        if (url == null) return null;
        int i = url.indexOf('#');
        return i < 0 ? url : url.substring(0, i);
    }

    /** Lower-case host of an http(s) URL, or "" when there is none. */
    public static String host(String url) {
        if (url == null) return "";
        int start = url.indexOf("://");
        if (start < 0) return "";
        start += 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close > 0 ? authority.substring(0, close + 1).toLowerCase(Locale.US) : authority;
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);
        return authority.toLowerCase(Locale.US);
    }

    /** scheme://host[:port] */
    public static String origin(String url) {
        if (url == null) return "";
        int start = url.indexOf("://");
        if (start < 0) return "";
        int end = url.length();
        for (int i = start + 3; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        return url.substring(0, end).toLowerCase(Locale.US);
    }

    /** Registrable-ish domain: last two labels, or three for common second-level suffixes. */
    public static String siteOf(String host) {
        if (host == null) return "";
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        String last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
        String sld = parts[parts.length - 2];
        boolean shortSld = sld.equals("co") || sld.equals("com") || sld.equals("net") || sld.equals("org")
                || sld.equals("gov") || sld.equals("edu") || sld.equals("ac") || sld.equals("or")
                || sld.equals("ne") || sld.equals("go");
        if (shortSld && parts[parts.length - 1].length() == 2) {
            return parts[parts.length - 3] + "." + last2;
        }
        return last2;
    }

    public static boolean sameSite(String hostA, String hostB) {
        return siteOf(hostA).equals(siteOf(hostB));
    }

    /** Referer value following strict-origin-when-cross-origin. */
    public static String referrer(String from, String to) {
        if (!isHttp(from) || !isHttp(to)) return null;
        boolean fromHttps = from.regionMatches(true, 0, "https:", 0, 6);
        boolean toHttps = to.regionMatches(true, 0, "https:", 0, 6);
        if (fromHttps && !toHttps) return null;
        String fromOrigin = origin(from);
        if (fromOrigin.equals(origin(to))) return stripFragment(from);
        return fromOrigin + "/";
    }

    /** Lower-case extension of the URL path (without query), or "". */
    public static String extension(String url) {
        if (url == null) return "";
        int end = url.length();
        int q = url.indexOf('?');
        if (q >= 0) end = q;
        int h = url.indexOf('#');
        if (h >= 0 && h < end) end = h;
        int slash = url.lastIndexOf('/', end - 1);
        int dot = url.lastIndexOf('.', end - 1);
        if (dot < 0 || dot < slash || end - dot > 6) return "";
        return url.substring(dot + 1, end).toLowerCase(Locale.US);
    }

    public static final int KIND_OTHER = 0;
    public static final int KIND_IMAGE = 1;
    public static final int KIND_CSS = 2;
    public static final int KIND_SCRIPT = 3;
    public static final int KIND_FONT = 4;
    public static final int KIND_MEDIA = 5;
    public static final int KIND_DOCUMENT = 6;

    /** Best guess of what a subresource is from its URL alone (API 19 gives us nothing else). */
    public static int kindOf(String url) {
        String ext = extension(url);
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "jpe": case "jfif":
                return KIND_IMAGE;
            case "css":
                return KIND_CSS;
            case "js": case "mjs":
                return KIND_SCRIPT;
            case "woff": case "woff2": case "ttf": case "otf": case "eot":
                return KIND_FONT;
            case "mp4": case "webm": case "m4v": case "mp3": case "m4a": case "ogg": case "oga": case "ogv":
            case "wav": case "m3u8": case "mpd": case "ts": case "aac": case "opus": case "flac":
                return KIND_MEDIA;
            case "html": case "htm": case "php": case "asp": case "aspx": case "jsp":
                return KIND_DOCUMENT;
            default:
                break;
        }
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("https://fonts.googleapis.com/css") || lower.startsWith("http://fonts.googleapis.com/css")) {
            return KIND_CSS;
        }
        if (lower.contains("format=jpg") || lower.contains("format=jpeg") || lower.contains("fm=jpg")
                || lower.contains("fm=webp") || lower.contains("format=webp") || lower.contains("format=png")) {
            return KIND_IMAGE;
        }
        return KIND_OTHER;
    }

    public static String mimeForKind(int kind, String url) {
        String ext = extension(url);
        switch (kind) {
            case KIND_IMAGE:
                if (ext.equals("png")) return "image/png";
                if (ext.equals("gif")) return "image/gif";
                if (ext.equals("webp")) return "image/webp";
                if (ext.equals("bmp")) return "image/bmp";
                return "image/jpeg";
            case KIND_CSS:
                return "text/css";
            case KIND_SCRIPT:
                return "application/javascript";
            case KIND_FONT:
                if (ext.equals("woff2")) return "font/woff2";
                if (ext.equals("woff")) return "application/font-woff";
                if (ext.equals("otf")) return "font/opentype";
                if (ext.equals("eot")) return "application/vnd.ms-fontobject";
                return "font/ttf";
            default:
                return "text/html";
        }
    }

    /** Charset parameter of a Content-Type header, or null. */
    public static String charsetOf(String contentType) {
        if (contentType == null) return null;
        String lower = contentType.toLowerCase(Locale.US);
        int i = lower.indexOf("charset=");
        if (i < 0) return null;
        String cs = contentType.substring(i + 8).trim();
        int semi = cs.indexOf(';');
        if (semi >= 0) cs = cs.substring(0, semi);
        cs = cs.replace("\"", "").replace("'", "").trim();
        return cs.isEmpty() ? null : cs;
    }

    /** Mime type (without parameters) of a Content-Type header, lower-cased; "" when absent. */
    public static String mimeOf(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        String m = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return m.trim().toLowerCase(Locale.US);
    }

    public static String htmlEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Escapes a string for use inside a JS double-quoted string literal (also safe inside HTML script). */
    public static String jsString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                case ' ': sb.append("\\u2028"); break;
                case ' ': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
