package com.browserlite.web;

import android.content.Context;
import android.content.res.Resources;

import com.browserlite.Config;
import com.browserlite.R;
import com.browserlite.data.Db;
import com.browserlite.net.UrlUtil;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.List;

import javax.net.ssl.SSLException;

/** Generates the built-in pages (home, errors, redirect stubs, embed placeholders). */
public final class Pages {
    private final Context app;

    public Pages(Context app) {
        this.app = app.getApplicationContext();
    }

    private Resources res() {
        return app.getResources();
    }

    private static final String STYLE = "<style>"
            + "html{-webkit-text-size-adjust:100%}"
            + "body{margin:0;padding:12px 14px 40px;font:18px/1.45 sans-serif;color:#000;background:#fff}"
            + "h1{font-size:22px;margin:8px 0 12px}"
            + "h2{font-size:15px;letter-spacing:1px;text-transform:uppercase;border-bottom:2px solid #000;"
            + "padding-bottom:4px;margin:26px 0 4px}"
            + "form{display:-webkit-box;display:flex;margin:10px 0 6px}"
            + "input[type=search],input[type=text]{-webkit-box-flex:1;flex:1;min-width:0;font-size:20px;padding:12px;"
            + "border:2px solid #000;border-radius:0;-webkit-appearance:none;background:#fff;color:#000}"
            + "button,.btn{font-size:18px;padding:12px 18px;border:2px solid #000;background:#000;color:#fff;"
            + "border-radius:0;-webkit-appearance:none;text-decoration:none;display:inline-block;margin:6px 8px 6px 0}"
            + ".btn.alt{background:#fff;color:#000}"
            + "ul{list-style:none;margin:0;padding:0}"
            + "li a{display:block;padding:13px 2px;border-bottom:1px solid #000;color:#000;text-decoration:none;"
            + "overflow:hidden;white-space:nowrap;text-overflow:ellipsis}"
            + "li a small{display:block;font-size:13px;color:#333;overflow:hidden;text-overflow:ellipsis}"
            + ".muted{color:#333;font-size:15px}"
            + ".url{word-break:break-all;font-size:14px;color:#333}"
            + "</style>";

    private static String head(String title) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + UrlUtil.htmlEscape(title) + "</title>" + STYLE + "</head><body>";
    }

    public String home(Config cfg) {
        Resources r = res();
        StringBuilder sb = new StringBuilder(4096);
        sb.append(head(r.getString(R.string.home_title)));
        sb.append(searchForm(cfg.searchTemplate, r.getString(R.string.hint_address), r.getString(R.string.home_search_button)));
        Db db = Db.get(app);
        List<Db.Entry> bookmarks = db.bookmarks();
        if (!bookmarks.isEmpty()) {
            sb.append("<h2>").append(UrlUtil.htmlEscape(r.getString(R.string.home_bookmarks))).append("</h2><ul>");
            appendLinks(sb, bookmarks, 30);
            sb.append("</ul>");
        }
        List<Db.Entry> top = db.topSites(8);
        if (!top.isEmpty()) {
            sb.append("<h2>").append(UrlUtil.htmlEscape(r.getString(R.string.home_top))).append("</h2><ul>");
            appendLinks(sb, top, 8);
            sb.append("</ul>");
        }
        if (bookmarks.isEmpty() && top.isEmpty()) {
            sb.append("<h2>").append(UrlUtil.htmlEscape(r.getString(R.string.home_suggested))).append("</h2><ul>");
            String[][] links = {
                    {"https://vi.m.wikipedia.org/", "Wikipedia"},
                    {"https://vnexpress.net/", "VnExpress"},
                    {"https://lite.duckduckgo.com/lite/", "DuckDuckGo Lite"},
                    {"https://news.google.com/", "Google News"},
                    {"https://www.gutenberg.org/", "Project Gutenberg"},
                    {"https://text.npr.org/", "NPR Text"}};
            for (String[] l : links) {
                sb.append("<li><a href=\"").append(UrlUtil.htmlEscape(l[0])).append("\">")
                        .append(UrlUtil.htmlEscape(l[1])).append("</a></li>");
            }
            sb.append("</ul>");
        }
        sb.append("<p class=\"muted\">").append(UrlUtil.htmlEscape(r.getString(R.string.home_tip))).append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void appendLinks(StringBuilder sb, List<Db.Entry> entries, int max) {
        int n = 0;
        for (Db.Entry e : entries) {
            if (n++ >= max) break;
            sb.append("<li><a href=\"").append(UrlUtil.htmlEscape(e.url)).append("\">")
                    .append(UrlUtil.htmlEscape(e.title));
            if (!e.title.equals(e.url)) {
                sb.append("<small>").append(UrlUtil.htmlEscape(UrlUtil.host(e.url))).append("</small>");
            }
            sb.append("</a></li>");
        }
    }

    /** A plain GET form built from the search template, so it works with JavaScript disabled too. */
    static String searchForm(String template, String hint, String button) {
        int q = template.indexOf('?');
        String action = q >= 0 ? template.substring(0, q) : template;
        StringBuilder hidden = new StringBuilder();
        String field = "q";
        if (q >= 0) {
            for (String kv : template.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq < 0) continue;
                String k = kv.substring(0, eq);
                String v = kv.substring(eq + 1);
                if (v.equals("%s")) {
                    field = k;
                } else {
                    hidden.append("<input type=\"hidden\" name=\"").append(UrlUtil.htmlEscape(k)).append("\" value=\"")
                            .append(UrlUtil.htmlEscape(decode(v))).append("\">");
                }
            }
        }
        return "<form action=\"" + UrlUtil.htmlEscape(action) + "\" method=\"get\">" + hidden
                + "<input type=\"search\" name=\"" + UrlUtil.htmlEscape(field) + "\" placeholder=\""
                + UrlUtil.htmlEscape(hint) + "\" autocomplete=\"off\"><button type=\"submit\">"
                + UrlUtil.htmlEscape(button) + "</button></form>";
    }

    private static String decode(String v) {
        try {
            return java.net.URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    public String error(String url, IOException e) {
        Resources r = res();
        String reason;
        Throwable root = e;
        if (e instanceof UnknownHostException) {
            reason = r.getString(R.string.err_dns);
        } else if (e instanceof SocketTimeoutException || e instanceof InterruptedIOException) {
            reason = r.getString(R.string.err_timeout);
        } else if (e instanceof ConnectException) {
            reason = r.getString(R.string.err_connect);
        } else if (e instanceof SSLException || (e.getCause() instanceof java.security.cert.CertificateException)) {
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            reason = r.getString(R.string.err_ssl, String.valueOf(root.getMessage()));
        } else {
            reason = r.getString(R.string.err_other, String.valueOf(e.getMessage()));
        }
        String bypass;
        try {
            bypass = "https://" + Interceptor.RES_HOST + "/bypass?u=" + URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException x) {
            bypass = url;
        }
        return head(r.getString(R.string.err_title))
                + "<h1>" + UrlUtil.htmlEscape(r.getString(R.string.err_title)) + "</h1>"
                + "<p class=\"url\">" + UrlUtil.htmlEscape(url) + "</p>"
                + "<p>" + UrlUtil.htmlEscape(reason) + "</p>"
                + "<a class=\"btn\" href=\"" + UrlUtil.htmlEscape(url) + "\">" + UrlUtil.htmlEscape(r.getString(R.string.err_retry)) + "</a>"
                + "<a class=\"btn alt\" href=\"" + UrlUtil.htmlEscape(bypass) + "\">" + UrlUtil.htmlEscape(r.getString(R.string.err_system)) + "</a>"
                + "</body></html>";
    }

    /** Sends the main frame to {@code target}, replacing the current history entry. Works without JS. */
    public String redirect(String target) {
        String esc = UrlUtil.htmlEscape(target);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url=" + esc
                + "\"><script>location.replace(" + UrlUtil.jsString(target) + ")</script></head>"
                + "<body style=\"background:#fff\"><a href=\"" + esc + "\">" + esc + "</a></body></html>";
    }

    public String embedPlaceholder(String label, String target) {
        Resources r = res();
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<style>html,body{margin:0;height:100%;background:#fff}"
                + "a{display:-webkit-box;display:flex;-webkit-box-orient:vertical;flex-direction:column;"
                + "-webkit-box-pack:center;justify-content:center;-webkit-box-align:center;align-items:center;"
                + "box-sizing:border-box;height:100%;min-height:80px;border:2px solid #000;color:#000;"
                + "text-decoration:none;font:17px sans-serif;padding:10px;text-align:center}"
                + "b{font-size:19px;margin-bottom:6px;text-decoration:underline}</style></head><body>"
                + "<a href=\"" + UrlUtil.htmlEscape(target) + "\" target=\"_top\"><b>"
                + UrlUtil.htmlEscape(r.getString(R.string.embed_open, label)) + "</b>"
                + UrlUtil.htmlEscape(r.getString(R.string.embed_note)) + "</a></body></html>";
    }

    /** Stand-in for an embedded YouTube player: its thumbnail, and a tap opens the native player. */
    public String youtubeEmbed(String id) {
        Resources r = res();
        String play = YouTubePages.PLAY + "?v=" + id;
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<style>html,body{margin:0;height:100%;background:#000}"
                + "a{position:relative;display:block;height:100%;min-height:90px;text-decoration:none;"
                + "background:#000 url(https://i.ytimg.com/vi/" + id + "/mqdefault.jpg) center/cover no-repeat}"
                + "span{position:absolute;left:50%;top:50%;-webkit-transform:translate(-50%,-50%);transform:translate(-50%,-50%);"
                + "background:#fff;color:#000;border:3px solid #000;padding:10px 16px;white-space:nowrap;font:bold 17px sans-serif}"
                + "</style></head><body><a href=\"" + play + "\" target=\"_top\"><span>&#9654; "
                + UrlUtil.htmlEscape(r.getString(R.string.yt_play_embed)) + "</span></a></body></html>";
    }

    public String downloading(String name) {
        Resources r = res();
        return head(name) + "<h1>" + UrlUtil.htmlEscape(r.getString(R.string.page_downloading, name)) + "</h1>"
                + "<p class=\"muted\">" + UrlUtil.htmlEscape(r.getString(R.string.page_downloading_note)) + "</p>"
                + "<a class=\"btn\" href=\"javascript:history.back()\" onclick=\"history.back();return false\">"
                + UrlUtil.htmlEscape(r.getString(R.string.page_back)) + "</a></body></html>";
    }

    /** For responses the modern engine can't show directly (HTTP authentication): retry via the WebView stack. */
    public String needsSystemNetwork(String url) {
        Resources r = res();
        String bypass;
        try {
            bypass = "https://" + Interceptor.RES_HOST + "/bypass?u=" + URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException x) {
            bypass = url;
        }
        return redirect(bypass).replace("<title>", "<title>" + UrlUtil.htmlEscape(r.getString(R.string.auth_title, UrlUtil.host(url))));
    }

    public String goBack() {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><script>history.back()</script></head><body></body></html>";
    }

    public String mediaDocument(String url, String tag) {
        String esc = UrlUtil.htmlEscape(url);
        String el;
        if (tag.equals("img")) {
            el = "<img src=\"" + esc + "\" style=\"max-width:100%;height:auto\">";
        } else {
            // The device player (or the built-in decoder) instead of the WebView's, which cannot draw video here.
            String play;
            try {
                play = YouTubePages.PLAY + "?u=" + URLEncoder.encode(url, "UTF-8") + (tag.equals("audio") ? "&amp;a=1" : "");
            } catch (UnsupportedEncodingException e) {
                play = url;
            }
            el = "<p style=\"margin:40px 10px\"><a href=\"" + play + "\" style=\"display:inline-block;padding:16px 24px;"
                    + "border:3px solid #000;color:#000;font:bold 20px sans-serif;text-decoration:none\">&#9654; "
                    + UrlUtil.htmlEscape(res().getString(R.string.yt_play)) + "</a></p><p style=\"font:14px sans-serif;"
                    + "word-break:break-all;padding:0 10px\">" + esc + "</p>";
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>" + esc + "</title></head><body style=\"margin:0;background:#fff;text-align:center\">" + el
                + "</body></html>";
    }

    public String textDocument(String url, String text) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>" + UrlUtil.htmlEscape(url) + "</title></head><body style=\"margin:0;background:#fff\">"
                + "<pre style=\"white-space:pre-wrap;word-wrap:break-word;font:14px/1.4 monospace;margin:8px;color:#000\">"
                + UrlUtil.htmlEscape(text) + "</pre></body></html>";
    }

    /** Wraps extracted article HTML (already sanitised) in the reader template. */
    public String reader(String title, String byline, String site, String bodyHtml, String css, String originalUrl,
            int minutes) {
        Resources r = res();
        StringBuilder sb = new StringBuilder(bodyHtml.length() + 2048);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(UrlUtil.htmlEscape(title)).append("</title><style>").append(css)
                .append("</style></head><body><article>");
        sb.append("<p class=\"bl-meta\">").append(UrlUtil.htmlEscape(site));
        if (minutes > 0) sb.append(" · ").append(UrlUtil.htmlEscape(r.getString(R.string.reader_minutes, minutes)));
        sb.append("</p><h1>").append(UrlUtil.htmlEscape(title)).append("</h1>");
        if (byline != null && !byline.isEmpty()) {
            sb.append("<p class=\"bl-byline\">").append(UrlUtil.htmlEscape(byline)).append("</p>");
        }
        sb.append(bodyHtml);
        sb.append("<p class=\"bl-foot\"><a href=\"").append(UrlUtil.htmlEscape(originalUrl)).append("\">")
                .append(UrlUtil.htmlEscape(r.getString(R.string.reader_original))).append("</a></p>");
        sb.append("</article></body></html>");
        return sb.toString();
    }
}
