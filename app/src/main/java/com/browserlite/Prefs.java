package com.browserlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Set;

/** Typed access to settings. Keys match res/xml/settings.xml. */
public final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp;

    public static void init(Context c) {
        sp = PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }

    public static SharedPreferences sp() {
        return sp;
    }

    public static boolean bool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public static String str(String key, String def) {
        String v = sp.getString(key, def);
        return v == null ? def : v;
    }

    public static int integer(String key, int def) {
        try {
            return Integer.parseInt(str(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void put(String key, boolean v) {
        sp.edit().putBoolean(key, v).apply();
    }

    public static void put(String key, String v) {
        sp.edit().putString(key, v).apply();
    }

    public static void putInt(String key, int v) {
        sp.edit().putInt(key, v).apply();
    }

    public static int rawInt(String key, int def) {
        try {
            return sp.getInt(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static Set<String> set(String key) {
        Set<String> s = sp.getStringSet(key, null);
        return s == null ? new HashSet<String>() : new HashSet<>(s);
    }

    public static boolean inSet(String key, String value) {
        Set<String> s = sp.getStringSet(key, null);
        return s != null && s.contains(value);
    }

    public static void toggleInSet(String key, String value, boolean present) {
        Set<String> s = set(key);
        if (present) s.add(value);
        else s.remove(value);
        sp.edit().putStringSet(key, s).apply();
    }

    // Keys
    public static final String HOME = "home_url";
    public static final String SEARCH = "search_engine";
    public static final String SEARCH_CUSTOM = "search_custom";
    public static final String UA = "ua_mode";
    public static final String UA_CUSTOM = "ua_custom";
    public static final String JS = "js";
    public static final String IMAGES = "images";
    public static final String GRAY = "gray_images";
    public static final String IMAGE_QUALITY = "image_quality";
    public static final String ADBLOCK = "adblock";
    public static final String BLOCK_FONTS = "block_fonts";
    public static final String EMBEDS = "embeds";
    public static final String COOKIE_BANNERS = "cookie_banners";
    public static final String CONTRAST = "high_contrast";
    public static final String BOLD_TEXT = "bold_text";
    public static final String TEXT_ZOOM = "text_zoom_pct";
    public static final String AUTOSIZE = "autosize";
    public static final String DESKTOP = "desktop";
    public static final String VOLUME_KEYS = "volume_keys";
    public static final String SWIPE_PAGES = "swipe_pages";
    public static final String TAP_ZONES = "tap_zones";
    public static final String OVERLAP = "page_overlap";
    public static final String REFRESH_EVERY = "refresh_every";
    public static final String BOTTOM_BAR = "bottom_bar";
    public static final String CSS_COMPAT = "css_compat";
    public static final String POLYFILLS = "polyfills";
    public static final String MODERN_NET = "modern_net";
    public static final String ROUTE_ASSETS = "route_assets";
    public static final String SAVE_DATA = "save_data";
    public static final String RENDER = "render_mode";
    public static final String MAX_TABS = "max_tabs";
    public static final String RESTORE_TABS = "restore_tabs";
    public static final String COOKIES = "cookies";
    public static final String LANG = "lang";
    public static final String UNSTICK = "unstick";
    public static final String MEMORY_GUARD = "memory_guard";
    public static final String JS_OFF_SITES = "js_off_sites";
    public static final String ADBLOCK_OFF_SITES = "adblock_off_sites";
    public static final String USER_BLOCKLIST = "user_blocklist";
    public static final String STILL = "no_motion";
    public static final String SCROLL_MODE = "scroll_mode";
    public static final String SITE_LEVELS = "site_levels";
    public static final String VIDEO_MODE = "video_mode";
    public static final String VIDEO_AUDIO = "video_audio_default";
    public static final String VIDEO_DECODER = "video_decoder";
    public static final String VIDEO_GRAY = "video_gray";
    public static final String VIDEO_FPS = "video_fps";
    public static final String VIDEO_SOUND = "video_sound";
    public static final String VIDEO_HEIGHT = "video_height";
    public static final String AUTO_RAM = "auto_ram";
    public static final String YT_FALLBACK = "yt_fallback";
}
