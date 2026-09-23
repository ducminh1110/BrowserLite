package com.browserlite;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import com.browserlite.net.AdBlocker;
import com.browserlite.net.NetEngine;
import com.browserlite.web.Injector;
import com.browserlite.web.Interceptor;
import com.browserlite.web.Pages;

import java.io.IOException;
import java.util.Locale;

public final class BrowserApp extends Application {
    public interface TrimListener {
        void onTrim(int level);
    }

    private static BrowserApp instance;
    private Interceptor interceptor;
    private Pages pages;
    private Injector injector;
    private TrimListener trimListener;

    public static BrowserApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Prefs.init(this);
        applyLocale(this);
        applyYouTubeFallback();
        Config cfg = Config.reload(this);
        pages = new Pages(this);
        injector = new Injector(this);
        interceptor = new Interceptor(this, pages, injector);
        NetEngine.setCookiesEnabled(cfg.cookies);
        NetEngine.warmUp(this);
        reloadAdBlocker();
    }

    public Interceptor interceptor() {
        return interceptor;
    }

    public Pages pages() {
        return pages;
    }

    public Injector injector() {
        return injector;
    }

    public void setTrimListener(TrimListener l) {
        trimListener = l;
    }

    public void clearTrimListener(TrimListener l) {
        if (trimListener == l) trimListener = null;
    }

    /** Settings changed: rebuild the snapshot every component reads. */
    /** Video mode's last resort: a user-chosen Invidious instance (Settings → Video), or none. */
    static void applyYouTubeFallback() {
        final String instance = Prefs.str(Prefs.YT_FALLBACK, "").trim();
        com.browserlite.net.YouTube.setFallback(instance.isEmpty() ? null
                : (http, id) -> com.browserlite.net.YouTube.invidious(http, instance, id));
    }

    public Config reloadConfig() {
        applyYouTubeFallback();
        Config cfg = Config.reload(this);
        NetEngine.setCookiesEnabled(cfg.cookies);
        return cfg;
    }

    public void reloadAdBlocker() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AdBlocker b = AdBlocker.load(getAssets().open("adhosts.txt"));
                    for (String line : Prefs.str(Prefs.USER_BLOCKLIST, "").split("\n")) b.addRule(line);
                    interceptor.setAdBlocker(b);
                } catch (IOException ignored) {
                    // no blocking then
                }
            }
        }, "adblock-load").start();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (trimListener != null) trimListener.onTrim(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (trimListener != null) trimListener.onTrim(TRIM_MEMORY_COMPLETE);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLocale(this);
    }

    /** Applies the in-app language override ("auto" follows the system). */
    @SuppressWarnings("deprecation")
    public static void applyLocale(Context c) {
        String lang = Prefs.str(Prefs.LANG, "auto");
        if (lang.equals("auto")) return;
        Locale l = new Locale(lang);
        Locale.setDefault(l);
        Resources r = c.getResources();
        Configuration conf = r.getConfiguration();
        if (l.equals(conf.locale)) return;
        conf.locale = l;
        r.updateConfiguration(conf, r.getDisplayMetrics());
    }
}
