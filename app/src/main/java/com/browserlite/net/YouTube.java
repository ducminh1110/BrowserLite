package com.browserlite.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to YouTube's own JSON API (InnerTube) instead of running youtube.com, whose JavaScript needs a far newer
 * engine than KitKat's. Lists (search, related, channels, playlists) come from the mobile web client; stream URLs
 * come from app clients, which return plain progressive MP4 links that the device's player (or our built-in
 * decoder) can play without running any of YouTube's code.
 */
public final class YouTube {
    private YouTube() {}

    private static final String[] API = {"https://youtubei.googleapis.com/youtubei/v1/", "https://www.youtube.com/youtubei/v1/"};
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int MAX_RESPONSE = 3 << 20;

    static final class Client {
        final String name, version, userAgent, id;
        final String[] extra;

        Client(String name, String version, String id, String userAgent, String... extra) {
            this.name = name;
            this.version = version;
            this.id = id;
            this.userAgent = userAgent;
            this.extra = extra;
        }
    }

    static final Client MWEB = new Client("MWEB", "2.20250925.01.00", "2",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36");
    static final Client VR = new Client("ANDROID_VR", "1.62.27", "28",
            "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "deviceMake", "Oculus", "deviceModel", "Quest 3", "androidSdkVersion", "32", "osName", "Android",
            "osVersion", "12L");
    static final Client ANDROID = new Client("ANDROID", "20.10.38", "3",
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            "androidSdkVersion", "30", "osName", "Android", "osVersion", "11");
    static final Client IOS = new Client("IOS", "20.10.4", "5",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            "deviceMake", "Apple", "deviceModel", "iPhone16,2", "osName", "iPhone", "osVersion", "18.3.2.22D82");

    /** Error text YouTube uses when it wants proof of a human (usually the network's IP is flagged for a while). */
    public static boolean isBotCheck(String error) {
        if (error == null) return false;
        String e = error.toLowerCase(Locale.US);
        return e.contains("not a bot") || e.contains("sign in to confirm") || e.contains("unusual traffic")
                || e.contains("http 429");
    }

    private static volatile String visitorData;

    /** An anonymous visitor id, as the apps send it: requests without one are refused more often. */
    static String visitorData(OkHttpClient http) {
        String v = visitorData;
        if (v != null) return v;
        try {
            JSONObject body = new JSONObject();
            JSONObject o = call(http, VR, "visitor_id", body, "en", "US", false);
            JSONObject rc = o.optJSONObject("responseContext");
            v = rc == null ? "" : rc.optString("visitorData", "");
        } catch (IOException e) {
            v = null;
        }
        if (v != null && !v.isEmpty()) visitorData = v;
        return v == null || v.isEmpty() ? null : v;
    }

    // ------------------------------------------------------------------ URLs

    public static boolean isYouTubeHost(String host) {
        if (host == null) return false;
        switch (host) {
            case "youtube.com":
            case "www.youtube.com":
            case "m.youtube.com":
            case "music.youtube.com":
            case "youtu.be":
            case "www.youtu.be":
            case "youtube-nocookie.com":
            case "www.youtube-nocookie.com":
                return true;
            default:
                return false;
        }
    }

    static boolean validId(String id) {
        if (id == null || id.length() != 11 || id.equals("videoseries")) return false; // playlist embeds
        for (int i = 0; i < 11; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** Video id of any YouTube video URL (watch, youtu.be, shorts, live, embed), or null. */
    public static String videoId(String url) {
        String host = UrlUtil.host(url);
        if (!isYouTubeHost(host)) return null;
        String path = Embeds.pathOf(url);
        if (host.endsWith("youtu.be")) {
            String id = Embeds.segment(path, 1);
            return validId(id) ? id : null;
        }
        if (path.equals("/watch") || path.equals("/watch/")) {
            String v = Embeds.param(url, "v");
            return validId(v) ? v : null;
        }
        String first = Embeds.segment(path, 1);
        if (first.equals("shorts") || first.equals("live") || first.equals("embed") || first.equals("v") || first.equals("e")) {
            String id = Embeds.segment(path, 2);
            return validId(id) ? id : null;
        }
        return null;
    }

    /** Video id when {@code url} is an embedded player (iframe), or null. */
    public static String embedId(String url) {
        String host = UrlUtil.host(url);
        if (!isYouTubeHost(host) || host.endsWith("youtu.be")) return null;
        String first = Embeds.segment(Embeds.pathOf(url), 1);
        if (!first.equals("embed") && !first.equals("v")) return null;
        return videoId(url);
    }

    // ------------------------------------------------------------------ model

    public static final int VIDEO = 0, SHORT = 1, CHANNEL = 2, PLAYLIST = 3;

    public static final class Item {
        public int type;
        public String id = "", title = "", channel = "", channelId = "", meta = "", duration = "", thumb = "";
        public boolean live;
    }

    public static final class Feed {
        public final List<Item> items = new ArrayList<>();
        public String continuation;
        public String title = "";
        public String subtitle = "";
        public String description = "";
    }

    public static final class Stream {
        public int itag;
        public String url, mime = "", quality = "";
        public List<String> codecs = new ArrayList<>();
        public int width, height, bitrate;
        public long length;
        public boolean video, audio;

        @Override
        public String toString() {
            return itag + " " + mime + " " + codecs + " " + height + "p";
        }
    }

    public static final class Video {
        public String id, title = "", author = "", channelId = "", description = "", hls, error, client = "";
        /** User-Agent of the client that got the links; the video servers see the same one. */
        public String userAgent;
        public long views = -1;
        public int lengthSec;
        public boolean live;
        public final List<Stream> streams = new ArrayList<>();
        long fetched;
    }

    // ------------------------------------------------------------------ requests

    private static JSONObject context(Client c, String hl, String gl) throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", c.name);
        client.put("clientVersion", c.version);
        client.put("hl", hl);
        client.put("gl", gl);
        for (int i = 0; i + 1 < c.extra.length; i += 2) {
            String v = c.extra[i + 1];
            if (c.extra[i].equals("androidSdkVersion")) client.put(c.extra[i], Integer.parseInt(v));
            else client.put(c.extra[i], v);
        }
        JSONObject ctx = new JSONObject();
        ctx.put("client", client);
        return ctx;
    }

    static JSONObject call(OkHttpClient http, Client c, String endpoint, JSONObject body, String hl, String gl)
            throws IOException {
        return call(http, c, endpoint, body, hl, gl, true);
    }

    static JSONObject call(OkHttpClient http, Client c, String endpoint, JSONObject body, String hl, String gl,
            boolean withVisitor) throws IOException {
        IOException last = null;
        String payload;
        String visitor = withVisitor ? visitorData(http) : null;
        try {
            JSONObject ctx = context(c, hl, gl);
            if (visitor != null) ctx.getJSONObject("client").put("visitorData", visitor);
            body.put("context", ctx);
            payload = body.toString();
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
        for (String base : API) {
            Request.Builder req = new Request.Builder()
                    .url(base + endpoint + "?prettyPrint=false")
                    .header("User-Agent", c.userAgent)
                    .header("X-YouTube-Client-Name", c.id)
                    .header("X-YouTube-Client-Version", c.version)
                    .header("Origin", "https://www.youtube.com")
                    .post(RequestBody.create(JSON, payload));
            if (visitor != null) req.header("X-Goog-Visitor-Id", visitor);
            try (Response r = http.newCall(req.build()).execute()) {
                ResponseBody rb = r.body();
                String text = rb == null ? "" : readLimited(rb);
                if (!r.isSuccessful() || !text.startsWith("{")) {
                    last = new IOException("YouTube HTTP " + r.code());
                    continue;
                }
                return new JSONObject(text);
            } catch (JSONException e) {
                last = new IOException("YouTube: " + e.getMessage());
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("YouTube");
    }

    private static String readLimited(ResponseBody rb) throws IOException {
        long len = rb.contentLength();
        if (len > MAX_RESPONSE) throw new IOException("response too large");
        String s = rb.string();
        if (s.length() > MAX_RESPONSE) throw new IOException("response too large");
        return s;
    }

    // ------------------------------------------------------------------ player

    private static final Map<String, Video> cache = new LinkedHashMap<String, Video>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Video> e) {
            return size() > 6;
        }
    };
    private static final long CACHE_MS = 3 * 3600_000L;

    /** Stream list and details of a video. Cached for a few hours (stream URLs stay valid for about six). */
    public static Video player(OkHttpClient http, String id, String hl, String gl, boolean refresh) throws IOException {
        return player(http, id, hl, gl, refresh, null);
    }

    /** @param skip clients whose links already failed for this video (403 while playing), tried last */
    public static Video player(OkHttpClient http, String id, String hl, String gl, boolean refresh,
            java.util.Collection<String> skip) throws IOException {
        synchronized (cache) {
            Video v = cache.get(id);
            if (v != null && !refresh && System.currentTimeMillis() - v.fetched < CACHE_MS) return v;
        }
        Video best = null;
        IOException error = null;
        // Several app clients: which one YouTube accepts changes over time and per video. A client counts only if
        // its stream links really download (some get "OK" but links that answer 403 without a proof-of-origin token).
        List<Client> order = new ArrayList<>();
        for (Client c : new Client[] {VR, ANDROID, IOS}) if (skip == null || !skip.contains(c.name)) order.add(c);
        for (Client c : new Client[] {VR, ANDROID, IOS}) if (skip != null && skip.contains(c.name)) order.add(c);
        for (Client c : order) {
            try {
                JSONObject body = new JSONObject();
                body.put("videoId", id);
                body.put("contentCheckOk", true);
                body.put("racyCheckOk", true);
                Video v = parsePlayer(call(http, c, "player", body, hl, gl), id);
                v.client = c.name;
                v.userAgent = c.userAgent;
                if ((!v.streams.isEmpty() || v.hls != null) && reachable(http, v, c)) {
                    best = v;
                    break;
                }
                if (best == null || (best.error != null && v.error == null)) best = v;
                if (best == v && v.error == null) v.error = "stream links refused (HTTP 403)";
            } catch (IOException e) {
                error = e;
            } catch (JSONException e) {
                error = new IOException(e.toString());
            }
        }
        if ((best == null || best.error != null) && fallback != null) {
            try {
                Video v = fallback.player(http, id);
                if (v != null && v.error == null && !v.streams.isEmpty()) best = v;
            } catch (IOException e) {
                if (best == null) error = e;
            }
        }
        if (best == null) throw error != null ? error : new IOException("YouTube");
        best.fetched = System.currentTimeMillis();
        if (best.error == null) {
            synchronized (cache) {
                cache.put(id, best);
            }
        }
        return best;
    }

    /**
     * Downloads the first bytes of one stream of each kind (with sound, picture only, sound only) and drops the kinds
     * the video servers refuse: some clients get "OK" plus links that answer 403 (they need a proof-of-origin token),
     * sometimes only for some kinds. False when nothing playable is left.
     */
    private static boolean reachable(OkHttpClient http, Video v, Client c) {
        Stream muxed = null, picture = null, sound = null;
        for (Stream s : v.streams) {
            if (s.video && s.audio) {
                if (muxed == null || s.itag == 18) muxed = s;
            } else if (s.video) {
                if (picture == null || s.itag == 134 || (s.itag == 133 && picture.itag != 134)) picture = s;
            } else if (s.audio) {
                if (sound == null || s.itag == 140) sound = s;
            }
        }
        OkHttpClient quick = http.newBuilder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build();
        boolean muxedOk = muxed == null || fetchable(quick, muxed, c);
        boolean pictureOk = picture == null || fetchable(quick, picture, c);
        boolean soundOk = sound == null || fetchable(quick, sound, c);
        for (Iterator<Stream> it = v.streams.iterator(); it.hasNext(); ) {
            Stream s = it.next();
            boolean ok = s.video && s.audio ? muxedOk : s.video ? pictureOk : soundOk;
            if (!ok) it.remove();
        }
        return !v.streams.isEmpty() || v.hls != null;
    }

    private static boolean fetchable(OkHttpClient http, Stream s, Client c) {
        Request req = new Request.Builder().url(s.url).header("Range", "bytes=0-1023")
                .header("User-Agent", c.userAgent).header("Accept-Encoding", "identity").build();
        try (Response r = http.newCall(req).execute()) {
            return r.code() == 200 || r.code() == 206;
        } catch (IOException e) {
            return true; // network hiccup, not a refusal: let the player try
        }
    }

    /** Optional last resort: an Invidious instance chosen by the user fetches the streams from its own network. */
    public interface Fallback {
        Video player(OkHttpClient http, String id) throws IOException;
    }

    private static volatile Fallback fallback;

    public static void setFallback(Fallback f) {
        fallback = f;
    }

    /** Streams from an Invidious instance's API ({@code /api/v1/videos/ID?local=true}, proxied through it). */
    public static Video invidious(OkHttpClient http, String base, String id) throws IOException {
        String root = base.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (!root.startsWith("http")) root = "https://" + root;
        Request req = new Request.Builder().url(root + "/api/v1/videos/" + id + "?local=true").build();
        try (Response r = http.newCall(req).execute()) {
            ResponseBody rb = r.body();
            String text = rb == null ? "" : readLimited(rb);
            if (!r.isSuccessful() || !text.startsWith("{")) throw new IOException("Invidious HTTP " + r.code());
            JSONObject o = new JSONObject(text);
            Video v = new Video();
            v.id = id;
            v.client = "invidious";
            v.title = o.optString("title", "");
            v.author = o.optString("author", "");
            v.channelId = o.optString("authorId", "");
            v.description = o.optString("description", "");
            v.lengthSec = o.optInt("lengthSeconds");
            v.views = o.optLong("viewCount", -1);
            for (String key : new String[] {"formatStreams", "adaptiveFormats"}) {
                JSONArray a = o.optJSONArray(key);
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject f = a.optJSONObject(i);
                    if (f == null || f.optString("url", "").isEmpty()) continue;
                    Stream s = new Stream();
                    s.itag = parseInt(f.optString("itag", "0"));
                    String u = f.optString("url");
                    s.url = u.startsWith("/") ? root + u : u;
                    parseMime(f.optString("type", ""), s);
                    String res = f.optString("resolution", f.optString("qualityLabel", ""));
                    s.height = parseInt(res.replaceAll("[^0-9].*$", ""));
                    s.quality = res;
                    s.bitrate = parseInt(f.optString("bitrate", "0"));
                    fixItag18(s);
                    v.streams.add(s);
                }
            }
            if (v.streams.isEmpty()) v.error = o.optString("error", "no streams");
            return v;
        } catch (JSONException e) {
            throw new IOException("Invidious: " + e.getMessage());
        }
    }

    private static void fixItag18(Stream s) {
        if (s.itag != 18) return;
        // itag 18 says "avc1.42001E" (Baseline) but is encoded in Main profile (avcC profile_idc 77), which
        // Baseline-only decoders such as KitKat's software one reject. Say what it really is.
        for (int k = 0; k < s.codecs.size(); k++) {
            if (s.codecs.get(k).toLowerCase(Locale.US).startsWith("avc1.42")) s.codecs.set(k, "avc1.4D401E");
        }
    }

    static Video parsePlayer(JSONObject o, String id) {
        Video v = new Video();
        v.id = id;
        JSONObject ps = o.optJSONObject("playabilityStatus");
        String status = ps == null ? "ERROR" : ps.optString("status", "ERROR");
        JSONObject d = o.optJSONObject("videoDetails");
        if (d != null) {
            v.title = d.optString("title", "");
            v.author = d.optString("author", "");
            v.channelId = d.optString("channelId", "");
            v.description = d.optString("shortDescription", "");
            v.lengthSec = parseInt(d.optString("lengthSeconds", "0"));
            String views = d.optString("viewCount", "");
            if (!views.isEmpty()) v.views = parseLong(views);
            v.live = d.optBoolean("isLive", false) || d.optBoolean("isLiveContent", false) && v.lengthSec == 0;
        }
        if (!"OK".equals(status)) {
            String reason = ps == null ? "" : ps.optString("reason", "");
            if (reason.isEmpty() && ps != null) {
                JSONObject err = ps.optJSONObject("errorScreen");
                reason = err == null ? "" : firstText(err);
            }
            v.error = reason.isEmpty() ? status : reason;
            return v;
        }
        JSONObject sd = o.optJSONObject("streamingData");
        if (sd == null) {
            v.error = "no streams";
            return v;
        }
        addStreams(v, sd.optJSONArray("formats"));
        addStreams(v, sd.optJSONArray("adaptiveFormats"));
        String hls = sd.optString("hlsManifestUrl", "");
        if (!hls.isEmpty()) v.hls = hls;
        return v;
    }

    private static void addStreams(Video v, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", "");
            if (url.isEmpty()) continue; // ciphered: would need YouTube's JavaScript
            Stream s = new Stream();
            s.itag = f.optInt("itag");
            s.url = url;
            parseMime(f.optString("mimeType", ""), s);
            fixItag18(s);
            s.width = f.optInt("width");
            s.height = f.optInt("height");
            s.bitrate = f.optInt("bitrate");
            s.length = parseLong(f.optString("contentLength", "0"));
            s.quality = f.optString("qualityLabel", "");
            v.streams.add(s);
        }
    }

    /** {@code video/mp4; codecs="avc1.42001E, mp4a.40.2"} */
    public static void parseMime(String mimeType, Stream s) {
        int semi = mimeType.indexOf(';');
        s.mime = (semi >= 0 ? mimeType.substring(0, semi) : mimeType).trim().toLowerCase(Locale.US);
        int c = mimeType.indexOf("codecs=");
        if (c >= 0) {
            String list = mimeType.substring(c + 7).replace("\"", "").replace("'", "");
            for (String codec : list.split(",")) {
                String t = codec.trim();
                if (!t.isEmpty()) s.codecs.add(t);
            }
        }
        for (String codec : s.codecs) {
            if (StreamPicker.isVideoCodec(codec)) s.video = true;
            else s.audio = true;
        }
        if (s.codecs.isEmpty()) {
            s.video = s.mime.startsWith("video/");
            s.audio = !s.video;
        }
    }

    // ------------------------------------------------------------------ lists

    public static Feed search(OkHttpClient http, String query, String continuation, String hl, String gl) throws IOException {
        JSONObject body = new JSONObject();
        try {
            if (continuation != null) body.put("continuation", continuation);
            else body.put("query", query);
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
        return feed(call(http, MWEB, "search", body, hl, gl));
    }

    /** Related videos plus the localized view/date line of a video. */
    public static Feed next(OkHttpClient http, String id, String hl, String gl) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("videoId", id);
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
        JSONObject o = call(http, MWEB, "next", body, hl, gl);
        Feed f = feed(o);
        List<JSONObject> info = new ArrayList<>();
        collect(o, "slimVideoInformationRenderer", info, 1);
        if (!info.isEmpty()) {
            f.title = text(info.get(0).optJSONObject("title"));
            f.subtitle = text(info.get(0).optJSONObject("expandedSubtitle"));
            if (f.subtitle.isEmpty()) f.subtitle = text(info.get(0).optJSONObject("collapsedSubtitle"));
        }
        // The first items of "next" are the video itself on some layouts.
        for (Iterator<Item> it = f.items.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(id)) it.remove();
        }
        f.continuation = null;
        return f;
    }

    public static Feed browse(OkHttpClient http, String browseId, String params, String continuation, String hl, String gl)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            if (continuation != null) {
                body.put("continuation", continuation);
            } else {
                body.put("browseId", browseId);
                if (params != null) body.put("params", params);
            }
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
        JSONObject o = call(http, MWEB, "browse", body, hl, gl);
        Feed f = feed(o);
        JSONObject meta = o.optJSONObject("metadata");
        if (meta != null) {
            JSONObject cm = meta.optJSONObject("channelMetadataRenderer");
            if (cm == null) cm = meta.optJSONObject("playlistMetadataRenderer");
            if (cm != null) {
                f.title = cm.optString("title", "");
                f.description = cm.optString("description", "");
            }
        }
        if (f.title.isEmpty()) {
            List<JSONObject> h = new ArrayList<>();
            collect(o, "pageHeaderViewModel", h, 1);
            if (!h.isEmpty()) f.title = firstText(h.get(0).optJSONObject("title"));
        }
        return f;
    }

    public static final String CHANNEL_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA";

    /** Channel id behind a handle or vanity URL (youtube.com/@name, /c/name, /user/name), or null. */
    public static String resolveChannel(OkHttpClient http, String url, String hl, String gl) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("url", url);
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
        JSONObject o = call(http, MWEB, "navigation/resolve_url", body, hl, gl);
        JSONObject ep = o.optJSONObject("endpoint");
        JSONObject be = ep == null ? null : ep.optJSONObject("browseEndpoint");
        String id = be == null ? "" : be.optString("browseId", "");
        return id.isEmpty() ? null : id;
    }

    // ------------------------------------------------------------------ parsing

    private static final Set<String> SKIP = new HashSet<>(java.util.Arrays.asList(
            "engagementPanels", "frameworkUpdates", "topbar", "menu", "trackingParams", "accessibility",
            "loggingDirectives", "responseContext", "microformat", "onResponseReceivedEndpoints", "overlay",
            "endScreen", "playerOverlays", "annotations", "cards"));

    static Feed feed(JSONObject root) {
        Feed f = new Feed();
        Set<String> seen = new HashSet<>();
        walk(root, f, seen, 0);
        return f;
    }

    private static void walk(Object node, Feed f, Set<String> seen, int depth) {
        if (depth > 60) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), f, seen, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (SKIP.contains(k)) continue;
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                JSONObject r = (JSONObject) v;
                Item it = null;
                switch (k) {
                    case "videoWithContextRenderer":
                    case "compactVideoRenderer":
                    case "videoRenderer":
                    case "gridVideoRenderer":
                    case "playlistVideoRenderer":
                    case "playlistPanelVideoRenderer":
                        it = video(r);
                        break;
                    case "shortsLockupViewModel":
                        it = shorts(r);
                        break;
                    case "reelItemRenderer":
                        it = reel(r);
                        break;
                    case "lockupViewModel":
                        it = lockup(r);
                        break;
                    case "compactChannelRenderer":
                    case "channelRenderer":
                    case "gridChannelRenderer":
                        it = channel(r);
                        break;
                    case "compactPlaylistRenderer":
                    case "playlistRenderer":
                    case "gridPlaylistRenderer":
                    case "compactRadioRenderer":
                        it = playlist(r);
                        break;
                    case "continuationItemRenderer":
                        if (f.continuation == null) f.continuation = continuationToken(r);
                        continue;
                    case "endScreenVideoRenderer":
                        continue;
                    default:
                        break;
                }
                if (it != null) {
                    if (!it.id.isEmpty() && seen.add(it.type + ":" + it.id)) f.items.add(it);
                    continue;
                }
            }
            if (v instanceof JSONObject || v instanceof JSONArray) walk(v, f, seen, depth + 1);
        }
    }

    private static String continuationToken(JSONObject r) {
        JSONObject ep = r.optJSONObject("continuationEndpoint");
        if (ep == null) {
            JSONObject b = r.optJSONObject("button");
            if (b != null) {
                List<JSONObject> cmds = new ArrayList<>();
                collect(b, "continuationCommand", cmds, 1);
                if (!cmds.isEmpty()) return emptyToNull(cmds.get(0).optString("token", ""));
            }
            return null;
        }
        JSONObject cc = ep.optJSONObject("continuationCommand");
        return cc == null ? null : emptyToNull(cc.optString("token", ""));
    }

    private static Item video(JSONObject r) {
        Item it = new Item();
        it.type = VIDEO;
        it.id = r.optString("videoId", "");
        if (it.id.isEmpty()) {
            JSONObject we = path(r, "navigationEndpoint", "watchEndpoint");
            if (we != null) it.id = we.optString("videoId", "");
        }
        if (!validId(it.id)) return null;
        it.title = text(r.optJSONObject("headline"));
        if (it.title.isEmpty()) it.title = text(r.optJSONObject("title"));
        JSONObject by = r.optJSONObject("shortBylineText");
        if (by == null) by = r.optJSONObject("longBylineText");
        if (by == null) by = r.optJSONObject("ownerText");
        it.channel = text(by);
        it.channelId = browseIdOf(by);
        it.duration = text(r.optJSONObject("lengthText"));
        String views = text(r.optJSONObject("shortViewCountText"));
        if (views.isEmpty()) views = text(r.optJSONObject("viewCountText"));
        String age = text(r.optJSONObject("publishedTimeText"));
        it.meta = join(views, age);
        JSONArray overlays = r.optJSONArray("thumbnailOverlays");
        if (overlays != null) {
            for (int i = 0; i < overlays.length(); i++) {
                JSONObject ts = path(overlays.optJSONObject(i), "thumbnailOverlayTimeStatusRenderer");
                if (ts == null) continue;
                String style = ts.optString("style", "");
                if (style.equals("LIVE")) it.live = true;
                if (it.duration.isEmpty()) it.duration = text(ts.optJSONObject("text"));
            }
        }
        it.thumb = thumb(it.id);
        return it;
    }

    private static Item shorts(JSONObject r) {
        Item it = new Item();
        it.type = SHORT;
        JSONObject rw = path(r, "onTap", "innertubeCommand", "reelWatchEndpoint");
        if (rw != null) it.id = rw.optString("videoId", "");
        if (!validId(it.id)) {
            String e = r.optString("entityId", "");
            int dash = e.lastIndexOf('-');
            it.id = e.length() >= 11 ? e.substring(e.length() - 11) : "";
            if (dash < 0 || !validId(it.id)) return null;
        }
        JSONObject om = r.optJSONObject("overlayMetadata");
        if (om != null) {
            it.title = text(om.optJSONObject("primaryText"));
            it.meta = text(om.optJSONObject("secondaryText"));
        }
        if (it.title.isEmpty()) it.title = r.optString("accessibilityText", "");
        it.duration = "Shorts";
        it.thumb = thumb(it.id);
        return it;
    }

    private static Item reel(JSONObject r) {
        Item it = new Item();
        it.type = SHORT;
        it.id = r.optString("videoId", "");
        if (!validId(it.id)) return null;
        it.title = text(r.optJSONObject("headline"));
        it.meta = text(r.optJSONObject("viewCountText"));
        it.duration = "Shorts";
        it.thumb = thumb(it.id);
        return it;
    }

    private static Item lockup(JSONObject r) {
        String type = r.optString("contentType", "");
        String id = r.optString("contentId", "");
        Item it = new Item();
        if (type.endsWith("VIDEO")) {
            it.type = VIDEO;
            if (!validId(id)) return null;
            it.thumb = thumb(id);
        } else if (type.endsWith("PLAYLIST") || type.endsWith("ALBUM") || type.endsWith("PODCAST")) {
            it.type = PLAYLIST;
            if (id.isEmpty()) return null;
        } else if (type.endsWith("CHANNEL")) {
            it.type = CHANNEL;
            if (id.isEmpty()) return null;
        } else {
            return null;
        }
        it.id = id;
        JSONObject md = path(r, "metadata", "lockupMetadataViewModel");
        if (md != null) {
            it.title = text(md.optJSONObject("title"));
            JSONArray rows = path(md, "metadata", "contentMetadataViewModel") == null ? null
                    : path(md, "metadata", "contentMetadataViewModel").optJSONArray("metadataRows");
            if (rows != null) {
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONArray parts = rows.optJSONObject(i) == null ? null : rows.optJSONObject(i).optJSONArray("metadataParts");
                    if (parts == null) continue;
                    List<String> texts = new ArrayList<>();
                    for (int j = 0; j < parts.length(); j++) {
                        String t = text(parts.optJSONObject(j) == null ? null : parts.optJSONObject(j).optJSONObject("text"));
                        if (!t.isEmpty()) texts.add(t);
                    }
                    if (!texts.isEmpty()) lines.add(joinAll(texts));
                }
                if (!lines.isEmpty()) it.channel = lines.get(0);
                if (lines.size() > 1) it.meta = lines.get(1);
            }
        }
        List<JSONObject> badges = new ArrayList<>();
        collect(r.optJSONObject("contentImage"), "thumbnailBadgeViewModel", badges, 2);
        for (JSONObject b : badges) {
            String t = b.optString("text", "");
            if (!t.isEmpty()) {
                it.duration = t;
                break;
            }
        }
        if (it.thumb.isEmpty()) {
            List<JSONObject> images = new ArrayList<>();
            collect(r.optJSONObject("contentImage"), "image", images, 1);
            if (!images.isEmpty()) it.thumb = firstSource(images.get(0));
        }
        return it.title.isEmpty() ? null : it;
    }

    private static Item channel(JSONObject r) {
        Item it = new Item();
        it.type = CHANNEL;
        it.id = r.optString("channelId", "");
        if (it.id.isEmpty()) return null;
        it.title = text(r.optJSONObject("title"));
        if (it.title.isEmpty()) it.title = text(r.optJSONObject("displayName"));
        it.meta = join(text(r.optJSONObject("subscriberCountText")), text(r.optJSONObject("videoCountText")));
        JSONObject th = r.optJSONObject("thumbnail");
        it.thumb = th == null ? "" : firstThumb(th);
        return it;
    }

    private static Item playlist(JSONObject r) {
        Item it = new Item();
        it.type = PLAYLIST;
        it.id = r.optString("playlistId", "");
        if (it.id.isEmpty()) return null;
        it.title = text(r.optJSONObject("title"));
        JSONObject by = r.optJSONObject("shortBylineText");
        if (by == null) by = r.optJSONObject("longBylineText");
        it.channel = text(by);
        String count = text(r.optJSONObject("videoCountShortText"));
        if (count.isEmpty()) count = text(r.optJSONObject("videoCountText"));
        it.meta = count;
        String vid = r.optString("videoId", "");
        JSONObject th = r.optJSONObject("thumbnail");
        it.thumb = validId(vid) ? thumb(vid) : th == null ? "" : firstThumb(th);
        return it;
    }

    // ------------------------------------------------------------------ JSON helpers

    /** Small JPEG thumbnail (320x180): decodes everywhere, unlike the WebP variants. */
    public static String thumb(String id) {
        return "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
    }

    static String text(JSONObject o) {
        if (o == null) return "";
        String simple = o.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        String content = o.optString("content", "");
        if (!content.isEmpty()) return content;
        JSONArray runs = o.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
        }
        return sb.toString().trim();
    }

    private static String firstText(JSONObject o) {
        if (o == null) return "";
        String t = text(o);
        if (!t.isEmpty()) return t;
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            Object v = o.opt(keys.next());
            if (v instanceof JSONObject) {
                t = firstText((JSONObject) v);
                if (!t.isEmpty()) return t;
            }
        }
        return "";
    }

    private static String browseIdOf(JSONObject runsHolder) {
        if (runsHolder == null) return "";
        JSONArray runs = runsHolder.optJSONArray("runs");
        if (runs == null) return "";
        for (int i = 0; i < runs.length(); i++) {
            JSONObject be = path(runs.optJSONObject(i), "navigationEndpoint", "browseEndpoint");
            if (be != null) return be.optString("browseId", "");
        }
        return "";
    }

    private static String firstThumb(JSONObject th) {
        JSONArray a = th.optJSONArray("thumbnails");
        if (a == null || a.length() == 0) return "";
        JSONObject t = a.optJSONObject(Math.min(1, a.length() - 1));
        String u = t == null ? "" : t.optString("url", "");
        return u.startsWith("//") ? "https:" + u : u;
    }

    private static String firstSource(JSONObject image) {
        JSONArray a = image.optJSONArray("sources");
        if (a == null || a.length() == 0) return "";
        JSONObject t = a.optJSONObject(Math.min(1, a.length() - 1));
        String u = t == null ? "" : t.optString("url", "");
        return u.startsWith("//") ? "https:" + u : u;
    }

    static JSONObject path(JSONObject o, String... keys) {
        JSONObject cur = o;
        for (String k : keys) {
            if (cur == null) return null;
            cur = cur.optJSONObject(k);
        }
        return cur;
    }

    /** Collects objects stored under {@code key} anywhere below {@code node}, up to {@code max}. */
    static void collect(Object node, String key, List<JSONObject> out, int max) {
        if (out.size() >= max || node == null) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < max; i++) collect(a.opt(i), key, out, max);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> keys = o.keys();
            while (keys.hasNext() && out.size() < max) {
                String k = keys.next();
                Object v = o.opt(k);
                if (k.equals(key) && v instanceof JSONObject) out.add((JSONObject) v);
                else if (!SKIP.contains(k)) collect(v, key, out, max);
            }
        }
    }

    private static String joinAll(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " · " + b;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
