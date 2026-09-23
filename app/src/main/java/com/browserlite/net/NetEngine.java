package com.browserlite.net;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;

import com.browserlite.Config;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The built-in network engine: OkHttp over BoringSSL (Conscrypt) with TLS 1.3, HTTP/2, current root CAs and a
 * cookie jar shared with the WebView. Falls back to the platform TLS stack (with TLS 1.2 force-enabled) when
 * Conscrypt's native library can't load.
 */
public final class NetEngine {
    private static final String TAG = "NetEngine";

    private static volatile OkHttpClient client;
    private static volatile TrustStore.IntermediateTrustManager trust;
    private static volatile boolean modernTls;
    private static volatile boolean cookiesEnabled = true;

    private NetEngine() {}

    /** Starts the (slow-ish) trust store build on a background thread. */
    public static void warmUp(final Context c) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client(c);
                } catch (Throwable t) {
                    Log.w(TAG, "warm up failed", t);
                }
            }
        }, "net-warmup").start();
    }

    public static OkHttpClient client(Context c) throws IOException {
        OkHttpClient cl = client;
        if (cl != null) return cl;
        synchronized (NetEngine.class) {
            if (client == null) client = build(c.getApplicationContext());
            return client;
        }
    }

    public static OkHttpClient clientOrNull() {
        return client;
    }

    private static OkHttpClient youtube;

    /**
     * For YouTube's API and its video servers: IPv4 only and no disk cache. Stream links are locked to the address
     * the API saw; on dual-stack networks the API call and the video download can leave through different families
     * (or IPv6 privacy addresses rotate between connections), and the video server then answers 403.
     */
    public static synchronized OkHttpClient youtube(Context c) throws IOException {
        if (youtube == null) youtube = client(c).newBuilder().dns(IPV4_PREFERRED).cache(null).build();
        return youtube;
    }

    /** IPv4 addresses when the host has any, else whatever it has. */
    static final okhttp3.Dns IPV4_PREFERRED = new okhttp3.Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws java.net.UnknownHostException {
            List<InetAddress> all = okhttp3.Dns.SYSTEM.lookup(hostname);
            List<InetAddress> v4 = new ArrayList<>();
            for (InetAddress a : all) if (a instanceof java.net.Inet4Address) v4.add(a);
            return v4.isEmpty() ? all : v4;
        }
    };

    public static TrustStore.IntermediateTrustManager trust() {
        return trust;
    }

    public static boolean isModernTls() {
        return modernTls;
    }

    public static void setCookiesEnabled(boolean on) {
        cookiesEnabled = on;
    }

    private static OkHttpClient build(Context c) throws IOException {
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            return buildInner(c);
        } finally {
            Log.i(TAG, "network engine ready in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms, modern TLS: "
                    + modernTls);
        }
    }

    private static OkHttpClient buildInner(Context c) throws IOException {
        Provider provider = null;
        try {
            provider = org.conscrypt.Conscrypt.newProvider();
        } catch (Throwable t) {
            Log.w(TAG, "Conscrypt unavailable, using platform TLS", t);
        }
        SSLSocketFactory factory;
        try {
            trust = TrustStore.build(c, provider);
            SSLContext ctx = provider != null ? SSLContext.getInstance("TLS", provider) : SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {trust}, null);
            factory = new ModernSocketFactory(ctx.getSocketFactory());
            modernTls = provider != null;
        } catch (Exception e) {
            throw new IOException("TLS setup failed: " + e);
        }
        Config cfg = Config.get();
        boolean lowRam = cfg == null || cfg.lowRam;
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(lowRam ? 10 : 20);
        dispatcher.setMaxRequestsPerHost(6);
        ConnectionSpec tls = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .allEnabledTlsVersions()
                .allEnabledCipherSuites()
                .build();
        return new OkHttpClient.Builder()
                .sslSocketFactory(factory, trust)
                .connectionSpecs(Arrays.asList(tls, ConnectionSpec.CLEARTEXT))
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(lowRam ? 4 : 8, 90, TimeUnit.SECONDS))
                .cache(new Cache(new File(c.getCacheDir(), "http"), (lowRam ? 12L : 32L) * 1024 * 1024))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor(new CookieBridge())
                .build();
    }

    /**
     * Evicts idle connections; called on memory pressure. Closing a TLS socket writes to the network, which the
     * main thread may not do, so it happens on a worker thread.
     */
    public static void trim() {
        final OkHttpClient cl = client;
        if (cl == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                cl.connectionPool().evictAll();
            }
        }, "net-trim").start();
    }

    /**
     * Sends and stores cookies through the WebView's CookieManager, so both network stacks share one jar
     * (logins made in either work in both). Runs for every network hop, including redirects.
     */
    static final class CookieBridge implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request req = chain.request();
            String url = req.url().toString();
            CookieManager cm = null;
            try {
                cm = CookieManager.getInstance();
            } catch (Throwable ignored) {
                // WebView not initialised yet.
            }
            if (cm != null && cookiesEnabled && req.header("Cookie") == null) {
                String cookie = null;
                try {
                    cookie = cm.getCookie(url);
                } catch (Throwable ignored) {
                    // ignore
                }
                if (cookie != null && !cookie.isEmpty()) req = req.newBuilder().header("Cookie", cookie).build();
            }
            Response resp = chain.proceed(req);
            if (cm != null && cookiesEnabled) {
                List<String> set = resp.headers("Set-Cookie");
                for (String s : set) {
                    try {
                        cm.setCookie(url, s);
                    } catch (Throwable ignored) {
                        // ignore malformed cookies
                    }
                }
            }
            return resp;
        }
    }

    /** Enables every protocol the socket supports (KitKat ships TLS 1.1/1.2 disabled by default). */
    static final class ModernSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        ModernSocketFactory(SSLSocketFactory d) {
            this.d = d;
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                List<String> protocols = new ArrayList<>();
                for (String p : ssl.getSupportedProtocols()) {
                    if (!p.startsWith("SSL")) protocols.add(p);
                }
                if (!protocols.isEmpty()) {
                    try {
                        ssl.setEnabledProtocols(protocols.toArray(new String[0]));
                    } catch (IllegalArgumentException ignored) {
                        // keep defaults
                    }
                }
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return d.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(d.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket() throws IOException {
            return patch(d.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(d.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return patch(d.createSocket(address, port, localAddress, localPort));
        }
    }
}
