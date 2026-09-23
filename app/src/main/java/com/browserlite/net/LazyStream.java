package com.browserlite.net;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * An InputStream handed to the WebView before the response exists.
 *
 * <p>On KitKat, {@code shouldInterceptRequest} runs on the WebView's network thread: blocking there would
 * serialise every request of the page. So we return this stream immediately, start the request
 * asynchronously, and only block the WebView's stream-reader thread (inside {@link #read}) until data arrives.
 */
public final class LazyStream extends InputStream implements Callback {

    /** Turns a response into the bytes given to the WebView. Runs on an OkHttp thread. */
    public interface Transformer {
        InputStream apply(Response response) throws IOException;
    }

    /** Substitute content when the request fails (for example an error page). Runs on an OkHttp thread. */
    public interface Fallback {
        InputStream onFailure(IOException error);
    }

    private static final long TIMEOUT_MS = 90_000;

    private static final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newCachedThreadPool();

    /** Supplies the client once the network engine is ready (may block; runs on a worker thread). */
    public interface ClientSource {
        OkHttpClient get() throws IOException;
    }

    private volatile Call call;
    private final Transformer transformer;
    private final Fallback fallback;
    private InputStream delegate;
    private IOException error;
    private boolean closed;

    public LazyStream(OkHttpClient client, Request request, Transformer transformer) {
        this(client, request, transformer, null);
    }

    public LazyStream(OkHttpClient client, Request request, Transformer transformer, Fallback fallback) {
        this.transformer = transformer;
        this.fallback = fallback;
        this.call = client.newCall(request);
        call.enqueue(this);
    }

    /** Starts the request on a worker thread once {@code source} can supply a client. */
    public LazyStream(final ClientSource source, final Request request, Transformer transformer, Fallback fallback) {
        this.transformer = transformer;
        this.fallback = fallback;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Call c = source.get().newCall(request);
                    synchronized (LazyStream.this) {
                        if (closed) return;
                        call = c;
                    }
                    c.enqueue(LazyStream.this);
                } catch (IOException e) {
                    onFailure(null, e);
                }
            }
        });
    }

    /** Transforms a response we already hold, off the calling thread. */
    public LazyStream(final Response response, Transformer transformer, Fallback fallback) {
        this.transformer = transformer;
        this.fallback = fallback;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                onResponse(null, response);
            }
        });
    }

    @Override
    public void onFailure(Call c, IOException e) {
        InputStream substitute = null;
        if (fallback != null) {
            try {
                substitute = fallback.onFailure(e);
            } catch (RuntimeException ignored) {
                // report the original error
            }
        }
        synchronized (this) {
            if (substitute != null && !closed) delegate = substitute;
            else error = e;
            notifyAll();
        }
    }

    @Override
    public void onResponse(Call c, Response response) {
        InputStream in;
        try {
            in = transformer != null ? transformer.apply(response) : response.body().byteStream();
        } catch (IOException e) {
            response.close();
            onFailure(c, e);
            return;
        } catch (RuntimeException | OutOfMemoryError e) {
            response.close();
            onFailure(c, new IOException(e.toString()));
            return;
        }
        boolean close;
        synchronized (this) {
            close = closed;
            if (!closed) delegate = in;
            notifyAll();
        }
        if (close) {
            // The stream owns the response (or already consumed it); a response stashed by the transformer for a
            // later request must stay open.
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private InputStream await() throws IOException {
        synchronized (this) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (delegate == null && error == null && !closed) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    Call c = call;
                    if (c != null) c.cancel();
                    throw new IOException("timeout");
                }
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Call c = call;
                    if (c != null) c.cancel();
                    throw new IOException("interrupted");
                }
            }
            if (closed) throw new IOException("closed");
            if (error != null) throw error;
            return delegate;
        }
    }

    // The KitKat WebView aborts the whole process (native CHECK) when a stream it reads throws after the request
    // was cancelled, so errors end the stream instead: the resource is simply truncated or empty.
    @Override
    public int read() {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        try {
            int n = await().read(b, off, len);
            return n;
        } catch (IOException | RuntimeException e) {
            if (debug && !closed) android.util.Log.d("LazyStream", "read failed: " + e);
            return -1;
        }
    }

    public static volatile boolean debug;

    @Override
    public int available() {
        return 0;
    }

    @Override
    public void close() {
        if (debug && delegate == null && error == null) {
            android.util.Log.d("LazyStream", "closed before the response arrived", new Throwable());
        }
        InputStream d;
        boolean delivered;
        synchronized (this) {
            closed = true;
            d = delegate;
            delivered = delegate != null || error != null;
            delegate = null;
            notifyAll();
        }
        // Once delivered, the stream owns the response. Cancelling the call then would also kill a response the
        // transformer handed on (redirect targets are fetched once and kept for the follow-up request).
        Call c = call;
        if (c != null && !delivered) c.cancel();
        if (d != null) {
            try {
                d.close();
            } catch (IOException | RuntimeException ignored) {
                // nothing to do
            }
        }
    }
}
