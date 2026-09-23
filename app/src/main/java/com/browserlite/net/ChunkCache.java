package com.browserlite.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads a video file ahead of playback in 1 MB pieces kept on disk, and serves the player from them. The player
 * never talks to the video server itself, so:
 * <ul>
 *   <li>short network stalls are absorbed by the read-ahead instead of freezing the picture;</li>
 *   <li>when the server refuses a piece (403/410, links expire or get revoked mid-video) a fresh link for the same
 *       file is fetched and the download continues at the same byte, unnoticed by the player;</li>
 *   <li>flaky connections are retried with growing pauses.</li>
 * </ul>
 * Pieces far behind the playback position are deleted, so disk use stays bounded. No RAM beyond one copy buffer.
 */
public final class ChunkCache {
    /** A download link plus the User-Agent it was issued to. */
    public static final class Link {
        public final String url, userAgent;

        public Link(String url, String userAgent) {
            this.url = url;
            this.userAgent = userAgent;
        }
    }

    /** Supplies a new link to the same file (same format, same length), or null when there is none. */
    public interface LinkSource {
        Link freshLink() throws IOException;
    }

    public static final int CHUNK = 1 << 20;

    private final OkHttpClient http;
    private volatile String userAgent;
    private final File dir;
    private final LinkSource source;
    private final int ahead, keep;
    private volatile String url;
    private final Object lock = new Object();
    private long length = -1;
    private boolean[] have;
    private int playChunk;
    private String contentType = "application/octet-stream";
    private volatile String error;
    private volatile boolean closed;
    private int refreshes, retries;
    private final Thread fetcher;

    /**
     * @param knownLength file size if the API told us, else 0 (learnt from the first piece)
     * @param ahead       pieces to download ahead of the playback position
     * @param keep        pieces kept on disk at most (older ones behind the position are dropped)
     */
    public ChunkCache(OkHttpClient http, String url, String userAgent, long knownLength, File dir, LinkSource source,
            int ahead, int keep) {
        this.http = http;
        this.url = url;
        this.userAgent = userAgent;
        this.dir = dir;
        this.source = source;
        this.ahead = Math.max(2, ahead);
        this.keep = Math.max(this.ahead + 2, keep);
        dir.mkdirs();
        if (knownLength > 0) setLength(knownLength);
        fetcher = new Thread(new Runnable() {
            @Override
            public void run() {
                fetchLoop();
            }
        }, "chunk-cache");
        fetcher.setDaemon(true);
        fetcher.start();
    }

    private void setLength(long len) {
        length = len;
        have = new boolean[(int) ((len + CHUNK - 1) / CHUNK)];
    }

    /** Blocks until the size is known (first piece), up to {@code timeoutMs}. */
    public long length(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (length < 0) {
                if (error != null) throw new IOException(error);
                if (closed) throw new IOException("closed");
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new IOException("timeout");
                waitQuietly(left);
            }
            return length;
        }
    }

    public String contentType() {
        synchronized (lock) {
            return contentType;
        }
    }

    public String error() {
        return error;
    }

    /** End (exclusive) of the bytes stored without a gap from the playback position on. */
    public long bufferedUntil() {
        synchronized (lock) {
            if (length < 0) return 0;
            int i = Math.max(0, playChunk);
            while (i < have.length && have[i]) i++;
            return Math.min(length, (long) i * CHUNK);
        }
    }

    /** Links replaced so far and pieces fetched again after a failure (for the log). */
    public String stats() {
        synchronized (lock) {
            return "new links " + refreshes + ", retries " + retries;
        }
    }

    /** Writes bytes [start, end] (inclusive) to {@code out}, waiting for pieces as they arrive. */
    public void serve(long start, long end, OutputStream out, long stallTimeoutMs) throws IOException {
        byte[] buf = new byte[32 * 1024];
        long pos = start;
        while (pos <= end) {
            int idx = (int) (pos / CHUNK);
            File f;
            long waited = 0;
            synchronized (lock) {
                playChunk = idx;
                lock.notifyAll();
                while (have == null || idx >= have.length || !have[idx]) {
                    if (error != null) throw new IOException(error);
                    if (closed) throw new IOException("closed");
                    if (have != null && idx >= have.length) throw new IOException("past the end");
                    if (waited >= stallTimeoutMs) throw new IOException("stalled");
                    long t0 = System.currentTimeMillis();
                    waitQuietly(500);
                    waited += System.currentTimeMillis() - t0;
                }
                f = chunkFile(idx);
            }
            long offset = pos - (long) idx * CHUNK;
            long upto = Math.min(end + 1, (long) (idx + 1) * CHUNK);
            InputStream in;
            try {
                in = new FileInputStream(f);
            } catch (IOException gone) {
                synchronized (lock) { // dropped from disk meanwhile: fetch it again
                    have[idx] = false;
                    lock.notifyAll();
                }
                continue;
            }
            try {
                skipFully(in, offset);
                long remaining = upto - pos;
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    remaining -= n;
                    pos += n;
                }
                if (remaining > 0) throw new IOException("short piece");
            } finally {
                in.close();
            }
        }
        out.flush();
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        fetcher.interrupt();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    // ------------------------------------------------------------------ download side

    private File chunkFile(int idx) {
        return new File(dir, "c" + idx);
    }

    private int nextMissing() {
        synchronized (lock) {
            if (length < 0) return 0;
            int end = Math.min(have.length, playChunk + ahead);
            for (int i = Math.max(0, playChunk); i < end; i++) if (!have[i]) return i;
            return -1;
        }
    }

    private void fetchLoop() {
        while (!closed) {
            int idx = nextMissing();
            if (idx < 0) {
                synchronized (lock) {
                    if (!closed) waitQuietly(1000);
                }
                continue;
            }
            if (!fetch(idx)) return; // permanent failure, error is set
            evict();
        }
    }

    private boolean wanted(int idx) {
        synchronized (lock) {
            return idx >= playChunk && idx < playChunk + ahead;
        }
    }

    /** Downloads one piece; false when the file can no longer be fetched at all. */
    private boolean fetch(int idx) {
        int attempts = 0;
        while (!closed) {
            if (attempts > 0 && !wanted(idx)) return true; // the player jumped elsewhere meanwhile
            long a = (long) idx * CHUNK;
            long b;
            synchronized (lock) {
                b = length > 0 ? Math.min(length, a + CHUNK) - 1 : a + CHUNK - 1;
            }
            Request.Builder rb = new Request.Builder().url(url).header("Range", "bytes=" + a + "-" + b)
                    .header("Accept", "*/*").header("Accept-Encoding", "identity");
            if (userAgent != null) rb.header("User-Agent", userAgent);
            int code;
            try (Response r = http.newCall(rb.build()).execute()) {
                code = r.code();
                if (code == 206 || (code == 200 && a == 0)) {
                    ResponseBody body = r.body();
                    if (body == null) throw new IOException("empty body");
                    long total = totalOf(r, code);
                    synchronized (lock) {
                        if (length < 0) {
                            if (total <= 0) {
                                error = "unknown size";
                                lock.notifyAll();
                                return false;
                            }
                            setLength(total);
                            b = Math.min(length, a + CHUNK) - 1;
                        }
                        String ct = r.header("Content-Type");
                        if (ct != null) contentType = ct;
                    }
                    long expected = b - a + 1;
                    if (!dir.isDirectory()) dir.mkdirs(); // storage cleaned behind our back
                    File part = new File(dir, "c" + idx + ".part");
                    long got = write(body.byteStream(), part, expected);
                    if (got < expected) throw new IOException("short read " + got + "/" + expected);
                    File done = chunkFile(idx);
                    if (!part.renameTo(done)) throw new IOException("cannot store piece");
                    synchronized (lock) {
                        have[idx] = true;
                        lock.notifyAll();
                    }
                    return true;
                }
            } catch (IOException e) {
                code = -1;
            }
            if (closed) return false;
            if (code == 403 || code == 410 || code == 401 || code == 404) {
                // The link was refused or revoked: a new one for the same file, from where we are.
                Link fresh = null;
                if (source != null && refreshes < 8) {
                    synchronized (lock) {
                        refreshes++;
                    }
                    try {
                        fresh = source.freshLink();
                    } catch (IOException ignored) {
                        // none
                    }
                }
                if (fresh == null || fresh.url == null) {
                    fail("HTTP " + code);
                    return false;
                }
                url = fresh.url;
                if (fresh.userAgent != null) userAgent = fresh.userAgent;
                continue;
            }
            // Network trouble, 5xx, 429: wait a little longer each time.
            synchronized (lock) {
                retries++;
            }
            if (++attempts > 10) {
                fail(code > 0 ? "HTTP " + code : "network");
                return false;
            }
            sleepQuietly(Math.min(8000, 500L << Math.min(4, attempts)));
        }
        return false;
    }

    private void fail(String why) {
        synchronized (lock) {
            error = why;
            lock.notifyAll();
        }
    }

    private static long totalOf(Response r, int code) {
        String cr = r.header("Content-Range");
        if (cr != null) {
            int slash = cr.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(cr.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                    // "*"
                }
            }
        }
        if (code == 200) {
            ResponseBody b = r.body();
            return b == null ? -1 : b.contentLength();
        }
        return -1;
    }

    private long write(InputStream in, File part, long expected) throws IOException {
        byte[] buf = new byte[32 * 1024];
        long got = 0;
        OutputStream os = new FileOutputStream(part);
        try {
            while (got < expected && !closed) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, expected - got));
                if (n <= 0) break;
                os.write(buf, 0, n);
                got += n;
            }
        } finally {
            os.close();
        }
        return got;
    }

    /**
     * Keeps at most {@code keep} pieces: first the ones furthest behind the playback position (a little is kept for
     * short rewinds), then the ones far ahead left over from before a backward jump.
     */
    private void evict() {
        synchronized (lock) {
            if (have == null) return;
            int count = 0;
            for (boolean h : have) if (h) count++;
            for (int i = 0; count > keep && i < playChunk - 1; i++) {
                if (have[i]) {
                    drop(i);
                    count--;
                }
            }
            for (int i = have.length - 1; count > keep && i >= playChunk + ahead; i--) {
                if (have[i]) {
                    drop(i);
                    count--;
                }
            }
        }
    }

    private void drop(int i) {
        chunkFile(i).delete();
        have[i] = false;
    }

    private void waitQuietly(long ms) {
        try {
            lock.wait(Math.max(1, ms));
        } catch (InterruptedException ignored) {
            // re-check state
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // closing
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) throw new IOException("cannot skip");
            n -= s;
        }
    }
}
