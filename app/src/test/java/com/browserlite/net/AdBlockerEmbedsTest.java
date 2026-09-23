package com.browserlite.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import org.junit.Test;

public class AdBlockerEmbedsTest {
    @Test public void blocksSubdomainsNotParents() {
        AdBlocker b = new AdBlocker();
        b.addRule("doubleclick.net");
        b.addRule("0.0.0.0 ads.example.com");
        b.addRule("||tracker.io^");
        b.addRule("# comment");
        b.addRule("com");
        assertTrue(b.isBlocked("doubleclick.net"));
        assertTrue(b.isBlocked("stats.g.doubleclick.net"));
        assertTrue(b.isBlocked("ads.example.com"));
        assertTrue(b.isBlocked("x.tracker.io"));
        assertFalse(b.isBlocked("example.com"));
        assertFalse(b.isBlocked("notdoubleclick.net"));
        assertFalse(b.isBlocked("google.com"));
        assertEquals(3, b.size());
    }

    @Test public void bundledListLoads() throws IOException {
        AdBlocker b = AdBlocker.load(new FileInputStream("src/main/assets/adhosts.txt"));
        assertTrue(b.size() > 200);
        assertTrue(b.isBlocked("securepubads.g.doubleclick.net"));
        assertTrue(b.isBlocked("pagead2.googlesyndication.com"));
        assertFalse(b.isBlocked("www.google.com"));
        assertFalse(b.isBlocked("fonts.googleapis.com"));
        assertFalse(b.isBlocked("vnexpress.net"));
        assertFalse(b.isBlocked("i1-vnexpress.vnecdn.net"));
    }

    @Test public void embeds() {
        Embeds.Match m = Embeds.match("https://www.youtube.com/embed/abc123?rel=0");
        assertNotNull(m);
        assertEquals("https://m.youtube.com/watch?v=abc123", m.target);
        assertEquals("https://vimeo.com/42", Embeds.match("https://player.vimeo.com/video/42").target);
        assertEquals("https://x.com/i/status/99", Embeds.match("https://platform.twitter.com/embed/Tweet.html?id=99").target);
        assertEquals("https://www.facebook.com/p/1", Embeds.match("https://www.facebook.com/plugins/post.php?href=https%3A%2F%2Fwww.facebook.com%2Fp%2F1").target);
        assertNull(Embeds.match("https://www.facebook.com/plugins/like.php?href=x").target);
        assertNull(Embeds.match("https://www.youtube.com/watch?v=abc"));
        assertNull(Embeds.match("https://www.google.com/recaptcha/api2/anchor"));
        assertNull(Embeds.match("https://example.com/embed/x"));
    }
}
