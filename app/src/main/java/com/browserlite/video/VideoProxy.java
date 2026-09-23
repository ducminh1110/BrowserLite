package com.browserlite.video;

import android.content.Context;
import android.util.Log;

import com.browserlite.net.ChunkCache;
import com.browserlite.net.NetEngine;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Tiny HTTP server on 127.0.0.1 that relays media through the app's network engine. KitKat's MediaPlayer speaks
 * only its own old TLS (and FFmpeg here speaks none), so both players read {@code http://127.0.0.1/...} and we
 * fetch the real {@code https://} bytes with modern TLS, forwarding Range requests for seeking. HLS playlists are
 * rewritten so their segments come through here too.
 */
public final class VideoProxy {
    private static final String TAG = "VideoProxy";
    private static VideoProxy instance;

    private static final class Target {
        final String url, userAgent, referer, mime;
        /** Read-ahead on disk with link renewal, or null to relay requests as they come. */
        final ChunkCache cache;
        volatile long lastUse = System.currentTimeMillis();

        Target(String url, String userAgent, String referer, String mime, ChunkCache cache) {
            this.url = url;
            this.userAgent = userAgent;
            this.referer = referer;
            this.mime = mime;
            this.cache = cache;
        }
    }

    /** Cached targets nobody reads for this long are closed (downloads never unregister). */
    private static final long IDLE_MS = 20 * 60_000L;

    private final Context app;
    private final ServerSocket server;
    private final ConcurrentHashMap<String, Target> targets = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "video-proxy");
            t.setDaemon(true);
            return t;
        }
    });
    private final SecureRandom random = new SecureRandom();

    private VideoProxy(Context c) throws IOException {
        app = c.getApplicationContext();
        // Pieces left by a previous run, removed before anything new is stored next to them.
        deleteTree(cacheRoot(), false);
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "video-proxy-accept");
        t.setDaemon(true);
        t.start();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                reapLoop();
            }
        });
    }

    public static synchronized VideoProxy get(Context c) throws IOException {
        if (instance == null) instance = new VideoProxy(c);
        return instance;
    }

    /** Local URL that serves {@code url}. {@code name} only helps players guess the container. */
    public String register(String url, String userAgent, String referer, String name) {
        String token = newToken();
        targets.put(token, new Target(url, userAgent, referer, null, null));
        return localUrl(token, name);
    }

    /**
     * Like {@link #register}, but the file is downloaded ahead of the player onto disk in 1 MB pieces, and a link
     * the server refuses midway is replaced through {@code source} without the player noticing. Falls back to the
     * plain relay when storage is nearly full.
     *
     * @param length file size when known, else 0
     * @param ahead  pieces to fetch ahead of the reader at most (0: as many as the free space allows)
     */
    public String registerCached(String url, String userAgent, String name, String mime, long length,
            ChunkCache.LinkSource source, int ahead) throws IOException {
        String token = newToken();
        File root = cacheRoot();
        long freeMb = root.getUsableSpace() >> 20;
        // A quarter of the free space, at most 96 MB: about 20 minutes of 360p ahead, 40 of 240p.
        int budget = (int) Math.min(96, freeMb / 4);
        if (budget < 6) {
            Log.w(TAG, "only " + freeMb + " MB free: no read-ahead");
            return register(url, userAgent, null, name);
        }
        int a = Math.max(3, Math.min(ahead > 0 ? ahead : 32, budget / 2));
        ChunkCache cache = new ChunkCache(mediaClient(), url, userAgent, length, new File(root, token), source, a, budget);
        targets.put(token, new Target(url, userAgent, null, mime, cache));
        return localUrl(token, name);
    }

    /** {bytes stored ahead of the reader, file size} of a cached target, or null. */
    public long[] buffered(String localUrl) {
        Target t = targets.get(tokenOf(localUrl));
        if (t == null || t.cache == null) return null;
        long len;
        try {
            len = t.cache.length(0);
        } catch (IOException e) {
            return null;
        }
        return new long[] {t.cache.bufferedUntil(), len};
    }

    public void unregister(String localUrl) {
        if (localUrl == null) return;
        final Target t = targets.remove(tokenOf(localUrl));
        if (t != null && t.cache != null) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "read-ahead closed: " + t.cache.stats());
                    t.cache.close();
                }
            });
        }
    }

    private String newToken() {
        return Long.toHexString(random.nextLong() & Long.MAX_VALUE);
    }

    private String localUrl(String token, String name) {
        return "http://127.0.0.1:" + server.getLocalPort() + "/v/" + token + "/" + name;
    }

    private static String tokenOf(String localUrl) {
        if (localUrl == null) return "";
        int i = localUrl.indexOf("/v/");
        if (i < 0) return "";
        String rest = localUrl.substring(i + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /** Pieces go where there is more room: the shared storage on most readers, else the app's own cache. */
    private File cacheRoot() {
        File internal = new File(app.getCacheDir(), "video");
        File ext = null;
        try {
            File e = app.getExternalCacheDir();
            if (e != null && android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState())) {
                ext = new File(e, "video");
            }
        } catch (RuntimeException ignored) {
            // no shared storage
        }
        File pick = internal;
        if (ext != null) {
            File probe = ext.getParentFile();
            if (probe != null && probe.getUsableSpace() > internal.getParentFile().getUsableSpace()) pick = ext;
        }
        pick.mkdirs();
        return pick;
    }

    private static void deleteTree(File dir, boolean self) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteTree(f, true);
                else f.delete();
            }
        }
        if (self) dir.delete();
    }

    private void reapLoop() {
        while (true) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (java.util.Map.Entry<String, Target> e : targets.entrySet()) {
                Target t = e.getValue();
                if (t.cache != null && now - t.lastUse > IDLE_MS && targets.remove(e.getKey(), t)) t.cache.close();
            }
        }
    }

    private void acceptLoop() {
        while (true) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(s);
                    }
                });
            } catch (IOException e) {
                Log.w(TAG, "accept failed", e);
                return;
            }
        }
    }

    private void serve(Socket s) {
        Response upstream = null;
        try {
            s.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String range = null, agent = "";
            String line;
            int headerBytes = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                headerBytes += line.length();
                if (headerBytes > 16384) return;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim();
                if (name.equalsIgnoreCase("range")) range = line.substring(colon + 1).trim();
                else if (name.equalsIgnoreCase("user-agent")) agent = line.substring(colon + 1).trim();
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            boolean head = parts[0].equals("HEAD");
            String path = parts[1];
            OutputStream out = s.getOutputStream();
            String upstreamUrl = resolve(path);
            Target t = target(path);
            if (upstreamUrl == null || t == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                return;
            }
            t.lastUse = System.currentTimeMillis();
            if (t.cache != null && path.startsWith("/v/")) {
                serveCached(t, range, head, agent.startsWith("Lavf"), out);
                return;
            }
            OkHttpClient client = mediaClient();
            // identity: byte ranges and Content-Length must describe the bytes we relay
            Request.Builder rb = new Request.Builder().url(upstreamUrl).header("Accept", "*/*")
                    .header("Accept-Encoding", "identity");
            if (t.userAgent != null) rb.header("User-Agent", t.userAgent);
            if (t.referer != null) rb.header("Referer", t.referer);
            long[] want = parseRange(range);
            if (!head && want != null && isThrottledHost(upstreamUrl) && (want[1] < 0 || want[1] - want[0] >= 2 * CHUNK)) {
                relayChunked(client, rb, want[0], want[1], range != null, out);
                return;
            }
            if (range != null) rb.header("Range", range);
            if (head) rb.head();
            upstream = client.newCall(rb.build()).execute();
            if (upstream.code() >= 400) Log.w(TAG, "upstream " + upstream.code() + " for " + com.browserlite.net.UrlUtil.host(upstreamUrl));
            ResponseBody body = upstream.body();
            String type = upstream.header("Content-Type", "application/octet-stream");
            boolean playlist = type.toLowerCase(Locale.US).contains("mpegurl")
                    || upstreamUrl.toLowerCase(Locale.US).contains(".m3u8");
            StringBuilder h = new StringBuilder(256);
            h.append("HTTP/1.1 ").append(upstream.code()).append(' ')
                    .append(upstream.message().isEmpty() ? "OK" : upstream.message()).append("\r\n");
            h.append("Content-Type: ").append(type).append("\r\n");
            h.append("Accept-Ranges: bytes\r\nConnection: close\r\n");
            if (playlist && body != null && !head) {
                String text = rewritePlaylist(body.string(), upstream.request().url(), path);
                byte[] bytes = text.getBytes("UTF-8");
                h.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
                out.write(h.toString().getBytes("US-ASCII"));
                out.write(bytes);
                out.flush();
                return;
            }
            String len = upstream.header("Content-Length");
            String cr = upstream.header("Content-Range");
            if (len != null) h.append("Content-Length: ").append(len).append("\r\n");
            if (cr != null) h.append("Content-Range: ").append(cr).append("\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes("US-ASCII"));
            if (head || body == null) {
                out.flush();
                return;
            }
            InputStream src = body.byteStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (IOException e) {
            // player closed the connection (seek, stop) or network failure: nothing to report
        } catch (RuntimeException e) {
            Log.w(TAG, "proxy failure", e);
        } finally {
            if (upstream != null) upstream.close();
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static final long CHUNK = 1 << 20;

    /**
     * Answers from the read-ahead. While a piece is late the answer just pauses; after a while the connection is cut
     * so the player asks again from where it stopped (the built-in decoder reconnects by itself: for it the reply
     * must not announce "Connection: close", or a cut would look like the end of the file).
     */
    private void serveCached(Target t, String range, boolean head, boolean lavf, OutputStream out) throws IOException {
        ChunkCache c = t.cache;
        long total;
        try {
            total = c.length(25_000);
        } catch (IOException e) {
            String err = c.error() != null ? c.error() : String.valueOf(e.getMessage());
            String status = err.contains("403") ? "403 Forbidden" : err.contains("404") ? "404 Not Found" : "502 Bad Gateway";
            Log.w(TAG, "read-ahead failed: " + err);
            out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            return;
        }
        long start = 0, end = total - 1;
        boolean ranged = false;
        if (range != null) {
            String r = range.trim().toLowerCase(Locale.US);
            long[] want = parseRange(range);
            if (want != null) {
                start = want[0];
                if (want[1] >= 0) end = Math.min(end, want[1]);
                ranged = true;
            } else if (r.startsWith("bytes=-") && r.indexOf(',') < 0) {
                try {
                    start = Math.max(0, total - Long.parseLong(r.substring(7).trim()));
                    ranged = true;
                } catch (NumberFormatException ignored) {
                    // whole file
                }
            }
        }
        if (start >= total || start > end) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            return;
        }
        String type = t.mime != null ? t.mime : c.contentType();
        StringBuilder h = new StringBuilder(256);
        h.append(ranged ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(type).append("\r\nAccept-Ranges: bytes\r\n");
        h.append("Content-Length: ").append(end - start + 1).append("\r\n");
        if (ranged) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
        if (!lavf) h.append("Connection: close\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        if (head) {
            out.flush();
            return;
        }
        c.serve(start, end, out, lavf ? 15_000 : 60_000);
    }

    /** YouTube's servers slow long open-ended downloads to about playback speed, but serve 1 MB ranges at once. */
    private static boolean isThrottledHost(String url) {
        String host = com.browserlite.net.UrlUtil.host(url);
        return host.endsWith(".googlevideo.com");
    }

    /** {start, endInclusive or -1}; no header means the whole file. Null when unparseable (multi-range...). */
    static long[] parseRange(String range) {
        if (range == null) return new long[] {0, -1};
        String r = range.trim().toLowerCase(Locale.US);
        if (!r.startsWith("bytes=") || r.indexOf(',') >= 0) return null;
        r = r.substring(6).trim();
        int dash = r.indexOf('-');
        if (dash <= 0) return null; // suffix ranges ("-500") are rare for players: forward as-is
        try {
            long start = Long.parseLong(r.substring(0, dash).trim());
            String e = r.substring(dash + 1).trim();
            return new long[] {start, e.isEmpty() ? -1 : Long.parseLong(e)};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Answers one (possibly open-ended) range with consecutive 1 MB upstream requests, streamed as they arrive. */
    private void relayChunked(OkHttpClient client, Request.Builder base, long start, long end, boolean ranged,
            OutputStream out) throws IOException {
        long pos = start, last = end, total = -1;
        byte[] buf = new byte[32 * 1024];
        boolean first = true;
        int retries = 0;
        while (true) {
            long chunkEnd = pos + CHUNK - 1;
            if (last >= 0) chunkEnd = Math.min(chunkEnd, last);
            Response r = client.newCall(base.header("Range", "bytes=" + pos + "-" + chunkEnd).build()).execute();
            long copied = 0;
            try {
                ResponseBody body = r.body();
                if (first) {
                    first = false;
                    String cr = r.header("Content-Range");
                    int slash = cr == null ? -1 : cr.lastIndexOf('/');
                    if (r.code() >= 400) Log.w(TAG, "upstream " + r.code() + " for " + com.browserlite.net.UrlUtil.host(r.request().url().toString()));
                    if (r.code() != 206 || slash < 0 || cr.endsWith("*")) {
                        // Server ignored the range (or failed): pass its answer through unchanged.
                        writeHead(out, r, r.header("Content-Length"), cr);
                        if (body != null) copy(body.byteStream(), out, buf);
                        return;
                    }
                    total = Long.parseLong(cr.substring(slash + 1).trim());
                    if (last < 0 || last >= total) last = total - 1;
                    String length = String.valueOf(last - start + 1);
                    if (ranged) {
                        writeHead(out, r, length, "bytes " + start + "-" + last + "/" + total);
                    } else {
                        StringBuilder h = new StringBuilder(256);
                        h.append("HTTP/1.1 200 OK\r\nContent-Type: ").append(r.header("Content-Type", "application/octet-stream"))
                                .append("\r\nAccept-Ranges: bytes\r\nConnection: close\r\nContent-Length: ").append(length)
                                .append("\r\n\r\n");
                        out.write(h.toString().getBytes("US-ASCII"));
                    }
                } else if (r.code() != 206 || !String.valueOf(r.header("Content-Range")).startsWith("bytes " + pos + "-")) {
                    return; // the player reconnects with a Range from where it stopped
                }
                if (body != null) {
                    InputStream in = body.byteStream();
                    while (true) {
                        int n;
                        try {
                            n = in.read(buf);
                        } catch (IOException upstreamDropped) {
                            if (retries >= 3) throw upstreamDropped;
                            break; // fetch the rest of this chunk again below
                        }
                        if (n <= 0) break;
                        out.write(buf, 0, n); // a failure here means the player went away: stop
                        copied += n;
                    }
                }
            } finally {
                r.close();
            }
            // Continue exactly after the last byte relayed: a short chunk (dropped connection) is fetched again from
            // there, so the player never sees a gap.
            if (copied < chunkEnd - pos + 1) {
                if (++retries > 3) return;
            } else {
                retries = 0;
            }
            pos += copied;
            if (pos > last) break;
        }
        out.flush();
    }

    private static void writeHead(OutputStream out, Response r, String length, String contentRange) throws IOException {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 ").append(r.code()).append(' ').append(r.message().isEmpty() ? "OK" : r.message()).append("\r\n");
        h.append("Content-Type: ").append(r.header("Content-Type", "application/octet-stream")).append("\r\n");
        h.append("Accept-Ranges: bytes\r\nConnection: close\r\n");
        if (length != null) h.append("Content-Length: ").append(length).append("\r\n");
        if (contentRange != null) h.append("Content-Range: ").append(contentRange).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
    }

    private static void copy(InputStream in, OutputStream out, byte[] buf) throws IOException {
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private OkHttpClient media;

    /** The shared engine without its disk cache: media would only churn it. */
    private synchronized OkHttpClient mediaClient() throws IOException {
        if (media == null) media = NetEngine.youtube(app); // IPv4 like the API calls, no disk cache
        return media;
    }

    /** /v/TOKEN/name → the registered URL; /r/TOKEN?u=ENCODED → a URL listed inside a rewritten playlist. */
    private String resolve(String path) {
        Target t = target(path);
        if (t == null) return null;
        if (path.startsWith("/v/")) return t.url;
        int q = path.indexOf("?u=");
        if (q < 0) return null;
        try {
            String u = URLDecoder.decode(path.substring(q + 3), "UTF-8");
            return u.startsWith("http://") || u.startsWith("https://") ? u : null;
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }

    private Target target(String path) {
        if (!path.startsWith("/v/") && !path.startsWith("/r/")) return null;
        String rest = path.substring(3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?') {
                end = i;
                break;
            }
        }
        return targets.get(rest.substring(0, end));
    }

    private String rewritePlaylist(String text, HttpUrl base, String requestPath) {
        String token = requestPath.substring(3);
        int end = token.length();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '/' || c == '?') {
                end = i;
                break;
            }
        }
        token = token.substring(0, end);
        StringBuilder sb = new StringBuilder(text.length() + 1024);
        for (String raw : text.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) {
                sb.append('\n');
            } else if (line.startsWith("#")) {
                int u = line.indexOf("URI=\"");
                if (u >= 0) {
                    int close = line.indexOf('"', u + 5);
                    if (close > u) {
                        String ref = line.substring(u + 5, close);
                        line = line.substring(0, u + 5) + local(token, base, ref) + line.substring(close);
                    }
                }
                sb.append(line).append('\n');
            } else {
                sb.append(local(token, base, line.trim())).append('\n');
            }
        }
        return sb.toString();
    }

    private String local(String token, HttpUrl base, String ref) {
        HttpUrl abs = base.resolve(ref);
        if (abs == null) return ref;
        try {
            return "http://127.0.0.1:" + server.getLocalPort() + "/r/" + token + "?u=" + URLEncoder.encode(abs.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return ref;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) return null;
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }
}
