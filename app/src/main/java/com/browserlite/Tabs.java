package com.browserlite;

import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tabs are just records. Only the current tab owns a live WebView; the others keep a saved back/forward
 * state, so ten open tabs cost about as much RAM as one.
 */
public final class Tabs {
    public static final class Tab {
        private static long nextId = 1;
        public final long id = nextId++;
        public String url;
        public String title;
        /** WebView.saveState() of a background tab; null for tabs never shown. */
        public Bundle state;
        public long lastUsed = System.currentTimeMillis();

        Tab(String url, String title) {
            this.url = url;
            this.title = title;
        }
    }

    private final ArrayList<Tab> list = new ArrayList<>();
    private int current = -1;

    public List<Tab> all() {
        return list;
    }

    public int size() {
        return list.size();
    }

    public Tab current() {
        return current >= 0 && current < list.size() ? list.get(current) : null;
    }

    public int currentIndex() {
        return current;
    }

    public Tab add(String url, String title, boolean makeCurrent, int max) {
        Tab t = new Tab(url, title);
        int insertAt = current >= 0 ? current + 1 : list.size();
        list.add(insertAt, t);
        if (current >= insertAt) current++;
        if (makeCurrent || current < 0) current = insertAt;
        return t;
    }

    /** Removes the least recently used background tab when over the limit. Returns true if one was closed. */
    public boolean enforceLimit(int max) {
        if (list.size() <= max) return false;
        int oldest = -1;
        for (int i = 0; i < list.size(); i++) {
            if (i == current) continue;
            if (oldest < 0 || list.get(i).lastUsed < list.get(oldest).lastUsed) oldest = i;
        }
        if (oldest < 0) return false;
        remove(oldest);
        return true;
    }

    public void select(int index) {
        if (index >= 0 && index < list.size()) {
            current = index;
            list.get(index).lastUsed = System.currentTimeMillis();
        }
    }

    public int indexOf(Tab t) {
        return list.indexOf(t);
    }

    /** Removes a tab; returns the index of the tab that becomes current (or -1 when none left). */
    public int remove(int index) {
        if (index < 0 || index >= list.size()) return current;
        list.remove(index);
        if (list.isEmpty()) {
            current = -1;
        } else if (index < current) {
            current--;
        } else if (index == current) {
            current = Math.min(index, list.size() - 1);
        }
        return current;
    }

    public void clear() {
        list.clear();
        current = -1;
    }

    // ------------------------------------------------------------------ persistence (URLs only)

    private static File file(Context c) {
        return new File(c.getFilesDir(), "tabs.json");
    }

    public void save(Context c) {
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : list) {
                JSONObject o = new JSONObject();
                o.put("url", t.url == null ? "" : t.url);
                o.put("title", t.title == null ? "" : t.title);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("tabs", arr);
            root.put("current", current);
            File tmp = new File(c.getFilesDir(), "tabs.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(root.toString().getBytes("UTF-8"));
            out.close();
            if (!tmp.renameTo(file(c))) tmp.delete();
        } catch (Exception ignored) {
            // Losing the tab list is not worth crashing for.
        }
    }

    public boolean load(Context c) {
        File f = file(c);
        if (!f.exists()) return false;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            in.close();
            JSONObject root = new JSONObject(new String(buf, 0, n, "UTF-8"));
            JSONArray arr = root.getJSONArray("tabs");
            list.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Tab(o.optString("url", ""), o.optString("title", "")));
            }
            current = list.isEmpty() ? -1 : Math.max(0, Math.min(root.optInt("current", 0), list.size() - 1));
            return !list.isEmpty();
        } catch (IOException | org.json.JSONException e) {
            return false;
        }
    }

    public void saveToBundle(Bundle out, Bundle currentState) {
        ArrayList<String> urls = new ArrayList<>(), titles = new ArrayList<>();
        for (Tab t : list) {
            urls.add(t.url == null ? "" : t.url);
            titles.add(t.title == null ? "" : t.title);
        }
        out.putStringArrayList("tab_urls", urls);
        out.putStringArrayList("tab_titles", titles);
        out.putInt("tab_current", current);
        if (currentState != null) out.putBundle("tab_state", currentState);
    }

    /** Returns the saved WebView state of the current tab, if any. */
    public Bundle restoreFromBundle(Bundle in) {
        ArrayList<String> urls = in.getStringArrayList("tab_urls");
        ArrayList<String> titles = in.getStringArrayList("tab_titles");
        if (urls == null || urls.isEmpty()) return null;
        list.clear();
        for (int i = 0; i < urls.size(); i++) {
            list.add(new Tab(urls.get(i), titles != null && i < titles.size() ? titles.get(i) : ""));
        }
        current = Math.max(0, Math.min(in.getInt("tab_current", 0), list.size() - 1));
        return in.getBundle("tab_state");
    }
}
