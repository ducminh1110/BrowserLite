package com.browserlite.net;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * Recognises heavyweight third-party embeds (video players, social widgets, maps) loaded in iframes.
 * Each one pulls megabytes of JavaScript and a whole extra document into RAM; we replace them with a
 * link that opens the real thing full-page instead.
 */
public final class Embeds {
    private Embeds() {}

    public static final class Match {
        public final String label;
        public final String target;

        Match(String label, String target) {
            this.label = label;
            this.target = target;
        }
    }

    public static Match match(String url) {
        String host = UrlUtil.host(url);
        if (host.isEmpty()) return null;
        String lower = url.toLowerCase(Locale.US);
        String path = pathOf(url);
        if ((host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) && path.startsWith("/embed/")) {
            String id = segment(path, 2);
            if (id.isEmpty() || id.equals("videoseries")) return new Match("YouTube", url);
            return new Match("YouTube", "https://m.youtube.com/watch?v=" + id);
        }
        if (host.equals("player.vimeo.com") && path.startsWith("/video/")) {
            return new Match("Vimeo", "https://vimeo.com/" + segment(path, 2));
        }
        if ((host.endsWith("dailymotion.com") || host.equals("geo.dailymotion.com")) && path.contains("/embed/video/")) {
            return new Match("Dailymotion", "https://www.dailymotion.com/video/" + path.substring(path.lastIndexOf('/') + 1));
        }
        if (host.equals("platform.twitter.com") && path.startsWith("/embed/")) {
            String id = param(url, "id");
            return new Match("X / Twitter", id != null ? "https://x.com/i/status/" + id : url);
        }
        if ((host.equals("www.facebook.com") || host.equals("web.facebook.com")) && path.startsWith("/plugins/")) {
            if (path.startsWith("/plugins/like") || path.startsWith("/plugins/share_button")) return new Match("", null);
            String href = param(url, "href");
            return new Match("Facebook", href != null ? href : url);
        }
        if (host.endsWith("instagram.com") && path.endsWith("/embed") || host.endsWith("instagram.com") && path.contains("/embed/")) {
            return new Match("Instagram", url.replace("/embed/captioned", "").replace("/embed", ""));
        }
        if (host.endsWith("tiktok.com") && path.startsWith("/embed")) {
            String id = path.substring(path.lastIndexOf('/') + 1);
            return new Match("TikTok", "https://www.tiktok.com/@/video/" + id);
        }
        if (host.equals("open.spotify.com") && path.startsWith("/embed")) {
            return new Match("Spotify", "https://open.spotify.com" + path.substring("/embed".length()));
        }
        if (host.equals("w.soundcloud.com") && path.startsWith("/player")) {
            String u = param(url, "url");
            return new Match("SoundCloud", u != null ? u : url);
        }
        if (host.endsWith("twitch.tv") && (host.startsWith("player.") || host.startsWith("clips."))) {
            return new Match("Twitch", url);
        }
        if (host.endsWith("disqus.com") && path.startsWith("/embed/comments")) {
            return new Match("Disqus", url);
        }
        if ((host.equals("www.google.com") || host.equals("maps.google.com")) && path.startsWith("/maps/embed")) {
            String q = param(url, "q");
            return new Match("Google Maps", q != null ? "https://www.google.com/maps/search/" + q : "https://www.google.com/maps");
        }
        if (lower.contains("//www.reddit.com/") && path.contains("/embed")) {
            return new Match("Reddit", url);
        }
        return null;
    }

    static String pathOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) return "";
        int p = url.indexOf('/', s + 3);
        if (p < 0) return "/";
        int end = url.length();
        int q = url.indexOf('?', p);
        if (q >= 0) end = q;
        int h = url.indexOf('#', p);
        if (h >= 0 && h < end) end = h;
        return url.substring(p, end);
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("/");
        return parts.length > index ? parts[index] : "";
    }

    static String param(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        String query = url.substring(q + 1);
        int h = query.indexOf('#');
        if (h >= 0) query = query.substring(0, h);
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            String k = eq < 0 ? kv : kv.substring(0, eq);
            if (k.equals(name)) {
                try {
                    return eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
