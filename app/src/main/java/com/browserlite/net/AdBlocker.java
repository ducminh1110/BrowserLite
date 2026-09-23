package com.browserlite.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;

/**
 * Host based blocker. A small curated list keeps the lookup set tiny (a few hundred KB of heap at most),
 * which matters far more on a 256 MB device than catching every last tracker.
 */
public final class AdBlocker {
    private final HashSet<String> hosts = new HashSet<>();

    public AdBlocker() {}

    public static AdBlocker load(InputStream in) throws IOException {
        AdBlocker b = new AdBlocker();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
        try {
            String line;
            while ((line = r.readLine()) != null) b.addRule(line);
        } finally {
            r.close();
        }
        return b;
    }

    /** Accepts "example.com", "0.0.0.0 example.com" (hosts format) and "||example.com^" (ABP format). */
    public void addRule(String line) {
        if (line == null) return;
        String s = line.trim();
        if (s.isEmpty() || s.charAt(0) == '#' || s.charAt(0) == '!') return;
        int hash = s.indexOf('#');
        if (hash > 0) s = s.substring(0, hash).trim();
        int space = s.lastIndexOf(' ');
        if (space < 0) space = s.lastIndexOf('\t');
        if (space >= 0) s = s.substring(space + 1);
        if (s.startsWith("||")) s = s.substring(2);
        if (s.endsWith("^")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("*.")) s = s.substring(2);
        if (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty() || s.indexOf('/') >= 0 || s.indexOf('.') < 0) return;
        if (s.equals("localhost") || s.equals("0.0.0.0")) return;
        hosts.add(s.toLowerCase(Locale.US));
    }

    public int size() {
        return hosts.size();
    }

    /** True when the host or any parent domain is listed. */
    public boolean isBlocked(String host) {
        if (host == null || host.isEmpty() || hosts.isEmpty()) return false;
        String h = host;
        while (true) {
            if (hosts.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
            if (h.indexOf('.') < 0) return false; // never match a bare TLD
        }
    }
}
