package com.browserlite;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.webkit.WebSettings;

import java.util.Locale;
import java.util.Set;

/**
 * Immutable snapshot of settings plus the device memory profile. Read from any thread via {@link #get()}.
 */
public final class Config {
    public static final int IMAGES_FULL = 0;
    public static final int IMAGES_OPTIMIZE = 1;
    public static final int IMAGES_OFF = 2;

    // Device profile
    public final boolean lowRam;
    public final long totalMemMb;
    public final int screenMaxPx;
    public final int maxImageWidth;
    public final long maxImagePixels;
    public final int decodeConcurrency;
    public final int maxTabs;
    public final int cssMaxLength;
    public final int jsHeapWarnMb;
    public final boolean softwareRendering;

    // Network
    public final String userAgent;
    public final String acceptLanguage;
    public final boolean modernNet;
    public final boolean routeAssets;
    public final boolean saveData;
    public final boolean cssCompat;
    public final boolean polyfills;
    public final boolean cookies;

    // Content
    public final boolean javascript;
    public final int imageMode;
    public final boolean grayImages;
    public final int imageQuality;
    public final boolean adblock;
    public final boolean blockFonts;
    public final boolean embeds;
    public final boolean cookieBanners;
    public final boolean highContrast;
    public final boolean boldText;
    public final boolean unstick;
    public final boolean desktop;
    public final Set<String> jsOffSites;
    public final Set<String> adblockOffSites;
    public final String searchTemplate;
    public final String homeUrl;
    public final String hash;

    private static volatile Config current;

    public static Config get() {
        return current;
    }

    public static synchronized Config reload(Context c) {
        current = new Config(c.getApplicationContext());
        return current;
    }

    private static String systemUserAgent;

    private Config(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        totalMemMb = mi.totalMem / (1024 * 1024);
        boolean isLowRamDevice = am.isLowRamDevice();
        lowRam = isLowRamDevice || totalMemMb <= 640 || am.getMemoryClass() <= 32;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        screenMaxPx = Math.max(dm.widthPixels, dm.heightPixels);
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        // Images never need to be wider than the screen's long side; e-ink panels are 600-1448 px.
        maxImageWidth = Math.max(480, Math.min(screenMaxPx, 1600));
        maxImagePixels = (long) Math.max(shortSide, 480) * Math.max(screenMaxPx, 640) * (lowRam ? 2 : 4);
        decodeConcurrency = lowRam ? 1 : 2;
        cssMaxLength = lowRam ? 900_000 : 2_000_000;
        jsHeapWarnMb = totalMemMb <= 320 ? 48 : totalMemMb <= 640 ? 96 : 192;

        int tabs = Prefs.integer(Prefs.MAX_TABS, 0);
        maxTabs = tabs > 0 ? tabs : (lowRam ? 8 : 16);

        String render = Prefs.str(Prefs.RENDER, "auto");
        softwareRendering = render.equals("software") || (render.equals("auto") && (isLowRamDevice || totalMemMb <= 400));

        desktop = Prefs.bool(Prefs.DESKTOP, false);
        userAgent = buildUserAgent(c, desktop ? "desktop" : Prefs.str(Prefs.UA, "default"));
        acceptLanguage = acceptLanguage();
        modernNet = Prefs.bool(Prefs.MODERN_NET, true);
        routeAssets = Prefs.bool(Prefs.ROUTE_ASSETS, false);
        saveData = Prefs.bool(Prefs.SAVE_DATA, true);
        cssCompat = Prefs.bool(Prefs.CSS_COMPAT, true);
        polyfills = Prefs.bool(Prefs.POLYFILLS, true);
        cookies = Prefs.bool(Prefs.COOKIES, true);

        javascript = Prefs.bool(Prefs.JS, true);
        String img = Prefs.str(Prefs.IMAGES, "optimize");
        imageMode = img.equals("off") ? IMAGES_OFF : img.equals("full") ? IMAGES_FULL : IMAGES_OPTIMIZE;
        grayImages = Prefs.bool(Prefs.GRAY, true);
        imageQuality = Math.max(30, Math.min(95, Prefs.integer(Prefs.IMAGE_QUALITY, 70)));
        adblock = Prefs.bool(Prefs.ADBLOCK, true);
        blockFonts = Prefs.bool(Prefs.BLOCK_FONTS, false);
        embeds = Prefs.bool(Prefs.EMBEDS, true);
        cookieBanners = Prefs.bool(Prefs.COOKIE_BANNERS, true);
        highContrast = Prefs.bool(Prefs.CONTRAST, true);
        boldText = Prefs.bool(Prefs.BOLD_TEXT, false);
        unstick = Prefs.bool(Prefs.UNSTICK, false);
        jsOffSites = Prefs.set(Prefs.JS_OFF_SITES);
        adblockOffSites = Prefs.set(Prefs.ADBLOCK_OFF_SITES);
        searchTemplate = searchTemplate(Prefs.str(Prefs.SEARCH, "ddg_html"), Prefs.str(Prefs.SEARCH_CUSTOM, ""));
        homeUrl = Prefs.str(Prefs.HOME, "").trim();
        hash = Integer.toHexString((highContrast ? 1 : 0) | (cookieBanners ? 2 : 0) | (adblock ? 4 : 0)
                | (polyfills ? 8 : 0) | (unstick ? 16 : 0) | (boldText ? 32 : 0) | (embeds ? 64 : 0)
                | (imageMode << 8) | (jsHeapWarnMb << 12));
    }

    static double screenInches(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float xdpi = dm.xdpi > 0 ? dm.xdpi : dm.densityDpi, ydpi = dm.ydpi > 0 ? dm.ydpi : dm.densityDpi;
        double w = dm.widthPixels / xdpi, h = dm.heightPixels / ydpi;
        return Math.sqrt(w * w + h * h);
    }

    public boolean jsAllowedFor(String host) {
        if (!javascript) return false;
        return !jsOffSites.contains(host);
    }

    public boolean adblockFor(String host) {
        return adblock && !adblockOffSites.contains(host);
    }

    private static String acceptLanguage() {
        Locale l = Locale.getDefault();
        String lang = l.getLanguage();
        String country = l.getCountry();
        StringBuilder sb = new StringBuilder();
        if (!country.isEmpty()) sb.append(lang).append('-').append(country).append(',');
        sb.append(lang).append(";q=0.9");
        if (!lang.equals("en")) sb.append(",en-US;q=0.8,en;q=0.7");
        return sb.toString();
    }

    public static String searchTemplate(String engine, String custom) {
        switch (engine) {
            case "ddg_lite": return "https://lite.duckduckgo.com/lite/?q=%s";
            case "google": return "https://www.google.com/search?q=%s";
            case "bing": return "https://www.bing.com/search?q=%s";
            case "startpage": return "https://www.startpage.com/do/search?q=%s";
            case "coccoc": return "https://coccoc.com/search?query=%s";
            case "wikipedia": return "https://vi.m.wikipedia.org/w/index.php?search=%s";
            case "custom":
                if (custom != null && custom.contains("%s")) return custom.trim();
                return "https://html.duckduckgo.com/html/?q=%s";
            default: return "https://html.duckduckgo.com/html/?q=%s";
        }
    }

    static String buildUserAgent(Context c, String mode) {
        switch (mode) {
            case "mobile":
                return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Mobile Safari/537.36";
            case "desktop":
                return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Safari/537.36";
            case "opera_mini":
                return "Opera/9.80 (Android; Opera Mini/7.6.40234/191.249; U; en) Presto/2.12.423 Version/12.16";
            case "custom": {
                String ua = Prefs.str(Prefs.UA_CUSTOM, "").trim();
                if (!ua.isEmpty()) return ua;
                break;
            }
            default:
                break;
        }
        // Honest engine version so servers pick their legacy (ES5) bundles, minus the WebView marker that
        // makes some sites serve crippled "in-app browser" pages.
        if (systemUserAgent == null) {
            String ua;
            try {
                ua = WebSettings.getDefaultUserAgent(c);
            } catch (Throwable t) {
                ua = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/33.0.0.0 Mobile Safari/537.36";
            }
            ua = ua.replace("Version/4.0 ", "").replace("; wv)", ")");
            // 6-8" e-ink readers are often mdpi, so the WebView calls itself a tablet and sites send heavy desktop
            // layouts. Physically small screens get the "Mobile" token back.
            if (!ua.contains("Mobile") && screenInches(c) < 8.5) ua = ua.replace("Safari/", "Mobile Safari/");
            systemUserAgent = ua;
        }
        return systemUserAgent;
    }
}
