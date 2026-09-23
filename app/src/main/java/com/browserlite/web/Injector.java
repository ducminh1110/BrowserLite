package com.browserlite.web;

import android.content.Context;

import com.browserlite.Config;
import com.browserlite.Profile;
import com.browserlite.net.UrlUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the script and stylesheet injected into every page: ES2015+ polyfills for the KitKat engine,
 * e-ink rendering rules and page helpers (paging, reader mode, lazy images, memory watch).
 */
public final class Injector {
    private final Context app;
    private String polyfill, pageJs, readerJs, eink, still, contrast, banners, adhide, bold, readerCss;
    private byte[] stillBytes;
    /** Built script and stylesheet per (settings hash, page profile); a handful of profiles are in use at once. */
    private final LinkedHashMap<String, byte[][]> built = new LinkedHashMap<String, byte[][]>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[][]> e) {
            return size() > 4;
        }
    };

    public Injector(Context app) {
        this.app = app.getApplicationContext();
    }

    private String asset(String name) {
        try {
            InputStream in = app.getAssets().open("web/" + name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private synchronized void load() {
        if (polyfill != null) return;
        polyfill = asset("polyfill.js");
        pageJs = asset("page.js");
        eink = asset("eink.css");
        still = asset("still.css");
        contrast = asset("contrast.css");
        banners = asset("banners.css");
        adhide = asset("adhide.css");
        bold = asset("bold.css");
    }

    public synchronized String readerScript() {
        if (readerJs == null) readerJs = asset("reader.js");
        return readerJs;
    }

    public synchronized String readerCss() {
        if (readerCss == null) readerCss = asset("reader.css");
        return readerCss;
    }

    /** Tags placed at the top of {@code <head>}. XHTML-safe. */
    public String headPayload(Config cfg, Profile p, String refreshHeader) {
        StringBuilder sb = new StringBuilder(320);
        String q = "?v=" + cfg.hash + "&amp;p=" + Integer.toHexString(p.bits());
        sb.append("<link rel=\"stylesheet\" href=\"https://").append(Interceptor.RES_HOST).append("/eink.css").append(q)
                .append("\" />");
        if (p.still) {
            sb.append("<link id=\"__bl_still\" rel=\"stylesheet\" href=\"https://").append(Interceptor.RES_HOST)
                    .append("/still.css\" />");
        }
        sb.append("<script src=\"https://").append(Interceptor.RES_HOST).append("/inject.js").append(q)
                .append("\"></script>");
        if (refreshHeader != null && refreshHeader.length() < 2000) {
            sb.append("<meta http-equiv=\"refresh\" content=\"").append(UrlUtil.htmlEscape(refreshHeader)).append("\" />");
        }
        return sb.toString();
    }

    private byte[][] build(Config cfg, Profile p) {
        String key = cfg.hash + "|" + p.bits();
        byte[][] b = built.get(key);
        if (b != null) return b;
        load();
        StringBuilder js = new StringBuilder(polyfill.length() + pageJs.length() + 512);
        js.append("window.__blcfg={hc:").append(p.contrast ? 1 : 0)
                .append(",cookie:").append(p.cookieBanners ? 1 : 0)
                .append(",ads:").append(p.adblock ? 1 : 0)
                .append(",unstick:").append(p.unstick ? 1 : 0)
                .append(",images:").append(p.imageMode)
                .append(",still:").append(p.still ? 1 : 0)
                .append(",video:").append(cfg.videoMode ? 1 : 0)
                .append(",memMb:").append(cfg.jsHeapWarnMb)
                .append(",maxImg:").append(cfg.maxImageWidth)
                .append("};\n");
        if (cfg.polyfills) js.append(polyfill).append('\n');
        js.append(pageJs);
        StringBuilder css = new StringBuilder(eink);
        if (p.contrast) css.append('\n').append(contrast);
        if (p.bold) css.append('\n').append(bold);
        if (p.cookieBanners) css.append('\n').append(banners);
        if (p.adblock) css.append('\n').append(adhide);
        b = new byte[][] {utf8(js.toString()), utf8(css.toString())};
        built.put(key, b);
        return b;
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    public synchronized byte[] script(Config cfg, Profile p) {
        return build(cfg, p)[0];
    }

    public synchronized byte[] css(Config cfg, Profile p) {
        return build(cfg, p)[1];
    }

    public synchronized byte[] stillCss() {
        load();
        if (stillBytes == null) stillBytes = utf8(still);
        return stillBytes;
    }

    /** Script evaluated after load on pages we could not inject into (POST results, JS navigations). */
    public synchronized String lateScript(Config cfg, Profile p) {
        byte[][] b = build(cfg, p);
        try {
            StringBuilder sb = new StringBuilder(b[0].length + b[1].length + 1024);
            sb.append("(function(){if(window.__bl)return;var h=document.head||document.documentElement;")
                    .append("var s=document.createElement('style');s.textContent=")
                    .append(UrlUtil.jsString(new String(b[1], "UTF-8"))).append(";h.appendChild(s);");
            if (p.still) {
                sb.append("var t=document.createElement('style');t.id='__bl_still';t.textContent=")
                        .append(UrlUtil.jsString(still)).append(";h.appendChild(t);");
            }
            sb.append(new String(b[0], "UTF-8")).append("\n})();");
            return sb.toString();
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
