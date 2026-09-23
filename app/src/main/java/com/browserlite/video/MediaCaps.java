package com.browserlite.video;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

import com.browserlite.net.StreamPicker;

import java.util.Locale;

/** Which decoders this device's ROM really has (MediaCodecList is what MediaPlayer uses on Android 4.1+). */
public final class MediaCaps {
    private MediaCaps() {}

    private static StreamPicker.Caps device;

    public static synchronized StreamPicker.Caps device() {
        if (device != null) return device;
        StreamPicker.Caps c = new StreamPicker.Caps();
        try {
            int n = MediaCodecList.getCodecCount();
            for (int i = 0; i < n; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder()) continue;
                boolean hw = isHardware(info.getName());
                for (String type : info.getSupportedTypes()) {
                    add(c, info, type);
                    String t = type.toLowerCase(Locale.US);
                    if (hw && t.equals("video/avc")) c.hwAvc = true;
                    if (hw && t.equals("video/x-vnd.on2.vp9")) c.hwVp9 = true;
                    if (hw && t.equals("video/hevc")) c.hwHevc = true;
                }
            }
        } catch (Throwable t) {
            // Some stripped ROMs throw here (missing media_codecs.xml): assume the Android baseline.
            Log.w("MediaCaps", "codec list unavailable", t);
            c = StreamPicker.Caps.assumed();
        }
        device = c;
        return c;
    }

    /** Android's own decoders are software; vendors' (OMX.qcom, OMX.MTK, OMX.rk...) run on the video block. */
    static boolean isHardware(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.endsWith(".sw")
                || n.contains("ffmpeg") || n.contains("avcodec") || n.startsWith("omx.pv"));
    }

    private static void add(StreamPicker.Caps c, MediaCodecInfo info, String type) {
        switch (type.toLowerCase(Locale.US)) {
            case "video/avc": {
                boolean any = false;
                try {
                    for (MediaCodecInfo.CodecProfileLevel pl : info.getCapabilitiesForType(type).profileLevels) {
                        any = true;
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) c.avcBaseline = true;
                        else if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain) c.avcMain = true;
                        else if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) c.avcHigh = true;
                    }
                } catch (Throwable ignored) {
                    // capabilities unreadable
                }
                if (!any || c.avcMain || c.avcHigh) c.avcBaseline = true;
                break;
            }
            case "video/hevc": c.hevc = true; break;
            case "video/x-vnd.on2.vp8": c.vp8 = true; break;
            case "video/x-vnd.on2.vp9": c.vp9 = true; break;
            case "video/av01": c.av1 = true; break;
            case "video/mp4v-es": c.mp4v = true; break;
            case "video/3gpp": c.h263 = true; break;
            case "audio/mp4a-latm": c.aac = true; break;
            case "audio/mpeg": c.mp3 = true; break;
            case "audio/opus": c.opus = true; break;
            case "audio/vorbis": c.vorbis = true; break;
            default: break;
        }
    }

    /** One line for the settings screen, e.g. "H.264 ✓ · HEVC ✗ · VP9 ✗ · AV1 ✗ · AAC ✓ · MP3 ✓". */
    public static String describe() {
        StreamPicker.Caps c = device();
        String avc = c.avcHigh ? "High" : c.avcMain ? "Main" : c.avcBaseline ? "Baseline" : null;
        return "H.264 " + (avc != null ? "✓ (" + avc + (c.hwAvc ? ", HW" : ", SW") + ")" : "✗") + " · HEVC " + mark(c.hevc) + " · VP9 " + mark(c.vp9)
                + " · AV1 " + mark(c.av1) + " · AAC " + mark(c.aac) + " · MP3 " + mark(c.mp3)
                + (c.known ? "" : " (?)");
    }

    private static String mark(boolean b) {
        return b ? "✓" : "✗";
    }
}
