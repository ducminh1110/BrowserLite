package com.browserlite.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Chooses which stream to play and with which decoder. Stripped-down ROMs often lack HEVC/VP9/AV1 and some ship no
 * decoder at all, so a stream is only handed to the system player when the device lists a decoder for every codec
 * in it; otherwise the built-in (FFmpeg) decoder takes it, and when nothing can show the picture we fall back to
 * sound only. Pure Java so it can be unit tested.
 */
public final class StreamPicker {
    private StreamPicker() {}

    /** Decoders available to a player. */
    public static final class Caps {
        public boolean avcBaseline, avcMain, avcHigh, hevc, vp8, vp9, av1, mp4v, h263;
        /** A hardware (vendor) decoder exists for the codec, not only Android's slow software one. */
        public boolean hwAvc, hwVp9, hwHevc;
        public boolean aac, mp3, opus, vorbis;
        /** False when the device would not tell us (then we assume the common H.264 Baseline + AAC). */
        public boolean known = true;

        public static Caps assumed() {
            Caps c = new Caps();
            c.avcBaseline = true;
            c.aac = true;
            c.mp3 = true;
            c.known = false;
            return c;
        }

        /** What the bundled FFmpeg build decodes. */
        public static Caps builtin() {
            Caps c = new Caps();
            c.avcBaseline = c.avcMain = c.avcHigh = true;
            c.aac = c.mp3 = true;
            return c;
        }

        public boolean anyVideo() {
            return avcBaseline || avcMain || avcHigh || hevc || vp8 || vp9 || av1 || mp4v || h263;
        }
    }

    public static final class Choice {
        public final YouTube.Stream stream;
        public final boolean builtin;
        public final boolean audioOnly;
        /** Why the picture was dropped (no decoder), or null. */
        public final String reason;

        Choice(YouTube.Stream stream, boolean builtin, boolean audioOnly, String reason) {
            this.stream = stream;
            this.builtin = builtin;
            this.audioOnly = audioOnly;
            this.reason = reason;
        }
    }

    public static final String NO_VIDEO_DECODER = "no-video-decoder";

    static boolean isVideoCodec(String codec) {
        String c = codec.toLowerCase(Locale.US);
        return c.startsWith("avc") || c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("vp8")
                || c.startsWith("vp08") || c.startsWith("vp9") || c.startsWith("vp09") || c.startsWith("av01")
                || c.startsWith("mp4v") || c.startsWith("h263") || c.startsWith("s263") || c.startsWith("theora")
                || c.startsWith("dvh") || c.startsWith("dva");
    }

    /** Whether {@code caps} decodes {@code codec} (an RFC 6381 string such as avc1.42001E or mp4a.40.2). */
    public static boolean decodes(Caps caps, String codec) {
        String c = codec.toLowerCase(Locale.US);
        if (c.startsWith("avc1") || c.startsWith("avc3")) {
            int profile = 0x42;
            if (c.length() >= 7 && c.charAt(4) == '.') {
                try {
                    profile = Integer.parseInt(c.substring(5, 7), 16);
                } catch (NumberFormatException ignored) {
                    // keep baseline
                }
            }
            if (profile == 0x42) return caps.avcBaseline || caps.avcMain || caps.avcHigh; // constrained baseline
            if (profile == 0x4d) return caps.avcMain || caps.avcHigh;
            return caps.avcHigh;
        }
        if (c.startsWith("hev") || c.startsWith("hvc")) return caps.hevc;
        if (c.startsWith("vp8") || c.startsWith("vp08")) return caps.vp8;
        if (c.startsWith("vp9") || c.startsWith("vp09")) return caps.vp9;
        if (c.startsWith("av01")) return caps.av1;
        if (c.startsWith("mp4v")) return caps.mp4v;
        if (c.startsWith("h263") || c.startsWith("s263")) return caps.h263;
        if (c.startsWith("mp4a.40") || c.equals("mp4a") || c.equals("aac")) return caps.aac;
        if (c.startsWith("mp4a.6b") || c.startsWith("mp4a.69") || c.equals("mp3")) return caps.mp3;
        if (c.equals("opus")) return caps.opus;
        if (c.equals("vorbis")) return caps.vorbis;
        return false; // dolby vision, ac-3, flac...: unknown means no
    }

    /** Container the player must also understand: webm needs Android 5+ for opus/vp9, and FFmpeg here has no webm. */
    private static boolean container(Caps caps, boolean builtin, YouTube.Stream s) {
        if (s.mime.contains("webm")) return !builtin && (caps.vp9 || caps.vp8 || caps.opus || caps.vorbis);
        return true;
    }

    public static boolean supports(Caps caps, boolean builtin, YouTube.Stream s) {
        if (!container(caps, builtin, s)) return false;
        if (s.codecs.isEmpty()) {
            // Plain links without codec info: MP4/M4A/MP3/HLS almost always hold H.264 + AAC or MP3.
            boolean common = s.mime.isEmpty() || s.mime.contains("mp4") || s.mime.contains("mpeg")
                    || s.mime.contains("m4a") || s.mime.contains("3gp");
            if (!common) return !builtin; // something else: only the system player might know it
            return s.video ? caps.avcBaseline || caps.avcMain || caps.avcHigh : caps.aac || caps.mp3;
        }
        for (String codec : s.codecs) {
            if (!decodes(caps, codec)) return false;
        }
        return true;
    }

    /**
     * @param mode        "auto", "system" (device decoders only) or "builtin" (always FFmpeg)
     * @param sound       decode sound at all; without it a picture-only stream is preferred (lighter to fetch and
     *                    decode), and "sound only" is impossible
     * @param builtinOk   the bundled decoder loaded on this device
     * @param maxHeight   tallest picture worth decoding for this screen
     */
    public static Choice pick(List<YouTube.Stream> streams, Caps device, String mode, boolean wantAudioOnly,
            boolean sound, int maxHeight, boolean builtinOk) {
        boolean allowSystem = !"builtin".equals(mode) || !builtinOk;
        boolean allowBuiltin = builtinOk && !"system".equals(mode);
        Caps builtin = Caps.builtin();
        String reason = null;
        if (!wantAudioOnly || !sound) {
            List<YouTube.Stream> muxed = new ArrayList<>();
            List<YouTube.Stream> pictureOnly = new ArrayList<>();
            for (YouTube.Stream s : streams) {
                if (s.video && s.audio) muxed.add(s);
                else if (s.video) pictureOnly.add(s);
            }
            sortByHeight(muxed);
            sortByHeight(pictureOnly);
            // Muted: picture-only streams first (nothing to download or decode for sound), muxed as a fallback.
            List<List<YouTube.Stream>> lists = new ArrayList<>();
            if (!sound) lists.add(pictureOnly);
            lists.add(muxed);
            // Cheapest first: a hardware decoder, then the device's software H.264, then our FFmpeg H.264 (NEON),
            // and only then other software codecs (Android's VP9 software decoder crawls on weak CPUs).
            for (int tier = 0; tier < 4; tier++) {
                for (List<YouTube.Stream> list : lists) {
                    YouTube.Stream best = null;
                    boolean useBuiltin = tier == 2;
                    if (tier == 0 && allowSystem) best = best(list, hardwareOnly(device), false, maxHeight, false);
                    else if (tier == 1 && allowSystem) best = best(list, device, false, maxHeight, true);
                    else if (tier == 2 && allowBuiltin) best = best(list, builtin, true, maxHeight, true);
                    else if (tier == 3 && allowSystem) best = best(list, device, false, maxHeight, false);
                    if (best != null) return new Choice(best, useBuiltin, false, null);
                }
            }
            if (!sound) return null;
            if (!muxed.isEmpty()) reason = NO_VIDEO_DECODER;
        }
        List<YouTube.Stream> audio = new ArrayList<>();
        for (YouTube.Stream s : streams) if (s.audio && !s.video) audio.add(s);
        Collections.sort(audio, new Comparator<YouTube.Stream>() {
            @Override
            public int compare(YouTube.Stream a, YouTube.Stream b) {
                return audioRank(a) - audioRank(b);
            }
        });
        for (YouTube.Stream s : audio) {
            if (allowSystem && supports(device, false, s)) return new Choice(s, false, true, reason);
        }
        for (YouTube.Stream s : audio) {
            if (allowBuiltin && supports(builtin, true, s)) return new Choice(s, true, true, reason);
        }
        // No separate audio stream: play a muxed one without its picture.
        List<YouTube.Stream> muxed = new ArrayList<>();
        for (YouTube.Stream s : streams) if (s.video && s.audio) muxed.add(s);
        sortByHeight(muxed);
        for (YouTube.Stream s : muxed) {
            if (allowSystem && audioSupported(device, s)) return new Choice(s, false, true, reason);
            if (allowBuiltin && audioSupported(builtin, s) && container(builtin, true, s)) return new Choice(s, true, true, reason);
        }
        return null;
    }

    private static boolean audioSupported(Caps caps, YouTube.Stream s) {
        for (String codec : s.codecs) {
            if (!isVideoCodec(codec) && !decodes(caps, codec)) return false;
        }
        return true;
    }

    /**
     * Tallest supported stream within {@code maxHeight} (else the smallest one). H.264 wins over VP9/AV1: it is the
     * cheapest to decode and has hardware support on almost every device that has any.
     */
    /** The device's decoders minus its software-only ones. */
    static Caps hardwareOnly(Caps c) {
        Caps h = new Caps();
        h.avcBaseline = c.hwAvc && c.avcBaseline;
        h.avcMain = c.hwAvc && c.avcMain;
        h.avcHigh = c.hwAvc && c.avcHigh;
        h.vp9 = c.hwVp9 && c.vp9;
        h.hevc = c.hwHevc && c.hevc;
        h.aac = c.aac;
        h.mp3 = c.mp3;
        h.opus = c.opus;
        h.vorbis = c.vorbis;
        return h;
    }

    private static YouTube.Stream best(List<YouTube.Stream> sorted, Caps caps, boolean builtin, int maxHeight, boolean avcOnly) {
        for (int pass = 0; pass < 2; pass++) {
            if (avcOnly && pass == 1) break;
            YouTube.Stream pick = null;
            for (YouTube.Stream s : sorted) {
                if (pass == 0 && !isAvc(s)) continue;
                if (!supports(caps, builtin, s)) continue;
                if (pick == null || s.height <= maxHeight) pick = s;
            }
            if (pick != null) return pick;
        }
        return null;
    }

    private static boolean isAvc(YouTube.Stream s) {
        for (String c : s.codecs) {
            String l = c.toLowerCase(Locale.US);
            if (l.startsWith("avc")) return true;
        }
        return s.codecs.isEmpty();
    }

    private static void sortByHeight(List<YouTube.Stream> list) {
        Collections.sort(list, new Comparator<YouTube.Stream>() {
            @Override
            public int compare(YouTube.Stream a, YouTube.Stream b) {
                return a.height - b.height;
            }
        });
    }

    /** AAC-LC first (decodes everywhere), then HE-AAC, MP3, Opus, anything else. */
    private static int audioRank(YouTube.Stream s) {
        String c = s.codecs.isEmpty() ? "" : s.codecs.get(0).toLowerCase(Locale.US);
        if (c.equals("mp4a.40.2")) return 0;
        if (c.startsWith("mp4a.40")) return 1;
        if (c.startsWith("mp4a")) return 2;
        if (c.equals("opus")) return 3;
        return 4;
    }
}
