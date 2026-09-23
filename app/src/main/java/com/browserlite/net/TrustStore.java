package com.browserlite.net;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Trust anchors for the built-in network engine.
 *
 * <p>Android 4.4's system store predates ISRG Root X1 (Let's Encrypt) and many other current roots, which
 * is why so many HTTPS sites fail on KitKat today. We ship Mozilla's current root program instead, plus any
 * CA the user installed, and fetch missing intermediates through AIA like desktop browsers do.
 */
public final class TrustStore {
    private TrustStore() {}

    public static IntermediateTrustManager build(Context ctx, Provider provider) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int count = 0;
        DataInputStream in = new DataInputStream(new BufferedInputStream(ctx.getAssets().open("cacerts.bin"), 16384));
        try {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'B' || magic[1] != 'L' || magic[2] != 'C' || magic[3] != 'A') {
                throw new IOException("bad cacerts.bin");
            }
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                int len = in.readUnsignedShort();
                byte[] der = new byte[len];
                in.readFully(der);
                try {
                    Certificate c = cf.generateCertificate(new ByteArrayInputStream(der));
                    ks.setCertificateEntry("mozilla" + i, c);
                    count++;
                } catch (CertificateException ignored) {
                    // A root this old platform cannot parse is simply skipped.
                }
            }
        } finally {
            in.close();
        }
        try {
            KeyStore system = KeyStore.getInstance("AndroidCAStore");
            system.load(null, null);
            Enumeration<String> aliases = system.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (alias.startsWith("user:")) {
                    Certificate c = system.getCertificate(alias);
                    if (c != null) ks.setCertificateEntry(alias, c);
                }
            }
        } catch (Exception ignored) {
            // No system store access: bundled roots are enough.
        }
        if (count == 0) throw new IOException("no roots");

        TrustManagerFactory tmf = null;
        if (provider != null) {
            try {
                tmf = TrustManagerFactory.getInstance("PKIX", provider);
                tmf.init(ks);
            } catch (Exception e) {
                tmf = null;
            }
        }
        if (tmf == null) {
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
        }
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return new IntermediateTrustManager((X509TrustManager) tm);
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /**
     * Wraps the real trust manager: remembers intermediates from verified chains and completes chains that
     * servers send without their intermediate by following the Authority Information Access URL.
     */
    public static final class IntermediateTrustManager implements X509TrustManager {
        private final X509TrustManager base;
        private final Map<String, X509Certificate> intermediates = new LinkedHashMap<String, X509Certificate>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, X509Certificate> eldest) {
                return size() > 160;
            }
        };

        IntermediateTrustManager(X509TrustManager base) {
            this.base = base;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            base.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                base.checkServerTrusted(chain, authType);
                remember(chain);
            } catch (CertificateException e) {
                X509Certificate[] completed = complete(chain);
                if (completed == null) throw e;
                base.checkServerTrusted(completed, authType);
                remember(completed);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return base.getAcceptedIssuers();
        }

        private void remember(X509Certificate[] chain) {
            synchronized (intermediates) {
                for (int i = 1; i < chain.length; i++) {
                    X509Certificate c = chain[i];
                    if (c.getSubjectX500Principal().equals(c.getIssuerX500Principal())) continue;
                    intermediates.put(c.getSubjectX500Principal().getName(), c);
                }
            }
        }

        public void rememberChain(Certificate[] chain) {
            ArrayList<X509Certificate> list = new ArrayList<>();
            for (Certificate c : chain) if (c instanceof X509Certificate) list.add((X509Certificate) c);
            remember(list.toArray(new X509Certificate[0]));
        }

        private X509Certificate knownIssuerOf(X509Certificate cert) {
            X509Certificate issuer;
            synchronized (intermediates) {
                issuer = intermediates.get(cert.getIssuerX500Principal().getName());
            }
            if (issuer == null) return null;
            try {
                cert.verify(issuer.getPublicKey());
                return issuer;
            } catch (Exception e) {
                return null;
            }
        }

        /** Tries to extend the chain with cached or AIA-fetched intermediates; null when nothing was added. */
        X509Certificate[] complete(X509Certificate[] chain) {
            if (chain == null || chain.length == 0) return null;
            List<X509Certificate> list = new ArrayList<>(Arrays.asList(chain));
            boolean added = false;
            for (int hop = 0; hop < 3; hop++) {
                X509Certificate last = list.get(list.size() - 1);
                if (last.getSubjectX500Principal().equals(last.getIssuerX500Principal())) break;
                X509Certificate issuer = knownIssuerOf(last);
                if (issuer == null) issuer = fetchIssuer(last);
                if (issuer == null) break;
                list.add(issuer);
                added = true;
                X509Certificate[] candidate = list.toArray(new X509Certificate[0]);
                try {
                    base.checkServerTrusted(candidate, "ECDHE_RSA");
                    return candidate;
                } catch (CertificateException ignored) {
                    // keep walking up
                }
            }
            return added ? list.toArray(new X509Certificate[0]) : null;
        }

        /** Verifies a certificate chain that starts with {@code leaf} (used for WebView SSL errors). */
        public void verifyLeaf(X509Certificate leaf) throws CertificateException {
            X509Certificate[] chain = {leaf};
            String auth = "RSA".equals(leaf.getPublicKey().getAlgorithm()) ? "ECDHE_RSA" : "ECDHE_ECDSA";
            try {
                base.checkServerTrusted(chain, auth);
                return;
            } catch (CertificateException e) {
                X509Certificate[] completed = complete(chain);
                if (completed == null) throw e;
                base.checkServerTrusted(completed, auth);
            }
        }

        private X509Certificate fetchIssuer(X509Certificate cert) {
            String url = caIssuersUrl(cert);
            if (url == null || !url.startsWith("http://")) return null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setInstanceFollowRedirects(true);
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 64 * 1024) return null;
                }
                in.close();
                byte[] data = bos.toByteArray();
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                Collection<? extends Certificate> certs;
                try {
                    certs = cf.generateCertificates(new ByteArrayInputStream(data));
                } catch (CertificateException e) {
                    certs = java.util.Collections.singletonList(cf.generateCertificate(new ByteArrayInputStream(data)));
                }
                for (Certificate c : certs) {
                    if (!(c instanceof X509Certificate)) continue;
                    X509Certificate x = (X509Certificate) c;
                    if (!x.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) continue;
                    cert.verify(x.getPublicKey());
                    synchronized (intermediates) {
                        intermediates.put(x.getSubjectX500Principal().getName(), x);
                    }
                    return x;
                }
            } catch (Exception ignored) {
                // Offline or broken AIA: the original error stands.
            } finally {
                if (conn != null) conn.disconnect();
            }
            return null;
        }
    }

    private static final byte[] CA_ISSUERS_OID = {0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02};

    /** Extracts the first caIssuers URI from the AuthorityInfoAccess extension. */
    static String caIssuersUrl(X509Certificate cert) {
        byte[] ext = cert.getExtensionValue("1.3.6.1.5.5.7.1.1");
        if (ext == null) return null;
        outer:
        for (int i = 0; i + CA_ISSUERS_OID.length < ext.length; i++) {
            for (int k = 0; k < CA_ISSUERS_OID.length; k++) {
                if (ext[i + k] != CA_ISSUERS_OID[k]) continue outer;
            }
            int p = i + CA_ISSUERS_OID.length;
            if (p >= ext.length || (ext[p] & 0xff) != 0x86) continue;
            p++;
            int len = ext[p++] & 0xff;
            if ((len & 0x80) != 0) {
                int bytes = len & 0x7f;
                len = 0;
                for (int b = 0; b < bytes && p < ext.length; b++) len = (len << 8) | (ext[p++] & 0xff);
            }
            if (p + len > ext.length || len <= 0) return null;
            try {
                return new String(ext, p, len, "US-ASCII");
            } catch (java.io.UnsupportedEncodingException e) {
                return null;
            }
        }
        return null;
    }
}
