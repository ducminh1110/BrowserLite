package com.browserlite.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class YouTubeTest {
    private static JSONObject fixture(String name) throws Exception {
        InputStream in = YouTubeTest.class.getClassLoader().getResourceAsStream(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new JSONObject(bos.toString("UTF-8"));
    }

    @Test public void videoIds() {
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?rel=0"));
        assertNull(YouTube.videoId("https://www.youtube.com/results?search_query=x"));
        assertNull(YouTube.videoId("https://example.com/watch?v=dQw4w9WgXcQ"));
        assertNull(YouTube.videoId("https://www.youtube.com/watch?v=short"));
        assertEquals("dQw4w9WgXcQ", YouTube.embedId("https://www.youtube.com/embed/dQw4w9WgXcQ"));
        assertNull(YouTube.embedId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertNull(YouTube.embedId("https://www.youtube.com/embed/videoseries?list=PL1"));
    }

    @Test public void searchFeed() throws Exception {
        YouTube.Feed f = YouTube.feed(fixture("yt_search.json"));
        assertEquals(3, f.items.size());
        YouTube.Item v = f.items.get(0);
        assertEquals(YouTube.VIDEO, v.type);
        assertEquals("n61ULEU7CO0", v.id);
        assertFalse(v.title.isEmpty());
        assertEquals("Lofi Girl", v.channel);
        assertTrue(v.channelId.startsWith("UC"));
        assertFalse(v.duration.isEmpty());
        assertTrue(v.meta.contains("·"));
        assertEquals("https://i.ytimg.com/vi/n61ULEU7CO0/mqdefault.jpg", v.thumb);
        assertEquals(YouTube.SHORT, f.items.get(2).type);
        assertNotNull(f.continuation);
        assertFalse("engagement panel tokens are not the list's", f.continuation.equals("WRONG"));
    }

    @Test public void playerStreams() throws Exception {
        YouTube.Video v = YouTube.parsePlayer(fixture("yt_player.json"), "dQw4w9WgXcQ");
        assertNull(v.error);
        assertEquals(213, v.lengthSec);
        assertEquals("Rick Astley", v.author);
        assertTrue(v.views > 1_000_000_000L);
        YouTube.Stream muxed = null;
        for (YouTube.Stream s : v.streams) {
            assertFalse("ciphered streams are skipped", s.itag == 999);
            if (s.itag == 18) muxed = s;
        }
        assertNotNull(muxed);
        assertTrue(muxed.video && muxed.audio);
        // itag 18 is really Main profile whatever its mimeType says
        assertEquals("avc1.4D401E", muxed.codecs.get(0));
    }

    @Test public void playerErrors() throws Exception {
        YouTube.Video v = YouTube.parsePlayer(new JSONObject(
                "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\",\"reason\":\"Sign in to confirm your age\"}}"), "x");
        assertEquals("Sign in to confirm your age", v.error);
        assertTrue(v.streams.isEmpty());
    }

    private static YouTube.Stream stream(int itag, String mime, int height) {
        YouTube.Stream s = new YouTube.Stream();
        s.itag = itag;
        s.url = "https://x/" + itag;
        YouTube.parseMime(mime, s);
        s.height = height;
        return s;
    }

    private static List<YouTube.Stream> youtubeLike() {
        List<YouTube.Stream> l = new ArrayList<>();
        l.add(stream(18, "video/mp4; codecs=\"avc1.4D401E, mp4a.40.2\"", 360));
        l.add(stream(160, "video/mp4; codecs=\"avc1.4d400c\"", 144));
        l.add(stream(133, "video/mp4; codecs=\"avc1.4d4015\"", 240));
        l.add(stream(134, "video/mp4; codecs=\"avc1.4d401e\"", 360));
        l.add(stream(135, "video/mp4; codecs=\"avc1.4d401f\"", 480));
        l.add(stream(136, "video/mp4; codecs=\"avc1.4d401f\"", 720));
        l.add(stream(243, "video/webm; codecs=\"vp9\"", 360));
        l.add(stream(396, "video/mp4; codecs=\"av01.0.01M.08\"", 360));
        l.add(stream(140, "audio/mp4; codecs=\"mp4a.40.2\"", 0));
        l.add(stream(251, "audio/webm; codecs=\"opus\"", 0));
        return l;
    }

    private static StreamPicker.Caps caps(boolean base, boolean main, boolean aac) {
        StreamPicker.Caps c = new StreamPicker.Caps();
        c.avcBaseline = base;
        c.avcMain = main;
        c.aac = aac;
        c.hwAvc = main; // vendor decoders handle Main; Android's software one only Baseline
        return c;
    }

    @Test public void kitkatSoftwareDecodersPreferBuiltinH264OverSoftwareVp9() {
        // The Android 4.4 emulator: OMX.google.h264 (Baseline only) and OMX.google.vp9, both software.
        StreamPicker.Caps kitkat = caps(true, false, true);
        kitkat.vp9 = true;
        StreamPicker.Choice c = StreamPicker.pick(youtubeLike(), kitkat, "auto", false, false, 360, true);
        assertTrue(c.builtin);
        assertEquals(134, c.stream.itag);
        // without the bundled decoder the software VP9 is still better than nothing
        StreamPicker.Choice n = StreamPicker.pick(youtubeLike(), kitkat, "auto", false, false, 360, false);
        assertFalse(n.builtin);
        assertEquals(243, n.stream.itag);
    }

    @Test public void hardwareVp9BeatsSoftwareH264() {
        StreamPicker.Caps c = caps(true, false, true);
        c.vp9 = true;
        c.hwVp9 = true;
        StreamPicker.Choice ch = StreamPicker.pick(youtubeLike(), c, "auto", false, false, 360, true);
        assertFalse(ch.builtin);
        assertEquals(243, ch.stream.itag);
    }

    @Test public void mutedPrefersPictureOnlyH264() {
        StreamPicker.Choice c = StreamPicker.pick(youtubeLike(), caps(true, true, true), "auto", false, false, 360, true);
        assertEquals(134, c.stream.itag);
        assertFalse(c.builtin);
        assertEquals(133, StreamPicker.pick(youtubeLike(), caps(true, true, true), "auto", false, false, 240, true).stream.itag);
    }

    @Test public void baselineOnlyDeviceGetsBuiltinDecoder() {
        // KitKat's software decoder: Baseline only, so YouTube's Main-profile streams need the bundled one.
        StreamPicker.Choice c = StreamPicker.pick(youtubeLike(), caps(true, false, true), "auto", false, true, 360, true);
        assertEquals(18, c.stream.itag);
        assertTrue(c.builtin);
    }

    @Test public void noDecodersAtAll() {
        StreamPicker.Caps none = new StreamPicker.Caps();
        StreamPicker.Choice c = StreamPicker.pick(youtubeLike(), none, "auto", false, false, 360, true);
        assertTrue(c.builtin);
        assertEquals(134, c.stream.itag);
        // and without the bundled decoder (unsupported CPU): nothing can play the picture
        assertNull(StreamPicker.pick(youtubeLike(), none, "auto", false, false, 360, false));
    }

    @Test public void neverHevcVp9Av1ForTheBuiltinDecoder() {
        List<YouTube.Stream> only = new ArrayList<>();
        only.add(stream(243, "video/webm; codecs=\"vp9\"", 360));
        only.add(stream(396, "video/mp4; codecs=\"av01.0.01M.08\"", 360));
        only.add(stream(1, "video/mp4; codecs=\"hvc1.1.6.L93.B0\"", 360));
        assertNull(StreamPicker.pick(only, new StreamPicker.Caps(), "auto", false, false, 360, true));
    }

    @Test public void soundOnDeviceDecoders() {
        StreamPicker.Choice c = StreamPicker.pick(youtubeLike(), caps(true, true, true), "auto", false, true, 360, true);
        assertEquals(18, c.stream.itag);
        assertFalse(c.builtin);
        StreamPicker.Choice a = StreamPicker.pick(youtubeLike(), caps(true, true, true), "auto", true, true, 360, true);
        assertEquals(140, a.stream.itag);
        assertTrue(a.audioOnly);
    }

    @Test public void soundOnButNoVideoDecoderAndNoBuiltin() {
        StreamPicker.Choice c = StreamPicker.pick(youtubeLike(), caps(false, false, true), "auto", false, true, 360, false);
        assertTrue(c.audioOnly);
        assertEquals(StreamPicker.NO_VIDEO_DECODER, c.reason);
    }

    @Test public void forcedModes() {
        assertTrue(StreamPicker.pick(youtubeLike(), caps(true, true, true), "builtin", false, false, 360, true).builtin);
        assertFalse(StreamPicker.pick(youtubeLike(), caps(true, true, true), "system", false, false, 360, true).builtin);
    }

    @Test public void codecStrings() {
        StreamPicker.Caps base = caps(true, false, true);
        assertTrue(StreamPicker.decodes(base, "avc1.42001E"));
        assertFalse(StreamPicker.decodes(base, "avc1.4D401E"));
        assertFalse(StreamPicker.decodes(base, "avc1.64001F"));
        assertTrue(StreamPicker.decodes(StreamPicker.Caps.builtin(), "avc1.64001F"));
        assertFalse(StreamPicker.decodes(StreamPicker.Caps.builtin(), "hev1.1.6.L93.B0"));
        assertTrue(StreamPicker.decodes(base, "mp4a.40.5"));
    }
}
