package com.browserlite.web;

import android.content.Context;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.browserlite.net.NetEngine;
import com.browserlite.net.TrustStore;
import com.browserlite.net.UrlUtil;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.tls.OkHostnameVerifier;

/**
 * Second opinion for certificates the KitKat WebView rejects.
 *
 * <p>The WebView validates against the 2013 system store, so perfectly valid Let's Encrypt and other modern
 * certificates show up as "untrusted". We re-validate the exact leaf certificate the WebView received against
 * our current roots (hostname, validity and full chain, fetching intermediates if needed) and only proceed when
 * that passes. Anything that fails here is a genuine error and goes to the user.
 */
public final class CertVerifier {
    public interface Callback {
        void onResult(boolean trusted);
    }

    private static final ConcurrentHashMap<String, Boolean> decisions = new ConcurrentHashMap<>();
    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private CertVerifier() {}

    public static void verify(final Context ctx, SslError error, final Callback cb) {
        final String url = error.getUrl();
        final String host = UrlUtil.host(url);
        final X509Certificate leaf = leafOf(error.getCertificate());
        if (leaf == null || host.isEmpty()) {
            cb.onResult(false);
            return;
        }
        final String key = host + "|" + fingerprint(leaf);
        Boolean known = decisions.get(key);
        if (known != null) {
            cb.onResult(known);
            return;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = check(host, leaf);
                if (!ok && learnChain(ctx, url)) ok = check(host, leaf);
                decisions.put(key, ok);
                final boolean result = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(result);
                    }
                });
            }
        });
    }

    private static boolean check(String host, X509Certificate leaf) {
        TrustStore.IntermediateTrustManager tm = NetEngine.trust();
        if (tm == null) return false;
        try {
            leaf.checkValidity();
            if (!OkHostnameVerifier.INSTANCE.verify(host, leaf)) return false;
            tm.verifyLeaf(leaf);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Connects with our engine once so the server's intermediates get cached. */
    private static boolean learnChain(Context ctx, String url) {
        try {
            OkHttpClient client = NetEngine.client(ctx);
            String origin = UrlUtil.origin(url);
            Request req = new Request.Builder().url(origin + "/").head().build();
            Response r = client.newCall(req).execute();
            try {
                if (r.handshake() == null) return false;
                TrustStore.IntermediateTrustManager tm = NetEngine.trust();
                if (tm != null) tm.rememberChain(r.handshake().peerCertificates().toArray(new Certificate[0]));
                return true;
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    static X509Certificate leafOf(SslCertificate cert) {
        if (cert == null) return null;
        try {
            Bundle b = SslCertificate.saveState(cert);
            byte[] der = b == null ? null : b.getByteArray("x509-certificate");
            if (der == null) return null;
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    private static String fingerprint(X509Certificate c) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(c.hashCode());
        }
    }
}
