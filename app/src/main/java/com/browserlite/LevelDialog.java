package com.browserlite;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.browserlite.net.UrlUtil;
import com.browserlite.ui.Steps;
import com.browserlite.ui.Ui;

import java.util.HashSet;
import java.util.Set;

/**
 * The optimization slider: from "Original" (the page as its author made it) to "Text only", for every site or just
 * the current one. Shows exactly what each position turns on, and what this device would do best with.
 */
public final class LevelDialog {
    private LevelDialog() {}

    public static String name(Activity a, int level) {
        String[] names = a.getResources().getStringArray(R.array.level_names);
        return level >= 0 && level < names.length ? names[level] : a.getString(R.string.level_custom);
    }

    /** Level this device should start from: low-RAM e-ink readers need the e-ink preset, others can go lighter. */
    public static int recommended(Config cfg) {
        return cfg.totalMemMb <= 640 ? Profile.EINK : Profile.BALANCED;
    }

    public static void show(final Activity a, final String host, final Runnable applied) {
        final Config cfg = Config.get();
        final boolean canSite = host != null && !host.isEmpty();
        final int siteLevel = canSite ? cfg.siteLevel(host) : Profile.CUSTOM;
        final boolean[] siteScope = {canSite && siteLevel != Profile.CUSTOM};
        int start = siteScope[0] ? siteLevel : cfg.globalLevel;
        final int[] level = {start == Profile.CUSTOM ? recommended(cfg) : start};

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(a, a.getString(R.string.level_title), 19, true);
        title.setPadding(0, 0, 0, Ui.dp(a, 8));
        box.addView(title);

        final TextView[] scopeButtons = new TextView[2];
        if (canSite) {
            LinearLayout scope = new LinearLayout(a);
            scope.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(0, 0, Ui.dp(a, 6), Ui.dp(a, 6));
            String site = UrlUtil.siteOf(host);
            scopeButtons[0] = Ui.button(a, a.getString(R.string.level_scope_all), !siteScope[0], null);
            scopeButtons[1] = Ui.button(a, a.getString(R.string.level_scope_site, site), siteScope[0], null);
            scopeButtons[1].setSingleLine(true);
            scopeButtons[1].setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            scope.addView(scopeButtons[0], lp);
            scope.addView(scopeButtons[1], new LinearLayout.LayoutParams(lp));
            box.addView(scope);
        }

        final Steps steps = new Steps(a, a.getResources().getStringArray(R.array.level_short), level[0]);
        box.addView(steps, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView heading = Ui.text(a, "", 18, true);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(heading);
        final TextView desc = Ui.text(a, "", 15, false);
        desc.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 6));
        box.addView(desc);
        final TextView features = Ui.text(a, "", 15, false);
        features.setLineSpacing(0, 1.15f);
        box.addView(features);
        TextView hint = Ui.text(a, a.getString(R.string.level_recommended, (int) cfg.totalMemMb,
                name(a, recommended(cfg))) + "\n" + a.getString(R.string.level_smart), 13, false);
        hint.setTextColor(0xFF333333);
        hint.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        box.addView(hint);
        if (cfg.autoRam) {
            int band = MemoryState.update(a, 0);
            int state = band == MemoryState.CRITICAL ? R.string.ram_critical : band == MemoryState.TIGHT ? R.string.ram_tight
                    : R.string.ram_roomy;
            TextView ram = Ui.text(a, a.getString(R.string.level_ram_state, (int) MemoryState.availMb(), a.getString(state)), 13, true);
            ram.setPadding(0, 0, 0, Ui.dp(a, 8));
            box.addView(ram);
        }
        if (cfg.scrollMode) {
            TextView motion = Ui.text(a, a.getString(R.string.level_scroll_note), 13, false);
            motion.setTextColor(0xFF333333);
            box.addView(motion);
        }

        final Runnable refresh = () -> {
            heading.setText((level[0]) + " · " + name(a, level[0]));
            desc.setText(a.getResources().getStringArray(R.array.level_desc)[level[0]]);
            features.setText(featureList(a, Profile.preset(level[0], false)));
        };
        steps.setOnChange(i -> {
            level[0] = i;
            refresh.run();
        });
        refresh.run();

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, Ui.dp(a, 8), 0, 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        blp.setMargins(0, 0, Ui.dp(a, 6), 0);
        final Dialog[] dialog = new Dialog[1];
        buttons.addView(Ui.button(a, a.getString(R.string.cancel), false, v -> dialog[0].dismiss()), blp);
        if (canSite && (siteLevel != Profile.CUSTOM || cfg.jsOffSites.contains(host) || cfg.adblockOffSites.contains(host))) {
            buttons.addView(Ui.button(a, a.getString(R.string.level_reset_site), false, v -> {
                resetSite(host);
                dialog[0].dismiss();
                applied.run();
            }), new LinearLayout.LayoutParams(blp));
        }
        buttons.addView(Ui.button(a, a.getString(R.string.level_apply), true, v -> {
            if (siteScope[0]) setSiteLevel(host, level[0]);
            else applyGlobal(level[0]);
            dialog[0].dismiss();
            applied.run();
        }), new LinearLayout.LayoutParams(blp));
        box.addView(buttons);

        if (canSite) {
            scopeButtons[0].setOnClickListener(v -> {
                siteScope[0] = false;
                restyle(a, scopeButtons, false);
            });
            scopeButtons[1].setOnClickListener(v -> {
                siteScope[0] = true;
                restyle(a, scopeButtons, true);
            });
        }
        ScrollView scroll = new ScrollView(a);
        scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scroll.addView(box);
        dialog[0] = Ui.panel(a, scroll);
        dialog[0].show();
    }

    private static void restyle(Activity a, TextView[] b, boolean site) {
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == site;
            TextView fresh = Ui.button(a, "", on, null);
            b[i].setBackgroundDrawable(fresh.getBackground());
            b[i].setTextColor(on ? 0xFFFFFFFF : 0xFF000000);
        }
    }

    static String featureList(Activity a, Profile p) {
        StringBuilder sb = new StringBuilder();
        line(sb, p.adblock, a.getString(R.string.feat_ads));
        line(sb, p.cookieBanners, a.getString(R.string.feat_cookies));
        line(sb, p.embeds, a.getString(R.string.feat_embeds));
        String images = p.imageMode == Config.IMAGES_OFF ? a.getString(R.string.feat_images_off)
                : p.imageMode == Config.IMAGES_FULL ? a.getString(R.string.feat_images_full)
                : a.getString(p.gray ? R.string.feat_images_gray : R.string.feat_images_small, p.imageQuality);
        sb.append("• ").append(images).append('\n');
        line(sb, p.still, a.getString(R.string.feat_still));
        line(sb, p.contrast, a.getString(R.string.feat_contrast));
        line(sb, p.blockFonts, a.getString(R.string.feat_fonts));
        line(sb, p.unstick, a.getString(R.string.feat_unstick));
        line(sb, p.javascript, a.getString(R.string.feat_js));
        return sb.toString().trim();
    }

    private static void line(StringBuilder sb, boolean on, String label) {
        sb.append(on ? "✓ " : "✗ ").append(label).append('\n');
    }

    /** Writes a preset into the individual settings, so the Settings screen shows what the slider did. */
    public static void applyGlobal(int level) {
        Profile p = Profile.preset(level, false);
        SharedPreferences.Editor e = Prefs.sp().edit();
        e.putBoolean(Prefs.ADBLOCK, p.adblock);
        e.putBoolean(Prefs.COOKIE_BANNERS, p.cookieBanners);
        e.putBoolean(Prefs.EMBEDS, p.embeds);
        e.putString(Prefs.IMAGES, p.imageMode == Config.IMAGES_OFF ? "off" : p.imageMode == Config.IMAGES_FULL ? "full" : "optimize");
        e.putBoolean(Prefs.GRAY, p.gray);
        e.putString(Prefs.IMAGE_QUALITY, String.valueOf(p.imageQuality));
        e.putBoolean(Prefs.STILL, p.still);
        e.putBoolean(Prefs.CONTRAST, p.contrast);
        e.putBoolean(Prefs.BLOCK_FONTS, p.blockFonts);
        e.putBoolean(Prefs.UNSTICK, p.unstick);
        e.putBoolean(Prefs.JS, p.javascript);
        e.apply();
    }

    public static void setSiteLevel(String host, int level) {
        String site = UrlUtil.siteOf(host);
        Set<String> entries = Prefs.set(Prefs.SITE_LEVELS);
        Set<String> out = new HashSet<>();
        for (String s : entries) if (!s.startsWith(site + "=")) out.add(s);
        if (level >= 0) out.add(site + "=" + level);
        Prefs.sp().edit().putStringSet(Prefs.SITE_LEVELS, out).apply();
    }

    /** Forget everything customised for this site: its level and the per-site JavaScript / ad-block switches. */
    public static void resetSite(String host) {
        setSiteLevel(host, -1);
        Prefs.toggleInSet(Prefs.JS_OFF_SITES, host, false);
        Prefs.toggleInSet(Prefs.ADBLOCK_OFF_SITES, host, false);
    }
}
