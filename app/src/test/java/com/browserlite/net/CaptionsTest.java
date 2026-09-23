package com.browserlite.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class CaptionsTest {
    // As answered by YouTube's timedtext server (format 3), written and speech-recognised.
    private static final String SRV3 = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><timedtext format=\"3\">\n<body>\n"
            + "<p t=\"1360\" d=\"1680\">[♪♪♪]</p>\n"
            + "<p t=\"18640\" d=\"3240\">♪ We&#39;re no strangers to love ♪</p>\n"
            + "<p t=\"22640\" d=\"4320\">♪ You know the rules\nand so do I ♪</p>\n</body></timedtext>";
    private static final String ASR = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><timedtext format=\"3\">\n<head>\n"
            + "<ws id=\"0\"/>\n<wp id=\"1\" ap=\"6\" ah=\"20\" av=\"100\" rc=\"2\" cc=\"40\"/>\n</head>\n<body>\n"
            + "<w t=\"0\" id=\"1\" wp=\"1\" ws=\"1\"/>\n"
            + "<p t=\"320\" d=\"14260\" w=\"1\">[Music]</p>\n"
            + "<p t=\"18790\" w=\"1\" a=\"1\">\n</p>\n"
            + "<p t=\"18800\" d=\"7160\" w=\"1\"><s ac=\"0\">We&#39;re</s><s t=\"239\" ac=\"0\"> no</s><s t=\"559\" ac=\"0\"> strangers</s></p>\n"
            + "<p t=\"21790\" d=\"4170\" w=\"1\" a=\"1\">\n</p>\n"
            + "<p t=\"21800\" d=\"7319\" w=\"1\"><s ac=\"0\">you</s><s t=\"240\" ac=\"0\"> know</s></p>\n"
            + "</body></timedtext>";

    @Test
    public void parsesTimedTextFormat3() {
        List<Captions.Cue> c = Captions.parse(SRV3);
        assertEquals(3, c.size());
        assertEquals(18640, c.get(1).start);
        assertEquals(21880, c.get(1).end);
        assertEquals("♪ We're no strangers to love ♪", c.get(1).text);
        assertEquals("♪ You know the rules\nand so do I ♪", c.get(2).text);
        assertEquals("", Captions.textAt(c, 5000));
        assertEquals("♪ We're no strangers to love ♪", Captions.textAt(c, 20000));
    }

    @Test
    public void speechRecognitionRollsUpTwoLines() {
        List<Captions.Cue> c = Captions.parse(ASR);
        assertEquals(3, c.size()); // the empty "append" paragraphs are dropped
        assertEquals("We're no strangers", c.get(1).text);
        assertEquals("We're no strangers", Captions.textAt(c, 20000));
        assertEquals("We're no strangers\nyou know", Captions.textAt(c, 23000));
        assertEquals("you know", Captions.textAt(c, 27000));
        assertEquals("", Captions.textAt(c, 40000));
    }

    @Test
    public void parsesOtherFormats() {
        List<Captions.Cue> srv1 = Captions.parse("<?xml version=\"1.0\"?><transcript><text start=\"1.5\" dur=\"2.25\">"
                + "It&amp;#39;s &amp;quot;fine&amp;quot;</text><text start=\"4\" dur=\"1\">next</text></transcript>");
        assertEquals(2, srv1.size());
        assertEquals(1500, srv1.get(0).start);
        assertEquals(3750, srv1.get(0).end);
        assertEquals("It's \"fine\"", srv1.get(0).text);

        List<Captions.Cue> json = Captions.parse("{\"events\":[{\"tStartMs\":100,\"dDurationMs\":900,\"segs\":[{\"utf8\":\"Xin \"},"
                + "{\"utf8\":\"chào\"}]},{\"tStartMs\":1000,\"dDurationMs\":10,\"segs\":[{\"utf8\":\"\\n\"}]}]}");
        assertEquals(1, json.size());
        assertEquals("Xin chào", json.get(0).text);

        List<Captions.Cue> vtt = Captions.parse("WEBVTT\nKind: captions\n\n00:00:01.000 --> 00:00:02.500 align:start\n"
                + "<c>Hello</c> there\nsecond line\n\n01:02.000 --> 01:03.000\nlater\n");
        assertEquals(2, vtt.size());
        assertEquals(1000, vtt.get(0).start);
        assertEquals("Hello there\nsecond line", vtt.get(0).text);
        assertEquals(62000, vtt.get(1).start);
    }

    @Test
    public void choosesTracks() throws Exception {
        JSONObject player = new JSONObject("{\"captions\":{\"playerCaptionsTracklistRenderer\":{\"captionTracks\":["
                + "{\"baseUrl\":\"https://www.youtube.com/api/timedtext?v=x&lang=en\",\"name\":{\"runs\":[{\"text\":\"English\"}]},\"languageCode\":\"en\",\"isTranslatable\":true},"
                + "{\"baseUrl\":\"https://www.youtube.com/api/timedtext?v=x&lang=en&kind=asr\",\"name\":{\"runs\":[{\"text\":\"English (auto-generated)\"}]},\"languageCode\":\"en\",\"kind\":\"asr\",\"isTranslatable\":true},"
                + "{\"baseUrl\":\"https://www.youtube.com/api/timedtext?v=x&lang=de-DE\",\"name\":{\"simpleText\":\"German (Germany)\"},\"languageCode\":\"de-DE\",\"isTranslatable\":true}"
                + "]}}}");
        List<Captions.Track> t = Captions.parseTracks(player);
        assertEquals(3, t.size());
        assertTrue(t.get(1).auto);
        assertEquals("German (Germany)", t.get(2).name);

        // written English beats recognised English as the spoken language
        assertEquals(t.get(0), Captions.original(t));
        Captions.Choice de = Captions.forLanguage(t, "de");
        assertEquals(t.get(2), de.track);
        assertNull(de.translateTo);
        Captions.Choice vi = Captions.forLanguage(t, "vi");
        assertEquals(t.get(0), vi.track);
        assertEquals("vi", vi.translateTo);
    }

    @Test
    public void probesDeepInsideLargeStreams() {
        assertEquals(0, YouTube.probeOffset(0));
        assertEquals(0, YouTube.probeOffset(2 << 20));
        assertEquals(20_000_000, YouTube.probeOffset(30_000_000));
    }
}
