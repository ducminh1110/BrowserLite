package com.browserlite.web;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import com.browserlite.Config;
import com.browserlite.Profile;
import com.browserlite.R;
import com.browserlite.data.Db;
import com.browserlite.net.Embeds;
import com.browserlite.net.NetEngine;
import com.browserlite.net.UrlUtil;
import com.browserlite.net.YouTube;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Video mode: youtube.com URLs are answered with small server-side-rendered pages (search, watch, channel, playlist)
 * built from YouTube's JSON API. No JavaScript is needed, lists are plain links, and "Play" opens the native player.
 */
public final class YouTubePages {
    public static final String PLAY = "https://" + Interceptor.RES_HOST + "/play";
    public static final String DOWNLOAD = "https://" + Interceptor.RES_HOST + "/ytdl";

    private final Context app;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<String, Object[]> cache = new LinkedHashMap<String, Object[]>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) {
            return size() > 6;
        }
    };
    private static final long CACHE_MS = 10 * 60_000L;

    YouTubePages(Context app) {
        this.app = app.getApplicationContext();
    }

    private Resources res() {
        return app.getResources();
    }

    private String s(int id, Object... args) {
        return UrlUtil.htmlEscape(args.length == 0 ? res().getString(id) : res().getString(id, args));
    }

    private static String hl() {
        String l = Locale.getDefault().getLanguage();
        return l.isEmpty() ? "en" : l;
    }

    private static String gl() {
        String c = Locale.getDefault().getCountry();
        return c.isEmpty() ? "US" : c;
    }

    /** Runs on a worker thread: talks to the network. */
    public String render(String url, Config cfg) {
        Profile pr = cfg.profileFor("www.youtube.com");
        boolean images = pr.imageMode != Config.IMAGES_OFF;
        String key = UrlUtil.stripFragment(url) + "|" + images + "|" + cfg.videoSound;
        String id = YouTube.videoId(url);
        boolean cacheable = id != null || url.contains("bl_ct=") || url.contains("/results");
        if (cacheable) {
            synchronized (cache) {
                Object[] c = cache.get(key);
                if (c != null && System.currentTimeMillis() - (Long) c[1] < CACHE_MS) return (String) c[0];
            }
        }
        String html;
        try {
            html = route(url, id, images);
        } catch (IOException e) {
            return page(s(R.string.yt_title), "", "<p class=\"err\">" + s(R.string.yt_error, String.valueOf(e.getMessage()))
                    + "</p><p class=\"pad\"><a class=\"btn pri\" href=\"" + UrlUtil.htmlEscape(url) + "\">"
                    + s(R.string.err_retry) + "</a></p>", images);
        }
        if (cacheable) {
            synchronized (cache) {
                cache.put(key, new Object[] {html, System.currentTimeMillis()});
            }
        }
        return html;
    }

    private String route(String url, String id, boolean images) throws IOException {
        if (id != null) return watch(id, images);
        String path = Embeds.pathOf(url);
        String ct = Embeds.param(url, "bl_ct");
        if (path.startsWith("/results") || path.startsWith("/search")) {
            String q = Embeds.param(url, "search_query");
            if (q == null) q = Embeds.param(url, "q");
            if (q == null || q.trim().isEmpty()) return home(images);
            return results(q.trim(), ct, images);
        }
        String first = Embeds.segment(path, 1);
        if (first.equals("channel") && !Embeds.segment(path, 2).isEmpty()) {
            return channel(Embeds.segment(path, 2), ct, images);
        }
        if (first.startsWith("@") || first.equals("c") || first.equals("user")) {
            String channelId = YouTube.resolveChannel(NetEngine.youtube(app),
                    "https://www.youtube.com" + (first.startsWith("@") ? "/" + first : path), hl(), gl());
            if (channelId != null) return channel(channelId, ct, images);
        }
        if (path.startsWith("/playlist")) {
            String list = Embeds.param(url, "list");
            if (list != null && !list.isEmpty()) return playlist(list, ct, images);
        }
        return home(images);
    }

    // ------------------------------------------------------------------ pages

    private String home(boolean images) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<h2>").append(s(R.string.yt_topics)).append("</h2><p class=\"chips\">");
        for (String t : res().getStringArray(R.array.yt_topics)) {
            sb.append("<a href=\"/results?search_query=").append(enc(t)).append("\">").append(UrlUtil.htmlEscape(t)).append("</a>");
        }
        sb.append("</p>");
        List<Db.Entry> history = Db.get(app).history(300, 0);
        SharedPreferences pos = app.getSharedPreferences("video_positions", Context.MODE_PRIVATE);
        StringBuilder recent = new StringBuilder();
        int n = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Db.Entry e : history) {
            String vid = YouTube.videoId(e.url);
            if (vid == null || !seen.add(vid)) continue;
            YouTube.Item it = new YouTube.Item();
            it.id = vid;
            it.title = e.title == null || e.title.equals(e.url) ? vid : e.title;
            long p = pos.getLong("yt:" + vid, 0);
            if (p > 0) it.meta = res().getString(R.string.yt_resume_at, format(p));
            it.thumb = YouTube.thumb(vid);
            item(recent, it, images);
            if (++n >= 12) break;
        }
        if (n > 0) sb.append("<h2>").append(s(R.string.yt_recent)).append("</h2>").append(recent);
        sb.append("<p class=\"note\">").append(s(R.string.yt_note)).append("</p>");
        return page(s(R.string.yt_title), "", sb.toString(), images);
    }

    private String results(String q, String ct, boolean images) throws IOException {
        YouTube.Feed f = YouTube.search(NetEngine.youtube(app), q, ct, hl(), gl());
        StringBuilder sb = new StringBuilder(16384);
        list(sb, f.items, images);
        if (f.items.isEmpty()) sb.append("<p class=\"pad\">").append(s(R.string.yt_empty)).append("</p>");
        if (f.continuation != null) {
            sb.append("<a class=\"more\" href=\"/results?search_query=").append(enc(q)).append("&amp;bl_ct=")
                    .append(enc(f.continuation)).append("\">").append(s(R.string.yt_more)).append("</a>");
        }
        return page(UrlUtil.htmlEscape(q) + " - YouTube", UrlUtil.htmlEscape(q), sb.toString(), images);
    }

    private String watch(final String id, boolean images) throws IOException {
        boolean sound = com.browserlite.Config.get().videoSound;
        Future<YouTube.Video> player = pool.submit(new Callable<YouTube.Video>() {
            @Override
            public YouTube.Video call() throws IOException {
                return YouTube.player(NetEngine.youtube(app), id, hl(), gl(), false);
            }
        });
        YouTube.Feed next;
        try {
            next = YouTube.next(NetEngine.youtube(app), id, hl(), gl());
        } catch (IOException e) {
            next = new YouTube.Feed(); // related list is optional
        }
        YouTube.Video v = null;
        String playerError = null;
        try {
            v = player.get();
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            playerError = String.valueOf(c.getMessage());
        }
        String title = v != null && !v.title.isEmpty() ? v.title : next.title.isEmpty() ? id : next.title;
        StringBuilder sb = new StringBuilder(24576);
        String play = PLAY + "?v=" + id + "&amp;t=" + enc(title);
        sb.append("<a class=\"player\" href=\"").append(play).append("\">");
        if (images) {
            sb.append("<img src=\"https://i.ytimg.com/vi/").append(id).append("/mqdefault.jpg\" alt=\"\">");
        } else {
            sb.append("<span class=\"noimg\"></span>");
        }
        sb.append("<span class=\"big\">&#9654;</span></a>");
        sb.append("<h1>").append(UrlUtil.htmlEscape(title)).append("</h1>");
        String sub = next.subtitle;
        if (sub.isEmpty() && v != null && v.views >= 0) sub = res().getString(R.string.yt_views, String.format(Locale.getDefault(), "%,d", v.views));
        if (v != null && v.lengthSec > 0) sub = sub.isEmpty() ? format(v.lengthSec * 1000L) : format(v.lengthSec * 1000L) + " · " + sub;
        if (v != null && v.live) sub = res().getString(R.string.player_live) + (sub.isEmpty() ? "" : " · " + sub);
        if (!sub.isEmpty()) sb.append("<p class=\"sub\">").append(UrlUtil.htmlEscape(sub)).append("</p>");
        if (v != null && !v.author.isEmpty()) {
            sb.append("<p class=\"sub\"><a class=\"ch\" href=\"/channel/").append(UrlUtil.htmlEscape(v.channelId)).append("\">")
                    .append(UrlUtil.htmlEscape(v.author)).append("</a></p>");
        }
        if (v != null && v.error != null) {
            String why = YouTube.isBotCheck(v.error) || v.error.contains("403") ? res().getString(R.string.yt_bot_check) : v.error;
            sb.append("<p class=\"err\">").append(s(R.string.yt_unplayable, why)).append("</p>");
        } else if (v == null && playerError != null) {
            sb.append("<p class=\"err\">").append(s(R.string.yt_error, playerError)).append("</p>");
        }
        sb.append("<p class=\"actions\"><a class=\"btn pri\" href=\"").append(play).append("\">&#9654; ")
                .append(s(R.string.yt_play)).append("</a>");
        if (sound) {
            sb.append("<a class=\"btn\" href=\"").append(play).append("&amp;a=1\">&#9835; ").append(s(R.string.yt_audio)).append("</a>");
        }
        sb.append("<a class=\"btn\" href=\"").append(DOWNLOAD).append("?v=").append(id)
                .append("&amp;t=").append(enc(title)).append("\">").append(s(R.string.yt_download)).append("</a></p>");
        if (v != null && !v.description.trim().isEmpty()) {
            sb.append("<details><summary>").append(s(R.string.yt_description)).append("</summary><div class=\"desc\">")
                    .append(linkify(v.description)).append("</div></details>");
        }
        if (!next.items.isEmpty()) {
            sb.append("<h2>").append(s(R.string.yt_related)).append("</h2>");
            list(sb, next.items, images);
        }
        return page(UrlUtil.htmlEscape(title), "", sb.toString(), images);
    }

    private String channel(String channelId, String ct, boolean images) throws IOException {
        YouTube.Feed f = YouTube.browse(NetEngine.youtube(app), channelId, ct == null ? YouTube.CHANNEL_VIDEOS : null, ct,
                hl(), gl());
        StringBuilder sb = new StringBuilder(16384);
        if (!f.title.isEmpty() && ct == null) sb.append("<h1>").append(UrlUtil.htmlEscape(f.title)).append("</h1>");
        if (!f.description.trim().isEmpty() && ct == null) {
            sb.append("<details><summary>").append(s(R.string.yt_about)).append("</summary><div class=\"desc\">")
                    .append(linkify(f.description)).append("</div></details>");
        }
        list(sb, f.items, images);
        if (f.continuation != null) {
            sb.append("<a class=\"more\" href=\"/channel/").append(UrlUtil.htmlEscape(channelId)).append("?bl_ct=")
                    .append(enc(f.continuation)).append("\">").append(s(R.string.yt_more)).append("</a>");
        }
        return page(f.title.isEmpty() ? "YouTube" : UrlUtil.htmlEscape(f.title), "", sb.toString(), images);
    }

    private String playlist(String list, String ct, boolean images) throws IOException {
        String browseId = list.startsWith("VL") ? list : "VL" + list;
        YouTube.Feed f = YouTube.browse(NetEngine.youtube(app), browseId, null, ct, hl(), gl());
        StringBuilder sb = new StringBuilder(16384);
        if (!f.title.isEmpty() && ct == null) sb.append("<h1>").append(UrlUtil.htmlEscape(f.title)).append("</h1>");
        list(sb, f.items, images);
        if (f.continuation != null) {
            sb.append("<a class=\"more\" href=\"/playlist?list=").append(enc(list)).append("&amp;bl_ct=")
                    .append(enc(f.continuation)).append("\">").append(s(R.string.yt_more)).append("</a>");
        }
        return page(f.title.isEmpty() ? "YouTube" : UrlUtil.htmlEscape(f.title), "", sb.toString(), images);
    }

    // ------------------------------------------------------------------ pieces

    private void list(StringBuilder sb, List<YouTube.Item> items, boolean images) {
        for (YouTube.Item it : items) item(sb, it, images);
    }

    private void item(StringBuilder sb, YouTube.Item it, boolean images) {
        String href;
        switch (it.type) {
            case YouTube.CHANNEL: href = "/channel/" + UrlUtil.htmlEscape(it.id); break;
            case YouTube.PLAYLIST: href = "/playlist?list=" + enc(it.id); break;
            case YouTube.SHORT: href = "/shorts/" + it.id; break;
            default: href = "/watch?v=" + it.id; break;
        }
        sb.append("<a class=\"item").append(it.type == YouTube.CHANNEL ? " chan" : "").append("\" href=\"").append(href).append("\">");
        if (images) {
            sb.append("<span class=\"th\">");
            if (!it.thumb.isEmpty()) sb.append("<img src=\"").append(UrlUtil.htmlEscape(it.thumb)).append("\" alt=\"\">");
            String badge = it.live ? res().getString(R.string.player_live) : it.duration;
            if (it.type == YouTube.PLAYLIST && badge.isEmpty()) badge = "≡";
            if (!badge.isEmpty()) sb.append("<b class=\"dur\">").append(UrlUtil.htmlEscape(badge)).append("</b>");
            sb.append("</span>");
        }
        sb.append("<span class=\"tx\"><span class=\"t\">").append(UrlUtil.htmlEscape(it.title)).append("</span>");
        if (!images && !it.duration.isEmpty()) sb.append("<span class=\"m\">").append(UrlUtil.htmlEscape(it.duration)).append("</span>");
        if (!it.channel.isEmpty()) sb.append("<span class=\"m\">").append(UrlUtil.htmlEscape(it.channel)).append("</span>");
        if (!it.meta.isEmpty()) sb.append("<span class=\"m\">").append(UrlUtil.htmlEscape(it.meta)).append("</span>");
        sb.append("</span></a>");
    }

    private static final String CSS = "<style>"
            + "html{-webkit-text-size-adjust:100%}"
            + "body{margin:0;font:16px/1.35 sans-serif;color:#000;background:#fff}"
            + "a{color:#000}"
            + ".top{display:-webkit-box;display:flex;-webkit-box-align:center;align-items:center;padding:6px;"
            + "border-bottom:2px solid #000}"
            + ".logo{font-weight:bold;font-size:18px;text-decoration:none;margin-right:8px;white-space:nowrap}"
            + ".top form{display:-webkit-box;display:flex;-webkit-box-flex:1;flex:1;min-width:0;margin:0}"
            + ".top input{-webkit-box-flex:1;flex:1;min-width:0;width:50px;font-size:17px;padding:8px;border:2px solid #000;"
            + "border-radius:0;-webkit-appearance:none;background:#fff;color:#000}"
            + ".top button{font-size:16px;padding:8px 12px;border:2px solid #000;border-left:0;background:#000;color:#fff;"
            + "border-radius:0;-webkit-appearance:none}"
            + ".item{display:-webkit-box;display:flex;padding:8px;border-bottom:1px solid #000;text-decoration:none}"
            + ".th{position:relative;display:block;width:160px;height:90px;-webkit-box-flex:0;flex:none;"
            + "background:#ccc;margin-right:10px;overflow:hidden}"
            + ".chan .th{width:90px;margin:0 45px 0 25px}"
            + ".th img{width:100%;height:100%;display:block}"
            + ".dur{position:absolute;right:2px;bottom:2px;background:#000;color:#fff;font-size:12px;padding:1px 4px}"
            + ".tx{display:block;-webkit-box-flex:1;flex:1;min-width:0}"
            + ".t{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;font-weight:bold}"
            + ".m{display:block;font-size:13px;color:#333;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}"
            + "h1{font-size:20px;margin:10px 10px 4px}"
            + "h2{font-size:14px;letter-spacing:1px;text-transform:uppercase;border-bottom:2px solid #000;"
            + "margin:16px 0 0;padding:0 10px 4px}"
            + ".sub{margin:2px 10px;font-size:14px;color:#333}"
            + ".ch{font-weight:bold;color:#000}"
            + ".player{display:block;position:relative;background:#000;text-decoration:none;min-height:120px}"
            + ".player img{width:100%;display:block}"
            + ".noimg{display:block;height:180px}"
            + ".big{position:absolute;left:50%;top:50%;width:70px;height:70px;margin:-35px 0 0 -35px;background:#fff;"
            + "border:3px solid #000;border-radius:35px;font-size:34px;line-height:70px;text-align:center;color:#000;"
            + "box-sizing:border-box;padding-left:6px}"
            + ".actions{padding:4px 10px;margin:6px 0}"
            + ".btn{display:inline-block;padding:10px 14px;margin:4px 6px 4px 0;border:2px solid #000;text-decoration:none;"
            + "font-weight:bold;background:#fff}"
            + ".btn.pri{background:#000;color:#fff}"
            + "details{margin:6px 10px;border:1px solid #000;padding:6px 8px}"
            + "summary{font-weight:bold}"
            + ".desc{white-space:pre-wrap;word-wrap:break-word;font-size:14px;margin-top:6px}"
            + ".more{display:block;text-align:center;padding:14px;font-weight:bold;border-bottom:1px solid #000;text-decoration:none}"
            + ".chips{padding:4px 6px;margin:0}"
            + ".chips a{display:inline-block;margin:4px;padding:8px 12px;border:2px solid #000;text-decoration:none}"
            + ".note{font-size:13px;color:#333;padding:10px;margin:10px 0 30px}"
            + ".err{padding:0 10px;font-weight:bold}"
            + ".pad{padding:0 10px}"
            + "</style>";

    private String page(String title, String query, String body, boolean images) {
        StringBuilder sb = new StringBuilder(body.length() + 3072);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(title).append("</title>").append(CSS)
                // Our own page needs none of the injected helpers: tell the browser not to add them later.
                .append("<script>window.__bl=window.__bl||{v:0,page:function(){return 0},lowMemory:function(){}};</script>")
                .append("</head><body><div class=\"top\"><a class=\"logo\" href=\"/\">&#9654; YouTube</a>")
                .append("<form action=\"/results\" method=\"get\"><input type=\"search\" name=\"search_query\" value=\"")
                .append(query).append("\" placeholder=\"").append(s(R.string.yt_search_hint))
                .append("\" autocomplete=\"off\"><button type=\"submit\">").append(s(R.string.yt_search))
                .append("</button></form></div>").append(body).append("</body></html>");
        return sb.toString();
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");

    private static String linkify(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 256);
        Matcher m = URL.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(UrlUtil.htmlEscape(text.substring(last, m.start())));
            String u = m.group();
            sb.append("<a href=\"").append(UrlUtil.htmlEscape(u)).append("\">").append(UrlUtil.htmlEscape(u)).append("</a>");
            last = m.end();
        }
        sb.append(UrlUtil.htmlEscape(text.substring(last)));
        return sb.toString();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    static String format(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }
}
