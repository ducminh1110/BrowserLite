package com.browserlite.web;

import android.content.Context;
import android.util.Log;
import android.util.LruCache;
import android.webkit.WebResourceResponse;

import com.browserlite.Config;
import com.browserlite.Profile;
import com.browserlite.net.AdBlocker;
import com.browserlite.net.CssCompat;
import com.browserlite.net.Embeds;
import com.browserlite.net.HtmlRewriter;
import com.browserlite.net.ImageOptimizer;
import com.browserlite.net.LazyStream;
import com.browserlite.net.NetEngine;
import com.browserlite.net.UrlUtil;
import com.browserlite.net.YouTube;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Decides, for every request the WebView makes, whether to let it through untouched, block it, or serve it
 * through the built-in network engine (modern TLS, image shrinking, CSS/JS compatibility).
 *
 * <p>API 19 tells us nothing but the URL, so main-frame navigations are recognised by matching against URLs
 * the UI announced via {@link #expectMainFrame}. Everything here runs on WebView threads.
 */
public final class Interceptor {
    private static final String TAG = "Interceptor";
    public static final String RES_HOST = "res.browserlite.invalid";
    public static final String HOME_URL = "https://home.browserlite.invalid/";
    private static final String RES_PREFIX = "https://" + RES_HOST + "/";

    private static final long PENDING_TTL_MS = 60_000;
    private static final byte[] GIF_1PX = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, (byte) 0x80, 0, 0, 0, 0, 0,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, '!', (byte) 0xF9, 4, 1, 0, 0, 0, 0, ',', 0, 0, 0, 0, 1, 0, 1, 0,
            0, 2, 2, 'D', 1, 0, ';'};

    private static final class Pending {
        final long time = System.currentTimeMillis();
        final String referrer;
        final boolean typed;

        Pending(String referrer, boolean typed) {
            this.referrer = referrer;
            this.typed = typed;
        }
    }

    private static final class Prefetched {
        final String url;
        final Response response;
        final long time = System.currentTimeMillis();

        Prefetched(String url, Response response) {
            this.url = url;
            this.response = response;
        }
    }

    private final Context app;
    private final Pages pages;
    private final Injector injector;
    private volatile AdBlocker adBlocker;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> bypass = new ConcurrentHashMap<>();
    private volatile Prefetched prefetched;
    private volatile String pageUrl = "";
    private final LruCache<String, CssCompat.VarRegistry> registries = new LruCache<>(6);

    /** Things the pages ask the app to do (links to res.browserlite.invalid that reach us instead of the UI). */
    public interface Actions {
        void play(String url);
    }

    private final YouTubePages youtube;
    private volatile Actions actions;

    public Interceptor(Context app, Pages pages, Injector injector) {
        this.app = app.getApplicationContext();
        this.pages = pages;
        this.injector = injector;
        this.youtube = new YouTubePages(this.app);
    }

    public void setActions(Actions a) {
        actions = a;
    }

    public void setAdBlocker(AdBlocker b) {
        adBlocker = b;
    }

    public AdBlocker adBlocker() {
        return adBlocker;
    }

    // ------------------------------------------------------------------ calls from the UI thread

    /** Announces that the main frame is about to load {@code url}. */
    public void expectMainFrame(String url, String referrer, boolean typed) {
        if (url == null) return;
        String key = UrlUtil.stripFragment(url);
        pending.put(key, new Pending(referrer, typed));
        if (pending.size() > 16) prune();
    }

    /** Next load of this URL goes through the WebView's own network stack (POST results, user choice). */
    public void bypassOnce(String url) {
        if (url == null) return;
        String key = UrlUtil.stripFragment(url);
        bypass.put(key, System.currentTimeMillis());
        pending.remove(key);
    }

    public void setPageUrl(String url) {
        pageUrl = url == null ? "" : url;
    }

    public void onLowMemory() {
        registries.evictAll();
        final Prefetched p = prefetched;
        prefetched = null;
        if (p != null) {
            // Closing an unread response closes its socket: network I/O, not allowed on the main thread.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    p.response.close();
                }
            }, "prefetch-close").start();
        }
        NetEngine.trim();
    }

    private void prune() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().time > PENDING_TTL_MS) it.remove();
        }
        for (Iterator<Map.Entry<String, Long>> it = bypass.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > PENDING_TTL_MS) it.remove();
        }
    }

    // ------------------------------------------------------------------ WebView entry point

    public WebResourceResponse intercept(String url) {
        try {
            return interceptInner(url);
        } catch (Throwable t) {
            Log.w(TAG, "intercept failed for " + url, t);
            return null;
        }
    }

    private WebResourceResponse interceptInner(String url) throws IOException {
        if (url == null) return null;
        if (url.startsWith(RES_PREFIX) || url.startsWith("http://" + RES_HOST + "/")) return serveInternal(url);
        if (url.startsWith(HOME_URL)) {
            return html(pages.home(Config.get()));
        }
        if (!UrlUtil.isHttp(url)) return null;
        Config cfg = Config.get();
        if (cfg == null) return null;
        String key = UrlUtil.stripFragment(url);

        Long bypassed = bypass.remove(key);
        if (bypassed != null && System.currentTimeMillis() - bypassed < PENDING_TTL_MS) return null;

        Pending main = pending.remove(key);
        if (main != null && System.currentTimeMillis() - main.time > PENDING_TTL_MS) main = null;
        if (main != null) return cfg.modernNet ? mainFrame(url, key, main, cfg) : null;

        return subresource(url, cfg);
    }

    // ------------------------------------------------------------------ subresources

    private WebResourceResponse subresource(String url, Config cfg) throws IOException {
        String host = UrlUtil.host(url);
        String page = pageUrl;
        String pageHost = UrlUtil.host(page);
        AdBlocker blocker = adBlocker;
        int kind = UrlUtil.kindOf(url);
        Profile pr = cfg.profileFor(pageHost);
        if (blocker != null && pr.adblock && !UrlUtil.sameSite(host, pageHost) && blocker.isBlocked(host)) {
            return blocked(kind);
        }
        if (!UrlUtil.sameSite(host, pageHost)) {
            // The real YouTube player cannot run on this engine: with video mode on it is always replaced.
            String yt = cfg.videoMode ? YouTube.embedId(url) : null;
            if (yt != null) return html(pages.youtubeEmbed(yt));
            Embeds.Match m = pr.embeds ? Embeds.match(url) : null;
            if (m != null) {
                if (m.target == null) return html("");
                return html(pages.embedPlaceholder(m.label, m.target));
            }
        }
        OkHttpClient client = NetEngine.clientOrNull();
        if (client == null) return null; // engine still starting: let the WebView handle it
        switch (kind) {
            case UrlUtil.KIND_IMAGE:
                if (pr.imageMode == Config.IMAGES_OPTIMIZE) {
                    return lazy(client, url, kind, page, new ImageOptimizer(cfg, pr, false), cfg);
                }
                if (pr.imageMode == Config.IMAGES_FULL && cfg.lowRam && cfg.memoryGuard) {
                    return lazy(client, url, kind, page, new ImageOptimizer(cfg, pr, true), cfg);
                }
                return null;
            case UrlUtil.KIND_CSS:
                if (pr.blockFonts && host.equals("fonts.googleapis.com")) return text("text/css", "");
                if (cfg.cssCompat) return lazy(client, url, kind, page, cssTransformer(pageHost, cfg, pr), cfg);
                return cfg.routeAssets ? lazy(client, url, kind, page, null, cfg) : null;
            case UrlUtil.KIND_FONT:
                if (pr.blockFonts) return new WebResourceResponse(UrlUtil.mimeForKind(kind, url), null,
                        new ByteArrayInputStream(new byte[0]));
                return cfg.routeAssets ? lazy(client, url, kind, page, null, cfg) : null;
            case UrlUtil.KIND_SCRIPT:
                return cfg.routeAssets ? lazy(client, url, kind, page, null, cfg) : null;
            default:
                return null;
        }
    }

    private WebResourceResponse blocked(int kind) {
        if (kind == UrlUtil.KIND_IMAGE) {
            return new WebResourceResponse("image/gif", null, new ByteArrayInputStream(GIF_1PX));
        }
        if (kind == UrlUtil.KIND_CSS) return text("text/css", "");
        if (kind == UrlUtil.KIND_SCRIPT) return text("application/javascript", "");
        return text("text/plain", "");
    }

    private WebResourceResponse lazy(OkHttpClient client, String url, int kind, String page,
            LazyStream.Transformer transformer, Config cfg) {
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", cfg.userAgent)
                .header("Accept-Language", cfg.acceptLanguage);
        if (kind == UrlUtil.KIND_IMAGE) rb.header("Accept", "image/webp,image/*,*/*;q=0.8");
        else if (kind == UrlUtil.KIND_CSS) rb.header("Accept", "text/css,*/*;q=0.1");
        else rb.header("Accept", "*/*");
        if (cfg.saveData) rb.header("Save-Data", "on");
        String ref = UrlUtil.referrer(page, url);
        if (ref != null) rb.header("Referer", ref);
        Request req;
        try {
            req = rb.build();
        } catch (IllegalArgumentException e) {
            return null; // URL OkHttp can't parse: let the WebView try
        }
        String charset = kind == UrlUtil.KIND_CSS ? "UTF-8" : null;
        return new WebResourceResponse(UrlUtil.mimeForKind(kind, url), charset, new LazyStream(client, req, transformer));
    }

    private CssCompat.VarRegistry registryFor(String pageHost) {
        String site = UrlUtil.siteOf(pageHost);
        synchronized (registries) {
            CssCompat.VarRegistry r = registries.get(site);
            if (r == null) {
                r = new CssCompat.VarRegistry();
                registries.put(site, r);
            }
            return r;
        }
    }

    private LazyStream.Transformer cssTransformer(String pageHost, final Config cfg, final Profile pr) {
        final CssCompat.VarRegistry reg = registryFor(pageHost);
        return new LazyStream.Transformer() {
            @Override
            public InputStream apply(Response response) throws IOException {
                ResponseBody body = response.body();
                if (body == null) return new ByteArrayInputStream(new byte[0]);
                if (!response.isSuccessful()) {
                    response.close();
                    return new ByteArrayInputStream(new byte[0]);
                }
                long len = body.contentLength();
                if (len > cfg.cssMaxLength) return body.byteStream();
                Charset cs = charsetOf(response.header("Content-Type"));
                String css = readString(body.byteStream(), cs, cfg.cssMaxLength);
                if (css == null) return new ByteArrayInputStream(new byte[0]);
                CssCompat.Options o = new CssCompat.Options();
                o.blockFonts = pr.blockFonts;
                o.maxLength = cfg.cssMaxLength;
                String out = CssCompat.transform(css, reg, o);
                return new ByteArrayInputStream(out.getBytes("UTF-8"));
            }
        };
    }

    private static Charset charsetOf(String contentType) {
        String cs = UrlUtil.charsetOf(contentType);
        if (cs != null) {
            try {
                return Charset.forName(cs);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return Charset.forName("UTF-8");
    }

    private static String readString(InputStream in, Charset cs, int max) throws IOException {
        Reader r = new InputStreamReader(in, cs);
        StringBuilder sb = new StringBuilder(8192);
        char[] buf = new char[8192];
        int n;
        try {
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > max * 2) break;
            }
        } finally {
            r.close();
        }
        if (sb.length() > 0 && sb.charAt(0) == '\uFEFF') sb.deleteCharAt(0);
        return sb.toString();
    }

    // ------------------------------------------------------------------ main frame

    /**
     * Main-frame documents. On KitKat this runs on the WebView's network thread and CookieManager calls from
     * any thread need that same thread, so we must never wait here for the network: the response is fetched
     * asynchronously and everything (redirects, downloads, non-HTML) is resolved inside the lazy stream.
     */
    private WebResourceResponse mainFrame(final String url, final String key, final Pending p, final Config cfg) {
        if (cfg.autoRam) com.browserlite.MemoryState.update(app, 2000); // the page's profile follows free RAM
        if (cfg.videoMode && YouTube.isYouTubeHost(UrlUtil.host(url))) {
            // youtube.com itself needs a far newer engine: answer with our own lightweight pages.
            return new WebResourceResponse("text/html", "UTF-8", new LazyStream(new LazyStream.Producer() {
                @Override
                public InputStream produce() {
                    return htmlStream(youtube.render(url, cfg));
                }
            }, null));
        }
        OkHttpClient client = NetEngine.clientOrNull();
        LazyStream.Transformer transform = new LazyStream.Transformer() {
            @Override
            public InputStream apply(Response response) throws IOException {
                return mainTransform(response, url, key, p, cfg);
            }
        };
        LazyStream.Fallback fallback = new LazyStream.Fallback() {
            @Override
            public InputStream onFailure(IOException error) {
                return mainFailure(error, url, p, cfg);
            }
        };
        Prefetched pf = prefetched;
        if (pf != null && pf.url.equals(key) && System.currentTimeMillis() - pf.time < 20_000) {
            prefetched = null;
            return new WebResourceResponse("text/html", null, new LazyStream(pf.response, transform, fallback));
        }
        Request req = mainRequest(url, p.referrer, cfg);
        if (req == null) return null;
        if (client == null) {
            // First load right after launch: wait for the engine on a worker thread, never on this one.
            LazyStream.ClientSource source = new LazyStream.ClientSource() {
                @Override
                public OkHttpClient get() throws IOException {
                    return NetEngine.client(app);
                }
            };
            return new WebResourceResponse("text/html", null, new LazyStream(source, req, transform, fallback));
        }
        return new WebResourceResponse("text/html", null, new LazyStream(client, req, transform, fallback));
    }

    /** Runs on an OkHttp thread. The WebView has been told "text/html", so everything becomes HTML here. */
    private InputStream mainTransform(Response resp, String url, String key, Pending p, Config cfg) throws IOException {
        int code = resp.code();
        String finalUrl = UrlUtil.stripFragment(resp.request().url().toString());
        if (code == 401 || code == 407) {
            resp.close();
            return htmlStream(pages.needsSystemNetwork(url));
        }
        if (code == 204 || code == 205) {
            resp.close();
            return htmlStream(pages.goBack());
        }
        if (code >= 300 && code < 400) {
            String loc = resp.header("Location");
            resp.close();
            if (loc == null) return htmlStream(pages.error(url, new IOException("HTTP " + code)));
            return htmlStream(pages.redirect(resolve(finalUrl, loc)));
        }
        if (!finalUrl.equals(key)) {
            // Redirected: the document must live at its final URL or relative links and cookies break.
            Prefetched old = prefetched;
            if (old != null) old.response.close();
            prefetched = new Prefetched(finalUrl, resp);
            expectMainFrame(finalUrl, p.referrer, false);
            return htmlStream(pages.redirect(finalUrl));
        }
        String ct = resp.header("Content-Type");
        String mime = UrlUtil.mimeOf(ct);
        String charset = UrlUtil.charsetOf(ct);
        String disposition = resp.header("Content-Disposition");
        boolean attachment = disposition != null && disposition.trim().toLowerCase(Locale.US).startsWith("attachment");
        ResponseBody body = resp.body();
        if (body == null) return htmlStream("");
        if (attachment || !renderable(mime)) {
            String name = com.browserlite.Downloader.fileName(url, disposition, mime);
            com.browserlite.Downloader.save(app, resp, url, name, mime);
            return htmlStream(pages.downloading(name));
        }
        if (mime.isEmpty() || mime.equals("text/html") || mime.equals("application/xhtml+xml")) {
            if (charset != null && charset.toLowerCase(Locale.US).startsWith("utf-16")) {
                // Not ASCII compatible: decode ourselves and hand over UTF-8 without rewriting.
                return new ByteArrayInputStream(readString(body.byteStream(), charsetOf(ct), 4 << 20).getBytes("UTF-8"));
            }
            HtmlRewriter.Options o = new HtmlRewriter.Options();
            Profile pr = cfg.profileFor(UrlUtil.host(url));
            String payload = injector.headPayload(cfg, pr, resp.header("Refresh"));
            if (charset != null) {
                // We could not pass the HTTP charset to the WebView up front; declare it in the document instead.
                payload = "<meta charset=\"" + UrlUtil.htmlEscape(charset) + "\" />" + payload;
            }
            o.headPayload = payload;
            o.transformCss = cfg.cssCompat;
            o.registry = registryFor(UrlUtil.host(url));
            CssCompat.Options co = new CssCompat.Options();
            co.blockFonts = pr.blockFonts;
            co.maxLength = cfg.cssMaxLength;
            o.cssOptions = co;
            o.maxStyleBuffer = cfg.lowRam ? 512 * 1024 : 1024 * 1024;
            o.deferLazy = pr.javascript;
            return new ResponseStream(new HtmlRewriter(body.byteStream(), o), resp);
        }
        if (mime.startsWith("image/")) {
            resp.close();
            return htmlStream(pages.mediaDocument(url, "img"));
        }
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            resp.close();
            return htmlStream(pages.mediaDocument(url, mime.startsWith("video/") ? "video" : "audio"));
        }
        // text/plain, JSON, XML, CSS, JS...: show as readable text.
        String text = readString(body.byteStream(), charsetOf(ct), 2 << 20);
        return htmlStream(pages.textDocument(url, text));
    }

    private InputStream mainFailure(IOException e, String url, Pending p, Config cfg) {
        if (p.typed && url.startsWith("https://")) {
            // Typed "example.com" became https://; old sites may only speak http.
            String http = "http://" + url.substring(8);
            Request r2 = mainRequest(http, null, cfg);
            OkHttpClient client = NetEngine.clientOrNull();
            if (r2 != null && client != null) {
                try {
                    Response r = client.newCall(r2).execute();
                    String target = UrlUtil.stripFragment(r.request().url().toString());
                    Prefetched old = prefetched;
                    if (old != null) old.response.close();
                    prefetched = new Prefetched(target, r);
                    expectMainFrame(target, null, false);
                    return htmlStream(pages.redirect(target));
                } catch (IOException ignored) {
                    // report the original error
                }
            }
        }
        return htmlStream(pages.error(url, e));
    }

    private static InputStream htmlStream(String html) {
        try {
            return new ByteArrayInputStream(html.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private Request mainRequest(String url, String referrer, Config cfg) {
        try {
            Request.Builder rb = new Request.Builder().url(url)
                    .header("User-Agent", cfg.userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", cfg.acceptLanguage)
                    .header("Upgrade-Insecure-Requests", "1");
            if (cfg.saveData) rb.header("Save-Data", "on");
            if (referrer != null) rb.header("Referer", referrer);
            return rb.build();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String resolve(String base, String loc) {
        okhttp3.HttpUrl b = okhttp3.HttpUrl.parse(base);
        if (loc.contains(":") && !loc.startsWith("/")) {
            int colon = loc.indexOf(':');
            int slash = loc.indexOf('/');
            if (slash < 0 || colon < slash) return loc; // absolute, possibly a non-http scheme
        }
        okhttp3.HttpUrl r = b == null ? null : b.resolve(loc);
        return r == null ? loc : r.toString();
    }

    private static boolean renderable(String mime) {
        if (mime.isEmpty()) return true;
        if (mime.startsWith("image/")) return !mime.contains("tiff");
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return true;
        switch (mime) {
            case "text/html":
            case "application/xhtml+xml":
            case "text/plain":
            case "text/xml":
            case "application/xml":
            case "application/rss+xml":
            case "application/atom+xml":
            case "application/json":
            case "text/css":
            case "application/javascript":
            case "text/javascript":
            case "text/csv":
            case "text/markdown":
                return true;
            default:
                return false;
        }
    }

    /** Closes the OkHttp response together with the stream the WebView reads. */
    private static final class ResponseStream extends FilterInputStream {
        private final Response resp;

        ResponseStream(InputStream in, Response resp) {
            super(in);
            this.resp = resp;
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                resp.close();
            }
        }
    }

    // ------------------------------------------------------------------ internal resources

    private WebResourceResponse serveInternal(String url) {
        String path = url.substring(url.indexOf(RES_HOST) + RES_HOST.length());
        int q = path.indexOf('?');
        String query = q >= 0 ? path.substring(q + 1) : "";
        if (q >= 0) path = path.substring(0, q);
        Config cfg = Config.get();
        switch (path) {
            case "/inject.js":
                return bytes("application/javascript", injector.script(cfg, profileParam(query, cfg)));
            case "/eink.css":
                return bytes("text/css", injector.css(cfg, profileParam(query, cfg)));
            case "/still.css":
                return bytes("text/css", injector.stillCss());
            case "/play":
            case "/ytdl": {
                // Normally caught in shouldOverrideUrlLoading; some navigations (iframes on KitKat) arrive here.
                Actions a = actions;
                if (a != null) a.play(url);
                return html(pages.goBack());
            }
            case "/bypass": {
                String target = queryParam(query, "u");
                if (target == null || !UrlUtil.isHttp(target)) return html("");
                bypassOnce(target);
                return html(pages.redirect(target));
            }
            default:
                return text("text/plain", "");
        }
    }

    private static Profile profileParam(String query, Config cfg) {
        String p = queryParam(query, "p");
        if (p != null) {
            try {
                return Profile.fromBits(Integer.parseInt(p, 16));
            } catch (NumberFormatException ignored) {
                // fall back to the global profile
            }
        }
        return cfg.profileFor("");
    }

    private static String queryParam(String query, String name) {
        for (String kv : query.split("&")) {
            if (kv.startsWith(name + "=")) {
                try {
                    return URLDecoder.decode(kv.substring(name.length() + 1), "UTF-8");
                } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    static WebResourceResponse html(String s) {
        return text("text/html", s);
    }

    static WebResourceResponse text(String mime, String s) {
        try {
            return new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(s.getBytes("UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    static WebResourceResponse bytes(String mime, byte[] b) {
        return new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(b));
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
