package com.browserlite;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import com.browserlite.net.NetEngine;
import com.browserlite.net.UrlUtil;
import com.browserlite.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads through the built-in engine (the system DownloadManager on 4.4 can't open most HTTPS sites
 * any more), then registers the file with DownloadManager so it shows up in the Downloads app.
 */
public final class Downloader {
    private static int nextId = 1000;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Downloader() {}

    public static String fileName(String url, String disposition, String mime) {
        String name = URLUtil.guessFileName(url, disposition, mime);
        if (name == null || name.isEmpty()) name = "download";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public static void start(final Context ctx, final String url, final String userAgent, final String disposition,
            final String mimeType, final String referer) {
        final Context app = ctx.getApplicationContext();
        final String name = fileName(url, disposition, mimeType);
        Ui.toast(app, app.getString(R.string.toast_download_started, name));
        new Thread(new Runnable() {
            @Override
            public void run() {
                int id = nextId++;
                NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                File out = null;
                try {
                    out = target(app, name);
                    String mime = mimeType;
                    long length;
                    if (url.startsWith("data:")) {
                        int comma = url.indexOf(',');
                        String meta = url.substring(5, Math.max(5, comma));
                        byte[] data = meta.endsWith(";base64") ? Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
                                : Uri.decode(url.substring(comma + 1)).getBytes("UTF-8");
                        OutputStream os = new FileOutputStream(out);
                        os.write(data);
                        os.close();
                        length = data.length;
                        if (mime == null || mime.isEmpty()) mime = meta.replace(";base64", "");
                    } else {
                        Request.Builder rb = new Request.Builder().url(url);
                        if (userAgent != null) rb.header("User-Agent", userAgent);
                        if (referer != null && UrlUtil.isHttp(referer)) rb.header("Referer", referer);
                        Response r = NetEngine.client(app).newCall(rb.build()).execute();
                        try {
                            if (mime == null || mime.isEmpty() || mime.equals("application/octet-stream")) {
                                String ct = UrlUtil.mimeOf(r.header("Content-Type"));
                                if (!ct.isEmpty()) mime = ct;
                            }
                            length = write(app, nm, id, r, out, name);
                        } finally {
                            r.close();
                        }
                    }
                    nm.cancel(id);
                    registerCompleted(app, nm, id, out, mime == null || mime.isEmpty() ? guessMime(out) : mime, length);
                    done(app, out.getName());
                } catch (final Exception e) {
                    nm.cancel(id);
                    if (out != null) out.delete();
                    failed(app, e);
                }
            }
        }, "download").start();
    }

    /** Saves a response we already have open (a main-frame navigation that turned out to be a file). */
    public static void save(final Context ctx, final Response response, final String url, final String name,
            final String mime) {
        final Context app = ctx.getApplicationContext();
        main.post(new Runnable() {
            @Override
            public void run() {
                Ui.toast(app, app.getString(R.string.toast_download_started, name));
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                int id = nextId++;
                NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                File out = null;
                try {
                    out = target(app, name);
                    long length = write(app, nm, id, response, out, name);
                    nm.cancel(id);
                    String type = mime == null || mime.isEmpty() ? guessMime(out) : mime;
                    registerCompleted(app, nm, id, out, type, length);
                    done(app, out.getName());
                } catch (Exception e) {
                    nm.cancel(id);
                    if (out != null) out.delete();
                    failed(app, e);
                } finally {
                    response.close();
                }
            }
        }, "download").start();
    }

    private static long write(Context app, NotificationManager nm, int id, Response r, File out, String name)
            throws IOException {
        if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
        ResponseBody body = r.body();
        if (body == null) throw new IOException("empty");
        long total = body.contentLength();
        InputStream in = body.byteStream();
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[32 * 1024];
            long done = 0, lastNotify = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastNotify > 2000) {
                    lastNotify = now;
                    progress(app, nm, id, name, done, total);
                }
            }
            return done;
        } finally {
            os.close();
        }
    }

    private static String guessMime(File f) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(f.getName());
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }

    private static void done(final Context app, final String name) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Ui.toast(app, app.getString(R.string.toast_download_done, name));
            }
        });
    }

    private static void failed(final Context app, final Exception e) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Ui.toast(app, app.getString(R.string.toast_download_failed, String.valueOf(e.getMessage())));
            }
        });
    }

    private static File target(Context c, String name) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null || (!dir.exists() && !dir.mkdirs()) || !dir.canWrite()) {
            dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        }
        if (dir == null) dir = new File(c.getFilesDir(), "downloads");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("no storage");
        File f = new File(dir, name);
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; f.exists() && i < 1000; i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    @SuppressWarnings("deprecation")
    private static void progress(Context c, NotificationManager nm, int id, String name, long done, long total) {
        Notification.Builder b = new Notification.Builder(c)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(name)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (total > 0) b.setProgress(100, (int) (done * 100 / total), false);
        else b.setProgress(0, 0, true);
        b.setContentText(done / 1024 + " KB" + (total > 0 ? " / " + total / 1024 + " KB" : ""));
        nm.notify(id, b.getNotification());
    }

    @SuppressWarnings("deprecation")
    private static void registerCompleted(Context c, NotificationManager nm, int id, File f, String mime, long length) {
        try {
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.addCompletedDownload(f.getName(), f.getName(), true, mime, f.getAbsolutePath(), length, true);
            return;
        } catch (Exception ignored) {
            // Fall back to our own notification.
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(Uri.fromFile(f), mime);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, id, view,
                    android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification.Builder b = new Notification.Builder(c)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(f.getName())
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(id, b.getNotification());
        } catch (Exception ignored) {
            // nothing else to do
        }
    }
}
