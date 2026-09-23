package com.browserlite;

import android.app.AlertDialog;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import com.browserlite.data.Db;
import com.browserlite.net.NetEngine;
import com.browserlite.ui.Ui;

import java.io.File;

/** Framework PreferenceActivity: no support library, nothing extra resident in RAM. */
public final class SettingsActivity extends PreferenceActivity {
    static boolean localeChanged;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        BrowserApp.applyLocale(this);
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings);
        getListView().setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        getListView().setScrollbarFadingEnabled(false);

        findPreference(Prefs.LANG).setOnPreferenceChangeListener((p, v) -> {
            localeChanged = true;
            Prefs.put(Prefs.LANG, String.valueOf(v));
            BrowserApp.applyLocale(getApplicationContext());
            recreate();
            return true;
        });
        findPreference("clear_cache").setOnPreferenceClickListener(p -> {
            try {
                WebView w = new WebView(this);
                w.clearCache(true);
                w.destroy();
            } catch (Exception ignored) {
                // ignore
            }
            new Thread(() -> {
                try {
                    okhttp3.OkHttpClient c = NetEngine.clientOrNull();
                    if (c != null && c.cache() != null) c.cache().evictAll();
                    else deleteRecursive(new File(getCacheDir(), "http"));
                } catch (Exception ignored) {
                    // ignore
                }
            }).start();
            Ui.toast(this, getString(R.string.toast_cleared));
            return true;
        });
        findPreference("clear_cookies").setOnPreferenceClickListener(p -> {
            try {
                CookieManager.getInstance().removeAllCookie();
                WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {
                // ignore
            }
            Ui.toast(this, getString(R.string.toast_cleared));
            return true;
        });
        findPreference("clear_history").setOnPreferenceClickListener(p -> {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.confirm_clear_history)
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        Db.get(this).clearHistory();
                        Ui.toast(this, getString(R.string.toast_cleared));
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });
        Preference level = findPreference("level");
        Config c0 = Config.get();
        level.setSummary(LevelDialog.name(this, c0.globalLevel));
        level.setOnPreferenceClickListener(p -> {
            LevelDialog.show(this, null, () -> {
                BrowserApp.get().reloadConfig();
                recreate(); // the individual switches below changed with the slider
            });
            return true;
        });
        Preference codecs = findPreference("codecs");
        codecs.setSummary(com.browserlite.video.MediaCaps.describe() + "\n" + getString(R.string.codecs_builtin,
                getString(com.browserlite.video.NativePlayer.availableForSettings()
                        ? R.string.codecs_builtin_ok : R.string.codecs_builtin_missing)));
        Preference about = findPreference("about");
        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // ignore
        }
        about.setTitle(getString(R.string.pref_about, version));
        Config cfg = Config.get();
        String engine = "Chromium";
        try {
            String ua = android.webkit.WebSettings.getDefaultUserAgent(this);
            int i = ua.indexOf("Chrome/");
            if (i >= 0) engine = "Chromium " + ua.substring(i + 7).split("[ .]")[0];
        } catch (Exception ignored) {
            // ignore
        }
        about.setSummary(getString(R.string.pref_about_sum, engine,
                NetEngine.isModernTls() ? "BoringSSL / TLS 1.3" : "Android / TLS 1.2",
                (int) cfg.totalMemMb, cfg.lowRam ? getString(R.string.low_ram_mode) : ""));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }
}
