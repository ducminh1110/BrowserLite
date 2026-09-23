package com.browserlite.net;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import com.browserlite.Config;
import com.browserlite.Profile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.concurrent.Semaphore;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Shrinks images before the WebView sees them.
 *
 * <p>The WebView keeps images decoded at full size: one 4000x3000 photo is 48 MB of RAM, which alone can
 * kill a 256 MB device. Scaling to the screen, flattening to grayscale for e-ink and freezing animated GIFs
 * (which would otherwise force constant e-ink refreshes) keeps pages light. Decodes are serialised through a
 * semaphore so our own Java heap stays bounded.
 */
public final class ImageOptimizer implements LazyStream.Transformer {
    private static final int MAX_INPUT = 8 * 1024 * 1024;
    private static Semaphore gate;

    private final boolean gray, freezeGif;
    private final int quality, maxWidth;
    private final long maxPixels;

    /**
     * @param guardOnly keep the image as the author made it (colour, animation, quality) and only shrink images far
     *                  bigger than the screen, which would otherwise cost tens of MB each once decoded.
     */
    public ImageOptimizer(Config cfg, Profile p, boolean guardOnly) {
        gray = !guardOnly && p.gray;
        freezeGif = !guardOnly && p.still;
        quality = guardOnly ? 85 : p.imageQuality;
        int band = cfg.autoRam ? com.browserlite.MemoryState.band() : com.browserlite.MemoryState.ROOMY;
        // Short of free RAM: every decoded image costs width x height x 4 bytes inside the WebView.
        int div = band == com.browserlite.MemoryState.CRITICAL ? 4 : band == com.browserlite.MemoryState.TIGHT ? 2 : 1;
        maxWidth = (guardOnly ? cfg.maxImageWidth * 2 : cfg.maxImageWidth) * (div == 4 ? 3 : 4) / 4;
        maxPixels = (guardOnly ? cfg.maxImagePixels * 3 : cfg.maxImagePixels) / div;
        synchronized (ImageOptimizer.class) {
            if (gate == null) gate = new Semaphore(Math.max(1, cfg.decodeConcurrency));
        }
    }

    @Override
    public InputStream apply(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) return new ByteArrayInputStream(new byte[0]);
        String ct = UrlUtil.mimeOf(response.header("Content-Type"));
        if (!response.isSuccessful() || ct.contains("svg") || ct.startsWith("text/") || ct.contains("json")) {
            return body.byteStream();
        }
        long declared = body.contentLength();
        if (declared > MAX_INPUT) return body.byteStream();
        InputStream in = body.byteStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(declared > 0 ? (int) declared : 32 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_INPUT) {
                // Too big to hold: hand over what we have plus the rest of the stream untouched.
                return new SequenceInputStream(new ByteArrayInputStream(bos.toByteArray()), in);
            }
        }
        in.close();
        byte[] data = bos.toByteArray();
        bos = null;
        byte[] out = null;
        boolean acquired = false;
        try {
            gate.acquire();
            acquired = true;
            out = optimize(data);
        } catch (InterruptedException e) {
            throw new IOException("interrupted");
        } catch (OutOfMemoryError e) {
            out = null;
            System.gc();
        } finally {
            if (acquired) gate.release();
        }
        return new ByteArrayInputStream(out != null ? out : data);
    }

    /** Returns re-encoded bytes, or null to keep the original. */
    byte[] optimize(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int w = bounds.outWidth, h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null; // not decodable here (e.g. AVIF): let the WebView try
        String mime = bounds.outMimeType == null ? "" : bounds.outMimeType;
        boolean jpeg = mime.contains("jpeg");
        boolean gif = mime.contains("gif");
        boolean animated = gif && isAnimatedGif(data);
        if (animated && !freezeGif) return null;

        double scale = 1.0;
        if (w > maxWidth) scale = (double) maxWidth / w;
        double pixels = (double) w * h * scale * scale;
        if (pixels > maxPixels) scale *= Math.sqrt(maxPixels / pixels);
        boolean resize = scale < 0.95;
        boolean recolor = gray && data.length > 24 * 1024;
        if (!resize && !animated && !recolor) return null;
        if (w < 32 && h < 32 && !animated) return null; // icons: not worth it

        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BitmapFactory.Options o = new BitmapFactory.Options();
        int sample = 1;
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2;
        o.inSampleSize = sample;
        o.inPreferredConfig = jpeg ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        o.inDither = jpeg;
        Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bm == null) return null;
        try {
            if (bm.getWidth() > tw + 2 || bm.getHeight() > th + 2) {
                Bitmap scaled = Bitmap.createScaledBitmap(bm, tw, th, true);
                if (scaled != bm) {
                    bm.recycle();
                    bm = scaled;
                }
            }
            boolean alpha = !jpeg && bm.hasAlpha();
            if (gray) {
                Bitmap g = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(),
                        alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(g);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0);
                // Slight contrast boost: e-ink renders mid-grays washed out.
                float c = 1.12f, t = (1 - c) * 128;
                cm.postConcat(new ColorMatrix(new float[] {c, 0, 0, 0, t, 0, c, 0, 0, t, 0, 0, c, 0, t, 0, 0, 0, 1, 0}));
                p.setColorFilter(new ColorMatrixColorFilter(cm));
                if (!alpha) canvas.drawColor(0xFFFFFFFF);
                canvas.drawBitmap(bm, 0, 0, p);
                bm.recycle();
                bm = g;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, data.length / 2));
            bm.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, quality, out);
            byte[] result = out.toByteArray();
            if (!resize && !animated && result.length >= data.length) return null;
            return result;
        } finally {
            bm.recycle();
        }
    }

    /** A GIF with more than one Graphic Control Extension (or a looping extension) is animated. */
    static boolean isAnimatedGif(byte[] d) {
        int frames = 0;
        for (int i = 0; i + 10 < d.length; i++) {
            if (d[i] == 0x21 && (d[i + 1] & 0xff) == 0xF9 && d[i + 2] == 0x04) {
                if (++frames > 1) return true;
            } else if (d[i] == 'N' && d[i + 1] == 'E' && d[i + 2] == 'T' && d[i + 3] == 'S' && d[i + 4] == 'C'
                    && d[i + 5] == 'A' && d[i + 6] == 'P' && d[i + 7] == 'E') {
                return true;
            }
        }
        return false;
    }
}
