package com.browserlite.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Subtitles of a YouTube video: the track list from the player response, and the timed lines of one track in
 * whichever format the server answers with (timedtext XML, the older XML, JSON, or WebVTT from Invidious).
 */
public final class Captions {
    private Captions() {}

    public static final class Track {
        public String url, lang = "", name = "";
        /** Speech recognition ("auto-generated"): the language actually spoken. */
        public boolean auto;
        public boolean translatable;

        @Override
        public String toString() {
            return lang + (auto ? " (auto)" : "") + " " + name;
        }
    }

    public static final class Cue {
        public final long start, end;
        public final String text;

        public Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    /** What to show: a track, optionally machine-translated into {@code translateTo}. */
    public static final class Choice {
        public final Track track;
        public final String translateTo;

        public Choice(Track track, String translateTo) {
            this.track = track;
            this.translateTo = translateTo;
        }
    }

    private static final int MAX_BYTES = 3 << 20;

    // ------------------------------------------------------------------ tracks

    /** {@code captions.playerCaptionsTracklistRenderer.captionTracks} of a player response. */
    static List<Track> parseTracks(JSONObject player) {
        List<Track> out = new ArrayList<>();
        JSONObject c = player.optJSONObject("captions");
        JSONObject r = c == null ? null : c.optJSONObject("playerCaptionsTracklistRenderer");
        JSONArray a = r == null ? null : r.optJSONArray("captionTracks");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            String url = t.optString("baseUrl", "");
            if (url.isEmpty()) continue;
            Track tr = new Track();
            tr.url = url.startsWith("/") ? "https://www.youtube.com" + url : url;
            tr.lang = t.optString("languageCode", "");
            tr.auto = "asr".equals(t.optString("kind", ""));
            tr.translatable = t.optBoolean("isTranslatable", false);
            JSONObject name = t.optJSONObject("name");
            tr.name = name == null ? tr.lang : name.has("simpleText") ? name.optString("simpleText") : runs(name);
            if (tr.name.isEmpty()) tr.name = tr.lang;
            out.add(tr);
        }
        return out;
    }

    private static String runs(JSONObject o) {
        JSONArray runs = o.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
        }
        return sb.toString();
    }

    /** Java still says "in"/"iw"/"ji" for Indonesian/Hebrew/Yiddish; YouTube uses "id"/"iw"/"yi". */
    public static String youtubeLanguage(Locale l) {
        String lang = l.getLanguage();
        if (lang.equals("in")) return "id";
        if (lang.equals("ji")) return "yi";
        return lang;
    }

    public static boolean sameLanguage(String a, String b) {
        return base(a).equals(base(b));
    }

    private static String base(String code) {
        String c = code == null ? "" : code.toLowerCase(Locale.US);
        int dash = c.indexOf('-');
        return dash > 0 ? c.substring(0, dash) : c;
    }

    /** The track in the spoken language: a written one when there is one, else the recognised speech. */
    public static Track original(List<Track> tracks) {
        if (tracks.isEmpty()) return null;
        Track asr = null;
        for (Track t : tracks) if (t.auto) {
            asr = t;
            break;
        }
        if (asr != null) {
            for (Track t : tracks) if (!t.auto && sameLanguage(t.lang, asr.lang)) return t;
            return asr;
        }
        return tracks.get(0);
    }

    /**
     * Subtitles in the reader's language: a written track, else recognised speech, else a translation of the
     * original track. Null when there is nothing to show.
     */
    public static Choice forLanguage(List<Track> tracks, String lang) {
        Track auto = null;
        for (Track t : tracks) {
            if (!sameLanguage(t.lang, lang)) continue;
            if (!t.auto) return new Choice(t, null);
            if (auto == null) auto = t;
        }
        if (auto != null) return new Choice(auto, null);
        Track src = original(tracks);
        if (src == null) return null;
        if (src.translatable) return new Choice(src, lang);
        for (Track t : tracks) if (t.translatable) return new Choice(t, lang);
        return null;
    }

    // ------------------------------------------------------------------ lines

    /** Downloads and parses one track; YouTube's subtitle server sometimes asks to slow down (429): wait and retry. */
    public static List<Cue> fetch(OkHttpClient http, Choice c, String userAgent) throws IOException {
        String url = c.track.url;
        if (c.translateTo != null && !c.translateTo.isEmpty()) url += (url.contains("?") ? "&" : "?") + "tlang=" + c.translateTo;
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(attempt * 2500L);
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
            }
            Request.Builder rb = new Request.Builder().url(url);
            if (userAgent != null) rb.header("User-Agent", userAgent);
            try (Response r = http.newCall(rb.build()).execute()) {
                ResponseBody b = r.body();
                if (r.code() == 429 || r.code() >= 500) {
                    last = new IOException("HTTP " + r.code());
                    continue;
                }
                if (!r.isSuccessful() || b == null) throw new IOException("HTTP " + r.code());
                if (b.contentLength() > MAX_BYTES) throw new IOException("subtitles too large");
                String text = b.string();
                List<Cue> cues = parse(text);
                if (cues.isEmpty() && text.length() > 0 && !text.trim().startsWith("<?xml") && !text.contains("WEBVTT")
                        && !text.trim().startsWith("{")) {
                    last = new IOException("unexpected answer");
                    continue;
                }
                return cues;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("subtitles");
    }

    /** Sniffs the format and returns the lines sorted by start time. */
    public static List<Cue> parse(String text) {
        List<Cue> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.trim();
        if (t.startsWith("{")) {
            parseJson3(t, out);
        } else if (t.startsWith("WEBVTT")) {
            parseVtt(t, out);
        } else if (t.contains("<p ") || t.contains("<p>")) {
            parseXml(t, "p", out, false);
        } else if (t.contains("<text")) {
            parseXml(t, "text", out, true);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    private static void parseJson3(String t, List<Cue> out) {
        try {
            JSONArray events = new JSONObject(t).optJSONArray("events");
            if (events == null) return;
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null) continue;
                JSONArray segs = e.optJSONArray("segs");
                if (segs == null) continue;
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < segs.length(); j++) {
                    JSONObject s = segs.optJSONObject(j);
                    if (s != null) sb.append(s.optString("utf8", ""));
                }
                long start = e.optLong("tStartMs"), dur = e.optLong("dDurationMs");
                add(out, start, start + dur, sb.toString());
            }
        } catch (JSONException ignored) {
            // not JSON after all
        }
    }

    /** {@code <p t="ms" d="ms">..</p>} (timedtext format 3) or {@code <text start="s" dur="s">..</text>}. */
    private static void parseXml(String t, String tag, List<Cue> out, boolean seconds) {
        String open = "<" + tag, close = "</" + tag + ">";
        int i = 0;
        while (true) {
            int a = t.indexOf(open, i);
            if (a < 0) break;
            char next = a + open.length() < t.length() ? t.charAt(a + open.length()) : '>';
            int gt = t.indexOf('>', a);
            if (gt < 0) break;
            if (next != ' ' && next != '>' && next != '\t' && next != '\n') {
                i = gt + 1;
                continue;
            }
            String attrs = t.substring(a + open.length(), gt);
            int end;
            String inner;
            if (attrs.endsWith("/")) {
                end = gt + 1;
                inner = "";
            } else {
                end = t.indexOf(close, gt);
                if (end < 0) break;
                inner = t.substring(gt + 1, end);
                end += close.length();
            }
            i = end;
            long start, dur;
            if (seconds) {
                start = secondsAttr(attrs, "start");
                dur = secondsAttr(attrs, "dur");
            } else {
                start = longAttr(attrs, "t");
                dur = longAttr(attrs, "d");
            }
            if (start < 0) continue;
            String text = stripTags(inner.replace("<br />", "\n").replace("<br/>", "\n").replace("<br>", "\n"));
            text = decode(text);
            if (text.contains("&")) text = decode(text); // the old format escapes twice
            add(out, start, start + Math.max(0, dur), text);
        }
    }

    private static void parseVtt(String t, List<Cue> out) {
        String[] lines = t.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int arrow = l.indexOf("-->");
            if (arrow < 0) continue;
            long start = vttTime(l.substring(0, arrow).trim());
            String rest = l.substring(arrow + 3).trim();
            int sp = rest.indexOf(' ');
            long end = vttTime(sp > 0 ? rest.substring(0, sp) : rest);
            StringBuilder sb = new StringBuilder();
            while (i + 1 < lines.length && !lines[i + 1].trim().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[++i]);
            }
            if (start >= 0 && end >= 0) add(out, start, end, decode(stripTags(sb.toString())));
        }
    }

    private static long vttTime(String s) {
        // hh:mm:ss.mmm or mm:ss.mmm
        String[] p = s.split(":");
        try {
            double sec = Double.parseDouble(p[p.length - 1].replace(',', '.'));
            long ms = Math.round(sec * 1000);
            if (p.length >= 2) ms += Long.parseLong(p[p.length - 2]) * 60_000L;
            if (p.length >= 3) ms += Long.parseLong(p[p.length - 3]) * 3_600_000L;
            return ms;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void add(List<Cue> out, long start, long end, String text) {
        String s = text.replace('\u00a0', ' ').trim();
        if (s.isEmpty()) return;
        // collapse runs of blank lines and spaces left by markup
        s = s.replaceAll("[ \t]+", " ").replaceAll("\\s*\n\\s*", "\n");
        out.add(new Cue(start, end > start ? end : start + 2000, s));
    }

    private static String attr(String attrs, String name) {
        int i = 0;
        while (true) {
            int a = attrs.indexOf(name + "=\"", i);
            if (a < 0) return null;
            if (a == 0 || Character.isWhitespace(attrs.charAt(a - 1))) {
                int from = a + name.length() + 2;
                int to = attrs.indexOf('"', from);
                return to < 0 ? null : attrs.substring(from, to);
            }
            i = a + 1;
        }
    }

    private static long longAttr(String attrs, String name) {
        String v = attr(attrs, name);
        if (v == null) return -1;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long secondsAttr(String attrs, String name) {
        String v = attr(attrs, name);
        if (v == null) return -1;
        try {
            return Math.round(Double.parseDouble(v.trim()) * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String stripTags(String s) {
        if (s.indexOf('<') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') in = true;
            else if (c == '>') in = false;
            else if (!in) sb.append(c);
        }
        return sb.toString();
    }

    static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int semi;
            if (c == '&' && (semi = s.indexOf(';', i)) > i && semi - i <= 10) {
                String ent = s.substring(i + 1, semi);
                String rep = null;
                switch (ent) {
                    case "amp": rep = "&"; break;
                    case "lt": rep = "<"; break;
                    case "gt": rep = ">"; break;
                    case "quot": rep = "\""; break;
                    case "apos": rep = "'"; break;
                    case "nbsp": rep = " "; break;
                    default:
                        if (ent.startsWith("#")) {
                            try {
                                int cp = ent.startsWith("#x") || ent.startsWith("#X") ? Integer.parseInt(ent.substring(2), 16)
                                        : Integer.parseInt(ent.substring(1));
                                rep = new String(Character.toChars(cp));
                            } catch (IllegalArgumentException ignored) {
                                // leave as is
                            }
                        }
                        break;
                }
                if (rep != null) {
                    sb.append(rep);
                    i = semi;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Text to show at {@code ms}: the lines on screen at that moment, at most two (speech recognition rolls lines
     * up, so the previous one stays while the next appears). Empty when nothing is said.
     */
    public static String textAt(List<Cue> cues, long ms) {
        if (cues == null || cues.isEmpty()) return "";
        int lo = 0, hi = cues.size() - 1, last = -1;
        while (lo <= hi) { // last cue starting at or before ms
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).start <= ms) {
                last = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (last < 0) return "";
        String a = null, b = null;
        for (int i = last; i >= 0 && i > last - 12; i--) {
            Cue c = cues.get(i);
            if (c.end <= ms) continue;
            if (b == null) b = c.text;
            else if (!c.text.equals(b)) {
                a = c.text;
                break;
            }
        }
        if (b == null) return "";
        return a == null ? b : a + "\n" + b;
    }
}
