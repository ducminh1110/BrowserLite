package com.browserlite.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** History and bookmarks in one small SQLite file. Writes go through a single background thread. */
public final class Db extends SQLiteOpenHelper {
    private static final int VERSION = 1;
    private static final int MAX_HISTORY = 1500;
    private static Db instance;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();

    public static final class Entry {
        public final String url;
        public final String title;
        public final long time;

        public Entry(String url, String title, long time) {
            this.url = url;
            this.title = title == null || title.isEmpty() ? url : title;
            this.time = time;
        }
    }

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "browser.db", null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT, visits INTEGER DEFAULT 1, last INTEGER)");
        db.execSQL("CREATE INDEX history_last ON history(last)");
        db.execSQL("CREATE TABLE bookmarks (url TEXT PRIMARY KEY, title TEXT, created INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // single version so far
    }

    public static void async(Runnable r) {
        writer.execute(r);
    }

    public void addVisit(final String url, final String title) {
        if (url == null || url.startsWith("data:") || url.startsWith("about:")) return;
        async(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = getWritableDatabase();
                long now = System.currentTimeMillis();
                SQLiteStatement st = db.compileStatement(
                        "UPDATE history SET visits = visits + 1, last = ?, title = COALESCE(?, title) WHERE url = ?");
                st.bindLong(1, now);
                String t = emptyToNull(title);
                if (t == null) st.bindNull(2);
                else st.bindString(2, t);
                st.bindString(3, url);
                boolean updated = st.executeUpdateDelete() > 0;
                st.close();
                if (!updated) {
                    ContentValues v = new ContentValues();
                    v.put("url", url);
                    v.put("title", title);
                    v.put("last", now);
                    db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                    if (Math.random() < 0.05) {
                        db.execSQL("DELETE FROM history WHERE url NOT IN (SELECT url FROM history ORDER BY last DESC LIMIT "
                                + MAX_HISTORY + ")");
                    }
                }
            }
        });
    }

    public void updateTitle(final String url, final String title) {
        if (url == null || title == null || title.isEmpty()) return;
        async(new Runnable() {
            @Override
            public void run() {
                ContentValues v = new ContentValues();
                v.put("title", title);
                getWritableDatabase().update("history", v, "url = ?", new String[] {url});
            }
        });
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private List<Entry> query(String sql, String[] args) {
        ArrayList<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            while (c.moveToNext()) out.add(new Entry(c.getString(0), c.getString(1), c.getLong(2)));
        } finally {
            c.close();
        }
        return out;
    }

    public List<Entry> history(int limit, int offset) {
        return query("SELECT url, title, last FROM history ORDER BY last DESC LIMIT " + limit + " OFFSET " + offset, null);
    }

    public List<Entry> topSites(int limit) {
        return query("SELECT url, title, last FROM history ORDER BY visits DESC, last DESC LIMIT " + limit, null);
    }

    public List<Entry> bookmarks() {
        return query("SELECT url, title, created FROM bookmarks ORDER BY created DESC", null);
    }

    /** Address bar suggestions: bookmarks first, then history, matching URL or title. */
    public List<Entry> suggest(String text, int limit) {
        String like = "%" + text.replace("%", "").replace("_", "") + "%";
        return query("SELECT url, title, 2 AS w FROM bookmarks WHERE url LIKE ?1 OR title LIKE ?1 "
                + "UNION ALL SELECT url, title, 1 AS w FROM history WHERE (url LIKE ?1 OR title LIKE ?1) "
                + "AND url NOT IN (SELECT url FROM bookmarks) ORDER BY w DESC LIMIT " + limit, new String[] {like});
    }

    public boolean isBookmarked(String url) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", new String[] {url});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public void addBookmark(final String url, final String title) {
        async(new Runnable() {
            @Override
            public void run() {
                ContentValues v = new ContentValues();
                v.put("url", url);
                v.put("title", title);
                v.put("created", System.currentTimeMillis());
                getWritableDatabase().insertWithOnConflict("bookmarks", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
        });
    }

    public void removeBookmark(final String url) {
        async(new Runnable() {
            @Override
            public void run() {
                getWritableDatabase().delete("bookmarks", "url = ?", new String[] {url});
            }
        });
    }

    public void removeHistory(final String url) {
        async(new Runnable() {
            @Override
            public void run() {
                getWritableDatabase().delete("history", "url = ?", new String[] {url});
            }
        });
    }

    public void clearHistory() {
        async(new Runnable() {
            @Override
            public void run() {
                getWritableDatabase().delete("history", null, null);
            }
        });
    }
}
