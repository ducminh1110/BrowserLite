package com.browserlite;

/**
 * What BrowserLite does to one page: the "optimization level" slider picks one of the presets below, per site or
 * for every site. Level 0 shows the page as its author made it (only the compatibility layers that make it render
 * at all stay on); each step strips a bit more, up to plain text.
 */
public final class Profile {
    public static final int CUSTOM = -1;
    public static final int ORIGINAL = 0, NO_ADS = 1, BALANCED = 2, EINK = 3, LIGHT = 4, TEXT = 5;
    public static final int LEVELS = 6;

    public final int level;
    public final boolean adblock, cookieBanners, embeds, still, contrast, bold, unstick, blockFonts, javascript, gray;
    public final int imageMode;
    public final int imageQuality;

    Profile(int level, boolean adblock, boolean cookieBanners, boolean embeds, boolean still, boolean contrast,
            boolean bold, boolean unstick, boolean blockFonts, boolean javascript, boolean gray, int imageMode,
            int imageQuality) {
        this.level = level;
        this.adblock = adblock;
        this.cookieBanners = cookieBanners;
        this.embeds = embeds;
        this.still = still;
        this.contrast = contrast;
        this.bold = bold;
        this.unstick = unstick;
        this.blockFonts = blockFonts;
        this.javascript = javascript;
        this.gray = gray;
        this.imageMode = imageMode;
        this.imageQuality = Math.max(30, Math.min(95, imageQuality));
    }

    /** The preset for a slider position. {@code bold} is a reading preference and never part of a level. */
    public static Profile preset(int level, boolean bold) {
        switch (level) {
            case ORIGINAL:
                return new Profile(level, false, false, false, false, false, bold, false, false, true, false,
                        Config.IMAGES_FULL, 85);
            case NO_ADS:
                return new Profile(level, true, true, false, false, false, bold, false, false, true, false,
                        Config.IMAGES_FULL, 85);
            case BALANCED:
                return new Profile(level, true, true, true, true, false, bold, false, false, true, false,
                        Config.IMAGES_OPTIMIZE, 75);
            case LIGHT:
                return new Profile(level, true, true, true, true, true, bold, true, true, true, true,
                        Config.IMAGES_OPTIMIZE, 50);
            case TEXT:
                return new Profile(level, true, true, true, true, true, bold, true, true, false, true,
                        Config.IMAGES_OFF, 50);
            case EINK:
            default:
                return new Profile(EINK, true, true, true, true, true, bold, false, false, true, true,
                        Config.IMAGES_OPTIMIZE, 70);
        }
    }

    /** The preset these settings equal, or {@link #CUSTOM}. */
    static int levelOf(boolean adblock, boolean cookieBanners, boolean embeds, boolean still, boolean contrast,
            boolean unstick, boolean blockFonts, boolean javascript, boolean gray, int imageMode, int imageQuality) {
        for (int l = 0; l < LEVELS; l++) {
            Profile p = preset(l, false);
            if (p.adblock == adblock && p.cookieBanners == cookieBanners && p.embeds == embeds && p.still == still
                    && p.contrast == contrast && p.unstick == unstick && p.blockFonts == blockFonts
                    && p.javascript == javascript && p.imageMode == imageMode
                    && (imageMode == Config.IMAGES_OFF || (p.gray == gray && p.imageQuality == imageQuality))) {
                return l;
            }
        }
        return CUSTOM;
    }

    /** Copy with per-site switches and the motion mode applied. */
    Profile with(boolean js, boolean ads, boolean motionAllowed) {
        if (js == javascript && ads == adblock && !(motionAllowed && still)) return this;
        return new Profile(js == javascript && ads == adblock ? level : CUSTOM, ads, cookieBanners, embeds,
                still && !motionAllowed, contrast, bold, unstick, blockFonts, js, gray, imageMode, imageQuality);
    }

    /**
     * Lighter variant for when free RAM runs short ({@link MemoryState}). Tight: heavy embeds wait for a tap and
     * images get a smaller budget. Critical: also no web fonts, ads, sticky layers, and images shrunk to grayscale.
     * JavaScript and the reading look are left alone.
     */
    Profile adapt(int band) {
        if (band == MemoryState.ROOMY) return this;
        boolean critical = band == MemoryState.CRITICAL;
        int mode = critical && imageMode == Config.IMAGES_FULL ? Config.IMAGES_OPTIMIZE : imageMode;
        int quality = Math.min(imageQuality, critical ? 45 : 60);
        boolean ads = adblock || critical, cookies = cookieBanners || critical, sticky = unstick || critical;
        boolean fonts = blockFonts || critical, g = gray || critical;
        if (embeds && fonts == blockFonts && ads == adblock && cookies == cookieBanners && sticky == unstick
                && g == gray && mode == imageMode && quality == imageQuality) {
            return this;
        }
        return new Profile(level, ads, cookies, true, still, contrast, bold, sticky, fonts, javascript, g, mode, quality);
    }

    /** Compact encoding used in the URLs of the injected script and stylesheet. */
    public int bits() {
        return (adblock ? 1 : 0) | (cookieBanners ? 2 : 0) | (embeds ? 4 : 0) | (still ? 8 : 0) | (contrast ? 16 : 0)
                | (bold ? 32 : 0) | (unstick ? 64 : 0) | (blockFonts ? 128 : 0) | (javascript ? 256 : 0)
                | (gray ? 512 : 0) | (imageMode << 10) | (imageQuality << 12);
    }

    public static Profile fromBits(int b) {
        return new Profile(CUSTOM, (b & 1) != 0, (b & 2) != 0, (b & 4) != 0, (b & 8) != 0, (b & 16) != 0,
                (b & 32) != 0, (b & 64) != 0, (b & 128) != 0, (b & 256) != 0, (b & 512) != 0, (b >> 10) & 3,
                (b >> 12) & 127);
    }
}
