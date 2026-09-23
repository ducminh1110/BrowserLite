package com.browserlite.video;

import android.content.Context;
import android.util.Log;

import com.browserlite.net.ChunkCache;
import com.browserlite.net.NetEngine;
import com.browserlite.net.YouTube;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Renews the link of one YouTube stream when the video servers start refusing it midway (403: the link was revoked,
 * expired, or its client lost favour). Asks again, trying the other app clients first, for the very same format and
 * size, so the bytes continue exactly where they stopped.
 */
public final class YouTubeLinks implements ChunkCache.LinkSource {
    private static final String TAG = "YouTubeLinks";

    private final Context app;
    private final String id;
    private final int itag;
    private final long length;
    /** Clients whose links failed for this video; shared with the player so a full restart avoids them too. */
    private final Set<String> failed;
    private volatile String client, userAgent;

    public YouTubeLinks(Context c, String id, YouTube.Stream s, String client, String userAgent, Set<String> failed) {
        app = c.getApplicationContext();
        this.id = id;
        this.itag = s.itag;
        this.length = s.length;
        this.client = client;
        this.userAgent = userAgent;
        this.failed = failed != null ? failed : new HashSet<String>();
    }

    public String client() {
        return client;
    }

    public String userAgent() {
        return userAgent;
    }

    @Override
    public ChunkCache.Link freshLink() throws IOException {
        Set<String> skip;
        synchronized (failed) {
            if (client != null && !client.isEmpty()) failed.add(client);
            skip = new HashSet<>(failed);
        }
        Locale l = Locale.getDefault();
        String gl = l.getCountry().isEmpty() ? "US" : l.getCountry();
        YouTube.Video v = YouTube.player(NetEngine.youtube(app), id, l.getLanguage(), gl, true, skip);
        if (v.error != null) {
            Log.w(TAG, "no new link for " + id + ": " + v.error);
            return null;
        }
        for (YouTube.Stream s : v.streams) {
            // Same format and size: the same file, so the bytes already stored stay valid.
            if (s.itag == itag && (length <= 0 || s.length <= 0 || s.length == length)) {
                client = v.client;
                userAgent = v.userAgent;
                Log.i(TAG, "new link for itag " + itag + " from " + v.client);
                return new ChunkCache.Link(s.url, v.userAgent);
            }
        }
        Log.w(TAG, "new links for " + id + " lack itag " + itag);
        return null;
    }
}
