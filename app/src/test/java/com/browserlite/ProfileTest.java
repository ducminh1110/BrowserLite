package com.browserlite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProfileTest {
    @Test public void presetsRoundTripAndAreDistinct() {
        for (int l = 0; l < Profile.LEVELS; l++) {
            Profile p = Profile.preset(l, false);
            assertEquals(l, p.level);
            assertEquals(p.bits(), Profile.fromBits(p.bits()).bits());
            Profile q = Profile.fromBits(p.bits());
            assertEquals(l, Profile.levelOf(q.adblock, q.cookieBanners, q.embeds, q.still, q.contrast, q.unstick,
                    q.blockFonts, q.javascript, q.gray, q.imageMode, q.imageQuality));
        }
    }

    @Test public void originalStripsNothing() {
        Profile p = Profile.preset(Profile.ORIGINAL, false);
        assertFalse(p.adblock || p.cookieBanners || p.embeds || p.still || p.contrast || p.gray || p.blockFonts);
        assertTrue(p.javascript);
        assertEquals(Config.IMAGES_FULL, p.imageMode);
        Profile t = Profile.preset(Profile.TEXT, false);
        assertFalse(t.javascript);
        assertEquals(Config.IMAGES_OFF, t.imageMode);
    }

    @Test public void scrollModeKeepsMotion() {
        Profile e = Profile.preset(Profile.EINK, false);
        assertTrue(e.still);
        assertFalse(e.with(true, true, true).still);
        assertSame(e, e.with(true, true, false));
    }

    @Test public void freeRamAdaptation() {
        Profile o = Profile.preset(Profile.ORIGINAL, false);
        assertSame(o, o.adapt(MemoryState.ROOMY));
        Profile tight = o.adapt(MemoryState.TIGHT);
        assertTrue(tight.embeds);
        assertFalse("tight RAM keeps web fonts (icon fonts)", tight.blockFonts);
        assertFalse(tight.adblock);
        Profile critical = o.adapt(MemoryState.CRITICAL);
        assertTrue(critical.blockFonts && critical.adblock && critical.gray && critical.unstick);
        assertEquals(Config.IMAGES_OPTIMIZE, critical.imageMode);
        assertTrue(critical.javascript);
        Profile text = Profile.preset(Profile.TEXT, false);
        assertEquals(Config.IMAGES_OFF, text.adapt(MemoryState.CRITICAL).imageMode);
    }

    @Test public void memoryBands() {
        assertEquals(MemoryState.ROOMY, MemoryState.classify(160, 24, false));
        assertEquals(MemoryState.TIGHT, MemoryState.classify(90, 24, false));
        assertEquals(MemoryState.CRITICAL, MemoryState.classify(40, 24, false));
        assertEquals(MemoryState.CRITICAL, MemoryState.classify(200, 24, true));
        // thresholds scale with the device's own low-memory line
        assertEquals(MemoryState.TIGHT, MemoryState.classify(120, 64, false));
    }
}
