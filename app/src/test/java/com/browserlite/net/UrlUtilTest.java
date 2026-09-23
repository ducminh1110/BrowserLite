package com.browserlite.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlUtilTest {
    private static final String T = "https://s.example/?q=%s";

    @Test public void inputToUrl() {
        assertEquals("https://vnexpress.net", UrlUtil.fromInput("vnexpress.net", T));
        assertEquals("https://example.com/a?b=1", UrlUtil.fromInput("  example.com/a?b=1 ", T));
        assertEquals("http://x.org", UrlUtil.fromInput("http://x.org", T));
        assertEquals("https://localhost:8080/x", UrlUtil.fromInput("localhost:8080/x", T));
        assertEquals("https://192.168.1.1", UrlUtil.fromInput("192.168.1.1", T));
        assertEquals("https://s.example/?q=th%E1%BB%9Di+ti%E1%BA%BFt", UrlUtil.fromInput("thời tiết", T));
        assertEquals("https://s.example/?q=hello", UrlUtil.fromInput("hello", T));
        assertEquals("https://s.example/?q=v1.2", UrlUtil.fromInput("v1.2", T));
        assertEquals("https://s.example/?q=a+b.com", UrlUtil.fromInput("a b.com", T));
        assertNull(UrlUtil.fromInput("   ", T));
    }

    @Test public void hosts() {
        assertEquals("a.b.com", UrlUtil.host("https://user:pw@A.B.com:8443/x?y#z"));
        assertEquals("", UrlUtil.host("about:blank"));
        assertEquals("https://a.com", UrlUtil.origin("https://a.com/x/y"));
        assertEquals("bbc.co.uk", UrlUtil.siteOf("news.bbc.co.uk"));
        assertEquals("vnexpress.net", UrlUtil.siteOf("s1.vnecdn.vnexpress.net"));
        assertEquals("com.vn", UrlUtil.siteOf("com.vn"));
        assertEquals("tuoitre.com.vn", UrlUtil.siteOf("cdn.tuoitre.com.vn"));
        assertTrue(UrlUtil.sameSite("a.example.com", "b.example.com"));
        assertFalse(UrlUtil.sameSite("example.com", "doubleclick.net"));
    }

    @Test public void referrerPolicy() {
        assertEquals("https://a.com/p?q=1", UrlUtil.referrer("https://a.com/p?q=1#h", "https://a.com/other"));
        assertEquals("https://a.com/", UrlUtil.referrer("https://a.com/p", "https://b.com/"));
        assertNull(UrlUtil.referrer("https://a.com/p", "http://b.com/"));
        assertNull(UrlUtil.referrer("", "https://b.com/"));
    }

    @Test public void kinds() {
        assertEquals(UrlUtil.KIND_IMAGE, UrlUtil.kindOf("https://x/y/photo.JPG?w=1"));
        assertEquals(UrlUtil.KIND_CSS, UrlUtil.kindOf("https://x/app.css"));
        assertEquals(UrlUtil.KIND_CSS, UrlUtil.kindOf("https://fonts.googleapis.com/css2?family=Roboto"));
        assertEquals(UrlUtil.KIND_SCRIPT, UrlUtil.kindOf("https://x/a.min.js#x"));
        assertEquals(UrlUtil.KIND_FONT, UrlUtil.kindOf("https://x/f.woff2"));
        assertEquals(UrlUtil.KIND_OTHER, UrlUtil.kindOf("https://x/api/data"));
        assertEquals(UrlUtil.KIND_OTHER, UrlUtil.kindOf("https://x.com/"));
        assertEquals(UrlUtil.KIND_IMAGE, UrlUtil.kindOf("https://img.x/abc?fm=webp&w=800"));
        assertEquals("", UrlUtil.extension("https://x/dir.v2/file"));
    }

    @Test public void contentType() {
        assertEquals("Shift_JIS", UrlUtil.charsetOf("text/html; charset=\"Shift_JIS\""));
        assertNull(UrlUtil.charsetOf("text/html"));
        assertEquals("text/html", UrlUtil.mimeOf("Text/HTML; charset=utf-8"));
    }

    @Test public void escaping() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;", UrlUtil.htmlEscape("<a href=\"x\">"));
        assertEquals("\"a\\\"b\\u003c/script\\u003e\"", UrlUtil.jsString("a\"b</script>"));
    }
}
