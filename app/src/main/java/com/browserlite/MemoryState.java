package com.browserlite;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

/**
 * How much RAM is free right now, in three bands. Pages and videos adapt to it when "optimize by free RAM" is on:
 * the same page loads lighter when the device is short of memory, and gets its full level back when there is room.
 * Readable from any thread; refreshed at most every couple of seconds (it is a binder call).
 */
public final class MemoryState {
    private MemoryState() {}

    public static final int ROOMY = 0, TIGHT = 1, CRITICAL = 2;

    private static volatile int band = ROOMY;
    private static volatile long availMb = -1;
    private static volatile long updatedAt;

    public static int band() {
        return band;
    }

    public static long availMb() {
        return availMb;
    }

    /** Refreshes when older than {@code maxAgeMs}; returns the current band. */
    public static int update(Context c, long maxAgeMs) {
        long now = SystemClock.uptimeMillis();
        if (availMb >= 0 && now - updatedAt < maxAgeMs) return band;
        try {
            ActivityManager am = (ActivityManager) c.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long avail = mi.availMem >> 20;
            long threshold = mi.threshold >> 20;
            availMb = avail;
            band = classify(avail, threshold, mi.lowMemory);
        } catch (RuntimeException e) {
            // keep the last value
        }
        updatedAt = now;
        return band;
    }

    /**
     * Free = MemFree + reclaimable cache, which is what a new page can really use. "Critical" is close to the point
     * where Android starts killing apps; "tight" leaves little room for a heavy page (a news front page can take
     * 40-60 MB on this engine).
     */
    static int classify(long availMb, long thresholdMb, boolean lowMemory) {
        if (lowMemory || availMb < Math.max(48, thresholdMb * 5 / 4)) return CRITICAL;
        if (availMb < Math.max(110, thresholdMb * 2)) return TIGHT;
        return ROOMY;
    }
}
