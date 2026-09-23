package com.browserlite.web;

import android.content.Context;

import com.browserlite.Config;
import com.browserlite.net.UrlUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

/**
 * Builds the script and stylesheet injected into every page: ES2015+ polyfills for the KitKat engine,
 * e-ink rendering rules and page helpers (paging, reader mode, lazy images, memory watch).
 */
public final class Injector {
    private final Context app;
    private String polyfill, pageJs, readerJs, eink, contrast, banners, adhide, bold, readerCss;
    private byte[] cachedScript, cachedCss;
    private String cachedFor;

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
    public String headPayload(Config cfg, String refreshHeader) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<link rel=\"stylesheet\" href=\"https://").append(Interceptor.RES_HOST).append("/eink.css?v=")
                .append(cfg.hash).append("\" />");
        sb.append("<script src=\"https://").append(Interceptor.RES_HOST).append("/inject.js?v=").append(cfg.hash)
                .append("\"></script>");
        if (refreshHeader != null && refreshHeader.length() < 2000) {
            sb.append("<meta http-equiv=\"refresh\" content=\"").append(UrlUtil.htmlEscape(refreshHeader)).append("\" />");
        }
        return sb.toString();
    }

    private void rebuild(Config cfg) {
        load();
        StringBuilder js = new StringBuilder(polyfill.length() + pageJs.length() + 512);
        js.append("window.__blcfg={hc:").append(cfg.highContrast ? 1 : 0)
                .append(",cookie:").append(cfg.cookieBanners ? 1 : 0)
                .append(",ads:").append(cfg.adblock ? 1 : 0)
                .append(",unstick:").append(cfg.unstick ? 1 : 0)
                .append(",images:").append(cfg.imageMode)
                .append(",memMb:").append(cfg.jsHeapWarnMb)
                .append(",maxImg:").append(cfg.maxImageWidth)
                .append("};\n");
        if (cfg.polyfills) js.append(polyfill).append('\n');
        js.append(pageJs);
        StringBuilder css = new StringBuilder(eink);
        if (cfg.highContrast) css.append('\n').append(contrast);
        if (cfg.boldText) css.append('\n').append(bold);
        if (cfg.cookieBanners) css.append('\n').append(banners);
        if (cfg.adblock) css.append('\n').append(adhide);
        try {
            cachedScript = js.toString().getBytes("UTF-8");
            cachedCss = css.toString().getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
        cachedFor = cfg.hash;
    }

    public synchronized byte[] script(Config cfg) {
        if (cachedScript == null || !cfg.hash.equals(cachedFor)) rebuild(cfg);
        return cachedScript;
    }

    public synchronized byte[] css(Config cfg) {
        if (cachedCss == null || !cfg.hash.equals(cachedFor)) rebuild(cfg);
        return cachedCss;
    }

    /** Script evaluated after load on pages we could not inject into (POST results, JS navigations). */
    public synchronized String lateScript(Config cfg) {
        byte[] s = script(cfg);
        byte[] c = css(cfg);
        try {
            return "(function(){if(window.__bl)return;var s=document.createElement('style');s.textContent="
                    + UrlUtil.jsString(new String(c, "UTF-8")) + ";(document.head||document.documentElement).appendChild(s);"
                    + new String(s, "UTF-8") + "\n})();";
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
