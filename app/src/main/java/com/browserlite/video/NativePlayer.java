package com.browserlite.video;

import android.util.Log;
import android.view.Surface;

/** JNI bridge to libblplayer.so (FFmpeg H.264/AAC/MP3 decoder bundled for devices that have none). */
public final class NativePlayer {
    static final int OPENING = 0, READY = 1, PLAYING = 2, PAUSED = 3, BUFFERING = 4, ENDED = 5, ERROR = 6;

    private static int loaded; // 0 unknown, 1 ok, -1 failed

    private NativePlayer() {}

    /** Loads the decoder on first use only: it costs nothing until a video actually needs it. */
    static synchronized boolean available() {
        if (loaded == 0) {
            try {
                System.loadLibrary("blffmpeg"); // KitKat's linker does not resolve dependencies from the APK itself
                System.loadLibrary("blplayer");
                loaded = 1;
            } catch (Throwable t) {
                Log.w("NativePlayer", "built-in decoder unavailable", t);
                loaded = -1;
            }
        }
        return loaded > 0;
    }

    /** For the settings screen: whether this CPU can run the bundled decoder (loads it once). */
    public static boolean availableForSettings() {
        return available();
    }

    /** Blocks while the stream is probed (network). Always returns a handle; check {@link #state}. */
    /** {@code threads} 0 = one per core (max 4); {@code queueKb}: demuxed data kept ahead of the decoders. */
    static native long open(String url, boolean gray, int fpsCap, boolean video, boolean audio, int threads, int queueKb);

    static native String error(long h);

    /** width, height, durationMs, hasVideo, hasAudio, sampleRate, channels, videoUnsupported */
    static native int[] info(long h);

    static native void start(long h);

    static native void setSurface(long h, Surface s);

    /** Interleaved 16-bit samples written, 0 = none ready, -1 = ended, -2 = flush the AudioTrack (after a seek). */
    static native int fillAudio(long h, short[] buf, long headFrames);

    static native void pause(long h, boolean paused);

    /** Audio can't be played here: discard it and run video on the wall clock. */
    static native void dropAudio(long h);

    static native void seek(long h, long ms);

    static native long position(long h);

    static native int state(long h);

    /** Scene changes shown so far (big jumps in the picture's brightness layout): when to clear e-ink ghosting. */
    static native int sceneCuts(long h);

    static native void close(long h);
}
