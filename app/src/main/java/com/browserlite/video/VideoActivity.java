package com.browserlite.video;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.browserlite.BrowserApp;
import com.browserlite.Config;
import com.browserlite.MemoryState;
import com.browserlite.Prefs;
import com.browserlite.R;
import com.browserlite.net.NetEngine;
import com.browserlite.net.StreamPicker;
import com.browserlite.net.UrlUtil;
import com.browserlite.net.YouTube;
import com.browserlite.ui.Icon;
import com.browserlite.ui.Ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen player for YouTube and plain video/audio links. Uses the device's decoders when the ROM has the right
 * ones (cheapest on battery), the bundled FFmpeg decoder when it doesn't or when the system player fails, and sound
 * only when no picture can be decoded at all.
 */
public final class VideoActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "VideoActivity";
    public static final String EXTRA_YT = "yt", EXTRA_URL = "url", EXTRA_TYPE = "type", EXTRA_TITLE = "title",
            EXTRA_REFERER = "referer", EXTRA_AUDIO = "audio";

    public static void playYouTube(Context c, String id, String title, boolean audio) {
        Intent i = new Intent(c, VideoActivity.class);
        i.putExtra(EXTRA_YT, id);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_AUDIO, audio);
        c.startActivity(i);
    }

    public static void playUrl(Context c, String url, String type, String title, String referer, boolean audio) {
        Intent i = new Intent(c, VideoActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_TYPE, type);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_REFERER, referer);
        i.putExtra(EXTRA_AUDIO, audio);
        c.startActivity(i);
    }

    // ------------------------------------------------------------------ engines

    interface Listener {
        void onReady(int width, int height);

        void onEnded();

        void onError(String message, boolean decoderProblem);

        void onBuffering(boolean buffering);
    }

    interface Engine {
        void start(String url, long startMs);

        void setSurface(SurfaceHolder holder);

        void play();

        void pause();

        boolean isPlaying();

        void seekTo(long ms);

        long position();

        long duration();

        void tick();

        void release();
    }

    /** The ROM's MediaPlayer (hardware decoders when present). */
    private final class SystemEngine implements Engine {
        private MediaPlayer mp;
        private boolean prepared, wantPlay = true, released;
        private long startAt;

        @Override
        public void start(String url, long startMs) {
            startAt = startMs;
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (!cfg.videoSound) mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(m -> {
                if (released) return;
                prepared = true;
                if (startAt > 0) m.seekTo((int) startAt);
                if (wantPlay) m.start();
                listener.onReady(m.getVideoWidth(), m.getVideoHeight());
            });
            mp.setOnVideoSizeChangedListener((m, w, h) -> {
                if (!released && w > 0 && h > 0) listener.onReady(w, h);
            });
            mp.setOnCompletionListener(m -> {
                if (!released) listener.onEnded();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (released) return true;
                boolean network = extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT
                        || what == 100 /* server died */;
                listener.onError("MediaPlayer " + what + "/" + extra, !network);
                return true;
            });
            mp.setOnInfoListener((m, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) listener.onBuffering(true);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) listener.onBuffering(false);
                return false;
            });
            try {
                if (url.startsWith("content:")) mp.setDataSource(VideoActivity.this, android.net.Uri.parse(url));
                else mp.setDataSource(url);
                if (surfaceReady && !audioOnly) mp.setDisplay(surface.getHolder());
                mp.prepareAsync();
            } catch (IOException | RuntimeException e) {
                listener.onError(String.valueOf(e.getMessage()), true);
            }
        }

        @Override
        public void setSurface(SurfaceHolder holder) {
            if (mp == null) return;
            try {
                mp.setDisplay(audioOnly ? null : holder);
            } catch (RuntimeException ignored) {
                // player already torn down
            }
        }

        @Override
        public void play() {
            wantPlay = true;
            if (prepared) mp.start();
        }

        @Override
        public void pause() {
            wantPlay = false;
            if (prepared && mp.isPlaying()) mp.pause();
        }

        @Override
        public boolean isPlaying() {
            return prepared ? mp.isPlaying() : wantPlay;
        }

        @Override
        public void seekTo(long ms) {
            if (prepared) mp.seekTo((int) ms);
            else startAt = ms;
        }

        @Override
        public long position() {
            return prepared ? mp.getCurrentPosition() : startAt;
        }

        @Override
        public long duration() {
            return prepared ? Math.max(0, mp.getDuration()) : 0;
        }

        @Override
        public void tick() {}

        @Override
        public void release() {
            released = true;
            if (mp == null) return;
            final MediaPlayer m = mp;
            mp = null;
            try {
                m.reset();
            } catch (RuntimeException ignored) {
                // ignore
            }
            m.release();
        }
    }

    /** Bundled FFmpeg: decodes in software, draws into the SurfaceView, plays sound through an AudioTrack. */
    private final class BuiltinEngine implements Engine {
        private volatile long h;
        private volatile boolean released, paused;
        private AudioTrack track;
        private Thread audioThread;
        private long startAt, duration;
        private boolean ended, buffering;

        @Override
        public void start(final String url, final long startMs) {
            startAt = startMs;
            final boolean gray = cfg.videoGray;
            final int fps = cfg.videoFps;
            final boolean video = !audioOnly;
            final boolean sound = cfg.videoSound;
            final int band = MemoryState.update(VideoActivity.this, 0);
            // Free RAM decides how much work is kept in flight: one decoder thread and a small read-ahead when short.
            final int threads = band == MemoryState.CRITICAL ? 1 : 0;
            final int queueKb = band == MemoryState.ROOMY ? 4096 : band == MemoryState.TIGHT ? 2048 : 1024;
            new Thread(() -> {
                final long handle = NativePlayer.open(url, gray, fps, video, sound, threads, queueKb);
                if (handle == 0 || NativePlayer.state(handle) == NativePlayer.ERROR) {
                    final String err = handle == 0 ? "out of memory" : NativePlayer.error(handle);
                    if (handle != 0) NativePlayer.close(handle);
                    handler.post(() -> {
                        if (!released) listener.onError(err, false);
                    });
                    return;
                }
                final int[] info = NativePlayer.info(handle);
                handler.post(() -> {
                    if (released) {
                        closeLater(handle, null, null);
                        return;
                    }
                    h = handle;
                    duration = info[2];
                    if (surfaceReady && video) NativePlayer.setSurface(h, surface.getHolder().getSurface());
                    if (startAt > 0) NativePlayer.seek(h, startAt);
                    NativePlayer.start(h);
                    if (info[4] == 1 && sound) startAudio(info[5], info[6]);
                    if (paused) NativePlayer.pause(h, true);
                    listener.onReady(info[0], info[1]);
                });
            }, "builtin-open").start();
        }

        private void startAudio(int rate, int channels) {
            int config = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int min = AudioTrack.getMinBufferSize(rate, config, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = rate * channels / 2;
            int size = Math.max(min * 2, rate * channels * 2 / 4); // >= 250 ms
            try {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, config, AudioFormat.ENCODING_PCM_16BIT, size,
                        AudioTrack.MODE_STREAM);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("audio track not initialized");
                if (!paused) track.play();
            } catch (RuntimeException e) {
                // No usable audio output (or this rate/layout refused): show the picture on its own clock.
                Log.w(TAG, "no audio output: " + e.getMessage());
                if (track != null) track.release();
                track = null;
                NativePlayer.dropAudio(h);
                return;
            }
            final AudioTrack t = track;
            final short[] buf = new short[1024 * Math.max(1, channels)];
            audioThread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                while (!released) {
                    if (paused) {
                        sleep(20);
                        continue;
                    }
                    long head = t.getPlaybackHeadPosition() & 0xffffffffL;
                    int n = NativePlayer.fillAudio(h, buf, head);
                    if (released) break;
                    if (n == -2) {
                        t.pause();
                        t.flush();
                        if (!paused) t.play();
                    } else if (n > 0) {
                        t.write(buf, 0, n);
                    } else {
                        sleep(n == 0 ? 10 : 60);
                    }
                }
            }, "builtin-audio");
            audioThread.start();
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
                // loop re-checks
            }
        }

        @Override
        public void setSurface(SurfaceHolder holder) {
            if (h != 0) NativePlayer.setSurface(h, holder == null || audioOnly ? null : holder.getSurface());
        }

        @Override
        public void play() {
            paused = false;
            ended = false;
            if (h != 0) NativePlayer.pause(h, false);
            if (track != null) track.play();
        }

        @Override
        public void pause() {
            paused = true;
            if (h != 0) NativePlayer.pause(h, true);
            if (track != null) track.pause();
        }

        @Override
        public boolean isPlaying() {
            return !paused && !ended;
        }

        @Override
        public void seekTo(long ms) {
            ended = false;
            if (h != 0) NativePlayer.seek(h, ms);
            else startAt = ms;
        }

        @Override
        public long position() {
            return h != 0 ? NativePlayer.position(h) : startAt;
        }

        @Override
        public long duration() {
            return duration;
        }

        @Override
        public void tick() {
            if (h == 0) return;
            int st = NativePlayer.state(h);
            boolean b = st == NativePlayer.BUFFERING;
            if (b != buffering) {
                buffering = b;
                listener.onBuffering(b);
            }
            if (st == NativePlayer.ENDED && !ended) {
                ended = true;
                listener.onEnded();
            }
        }

        @Override
        public void release() {
            released = true;
            long handle = h;
            h = 0;
            AudioTrack t = track;
            track = null;
            if (t != null) {
                try {
                    t.pause();
                    t.flush();
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            closeLater(handle, audioThread, t);
        }

        /** Joining the decoder threads can wait on the network: never on the UI thread. */
        private void closeLater(final long handle, final Thread audio, final AudioTrack t) {
            new Thread(() -> {
                if (audio != null) {
                    try {
                        audio.join(2000);
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                }
                if (t != null) t.release();
                if (handle != 0) NativePlayer.close(handle);
            }, "builtin-close").start();
        }
    }

    // ------------------------------------------------------------------ state

    private final Handler handler = new Handler();
    private Config cfg;
    private String ytId, directUrl, directType, referer, titleText = "";
    private boolean audioOnly, wantAudioOnly;
    private boolean builtin, forceBuiltin, triedBuiltin, triedAudio, refreshed;
    private Engine engine;
    private String localUrl;
    private boolean surfaceReady;
    private int videoW, videoH;
    private boolean userSeeking, ended;
    private long lastPosition;
    private String posKey;

    private FrameLayout stage;
    private SurfaceView surface;
    private LinearLayout topBar, controls, audioPanel;
    private TextView title, status, time, engineLabel, audioTitle, modeButton;
    private ImageView playButton;
    private SeekBar seek;
    private Icon playIcon, pauseIcon;

    private final Listener listener = new Listener() {
        @Override
        public void onReady(int width, int height) {
            status.setVisibility(View.GONE);
            if (width > 0 && height > 0) {
                videoW = width;
                videoH = height;
                layoutSurface();
            }
            updateControls();
            hideControlsSoon();
        }

        @Override
        public void onEnded() {
            ended = true;
            clearPosition();
            status.setText(R.string.player_ended);
            status.setVisibility(View.VISIBLE);
            showControls();
            updateControls();
        }

        @Override
        public void onError(String message, boolean decoderProblem) {
            onEngineError(message, decoderProblem);
        }

        @Override
        public void onBuffering(boolean b) {
            if (ended) return;
            status.setText(R.string.player_buffering);
            status.setVisibility(b ? View.VISIBLE : View.GONE);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        BrowserApp.applyLocale(this);
        super.onCreate(saved);
        cfg = Config.get();
        Intent i = getIntent();
        ytId = i.getStringExtra(EXTRA_YT);
        directUrl = i.getStringExtra(EXTRA_URL);
        directType = i.getStringExtra(EXTRA_TYPE);
        referer = i.getStringExtra(EXTRA_REFERER);
        if (ytId == null && directUrl == null && i.getData() != null) {
            // Opened from a file manager or another app ("Open with BrowserLite").
            String data = i.getData().toString();
            ytId = YouTube.videoId(data);
            if (ytId == null) {
                directUrl = data;
                directType = i.getType();
            }
        }
        String t = i.getStringExtra(EXTRA_TITLE);
        titleText = t != null && !t.isEmpty() ? t : directUrl != null ? lastSegment(directUrl) : "";
        wantAudioOnly = cfg.videoSound && i.getBooleanExtra(EXTRA_AUDIO, cfg.videoAudioDefault);
        audioOnly = wantAudioOnly;
        posKey = ytId != null ? "yt:" + ytId : directUrl != null ? "url:" + Integer.toHexString(directUrl.hashCode()) : null;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        buildUi();
        showA2HintOnce();
        resolveAndPlay(savedPosition());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) {
            lastPosition = engine.position();
            engine.pause();
            savePosition(lastPosition);
        }
        updateControls();
        showControls();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseEngine();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        stage.post(this::layoutSurface);
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.WHITE);
        topBar.addView(Ui.iconButton(this, Icon.CLOSE, "close", v -> finish()), new LinearLayout.LayoutParams(dp(46), dp(46)));
        title = Ui.text(this, titleText, 16, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        engineLabel = Ui.text(this, "", 12, false);
        engineLabel.setPadding(dp(6), 0, dp(10), 0);
        topBar.addView(engineLabel);
        column.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        stage.addView(surface, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        audioPanel = new LinearLayout(this);
        audioPanel.setOrientation(LinearLayout.VERTICAL);
        audioPanel.setGravity(Gravity.CENTER);
        audioPanel.setBackgroundColor(Color.WHITE);
        ImageView note = new ImageView(this);
        note.setImageDrawable(new Icon(Icon.NOTE, getResources().getDisplayMetrics().density * 4));
        audioPanel.addView(note);
        audioTitle = Ui.text(this, titleText, 20, true);
        audioTitle.setGravity(Gravity.CENTER);
        audioTitle.setPadding(dp(20), dp(16), dp(20), 0);
        audioPanel.addView(audioTitle);
        audioPanel.setVisibility(View.GONE);
        stage.addView(audioPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = Ui.text(this, getString(R.string.player_loading), 17, true);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.BLACK);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(10), dp(16), dp(10));
        stage.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        stage.setOnClickListener(v -> toggleControls());
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::layoutSurface);
        });
        column.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(Color.WHITE);
        controls.setPadding(dp(4), dp(2), dp(4), dp(2));
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && engine != null) time.setText(format(engine.duration() * p / 1000) + " / " + format(engine.duration()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
                handler.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (engine != null && engine.duration() > 0) {
                    ended = false;
                    engine.seekTo(engine.duration() * s.getProgress() / 1000);
                }
                hideControlsSoon();
            }
        });
        controls.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        playIcon = new Icon(Icon.PLAY, density);
        pauseIcon = new Icon(Icon.PAUSE, density);
        playButton = Ui.iconButton(this, Icon.PAUSE, "play", v -> togglePlay());
        row.addView(playButton, new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.addView(Ui.iconButton(this, Icon.REWIND, "back 10 s", v -> skip(-10_000)), new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.addView(Ui.iconButton(this, Icon.FAST_FORWARD, "forward 10 s", v -> skip(10_000)), new LinearLayout.LayoutParams(dp(52), dp(48)));
        time = Ui.text(this, "0:00", 14, false);
        time.setPadding(dp(8), 0, dp(8), 0);
        time.setSingleLine(true);
        row.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        modeButton = Ui.button(this, "", false, v -> switchAudioMode());
        modeButton.setTextSize(14);
        row.addView(modeButton);
        controls.addView(row);
        View line = new View(this);
        line.setBackgroundColor(Color.BLACK);
        column.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        column.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        applyAudioModeUi();
        handler.post(ticker);
    }

    private void applyAudioModeUi() {
        audioPanel.setVisibility(audioOnly ? View.VISIBLE : View.GONE);
        surface.setVisibility(audioOnly ? View.INVISIBLE : View.VISIBLE);
        modeButton.setText(audioOnly ? R.string.player_video : R.string.player_audio);
        modeButton.setVisibility(cfg.videoSound ? View.VISIBLE : View.GONE);
        audioTitle.setText(titleText);
        title.setText(titleText);
    }

    private void layoutSurface() {
        int sw = stage.getWidth(), sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) return;
        int w = videoW > 0 ? videoW : 16, h = videoH > 0 ? videoH : 9;
        int tw = sw, th = (int) ((long) sw * h / w);
        if (th > sh) {
            th = sh;
            tw = (int) ((long) sh * w / h);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != tw || lp.height != th) {
            lp.width = tw;
            lp.height = th;
            surface.setLayoutParams(lp);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (engine != null) {
                engine.tick();
                if (!userSeeking) {
                    long d = engine.duration(), p = engine.position();
                    if (d > 0) {
                        seek.setProgress((int) Math.min(1000, p * 1000 / d));
                        time.setText(format(p) + " / " + format(d));
                    } else {
                        time.setText(format(p) + (p > 0 ? "" : ""));
                    }
                }
                updatePlayIcon();
            }
            handler.postDelayed(this, controls.getVisibility() == View.VISIBLE ? 500 : 2000);
        }
    };

    private boolean playing;

    private void updatePlayIcon() {
        boolean p = engine != null && engine.isPlaying() && !ended;
        if (p != playing) {
            playing = p;
            playButton.setImageDrawable(p ? pauseIcon : playIcon);
        }
    }

    private void updateControls() {
        updatePlayIcon();
        engineLabel.setText(engine == null ? "" : getString(builtin ? R.string.player_engine_builtin : R.string.player_engine_system));
    }

    private final Runnable hideControls = () -> {
        if (!audioOnly && engine != null && engine.isPlaying() && !userSeeking) {
            controls.setVisibility(View.GONE);
            topBar.setVisibility(View.GONE);
        }
    };

    private void hideControlsSoon() {
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 4000);
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
    }

    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE && !audioOnly) {
            hideControls.run();
        } else {
            showControls();
            hideControlsSoon();
        }
    }

    private void togglePlay() {
        if (engine == null) return;
        if (ended) {
            ended = false;
            status.setVisibility(View.GONE);
            engine.seekTo(0);
            engine.play();
        } else if (engine.isPlaying()) {
            engine.pause();
        } else {
            engine.play();
        }
        updatePlayIcon();
        hideControlsSoon();
    }

    private void skip(long delta) {
        if (engine == null) return;
        long d = engine.duration();
        long target = Math.max(0, engine.position() + delta);
        if (d > 0) target = Math.min(target, Math.max(0, d - 1000));
        ended = false;
        engine.seekTo(target);
        hideControlsSoon();
    }

    private static String format(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    togglePlay();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    skip(-10_000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    skip(10_000);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ SurfaceHolder

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (engine != null) engine.setSurface(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (engine != null) engine.setSurface(null);
    }

    // ------------------------------------------------------------------ choosing and starting

    private void resolveAndPlay(final long startMs) {
        status.setText(R.string.player_loading);
        status.setVisibility(View.VISIBLE);
        final boolean refresh = refreshed;
        new Thread(() -> {
            try {
                final List<YouTube.Stream> streams = new ArrayList<>();
                String name = "media";
                if (ytId != null) {
                    Locale l = Locale.getDefault();
                    String gl = l.getCountry().isEmpty() ? "US" : l.getCountry();
                    YouTube.Video v = YouTube.player(NetEngine.client(this), ytId, l.getLanguage(), gl, refresh);
                    if (v.error != null) {
                        boolean blocked = YouTube.isBotCheck(v.error) || v.error.contains("403");
                        fail(blocked ? getString(R.string.yt_bot_check) : getString(R.string.player_error, v.error));
                        return;
                    }
                    if (!v.title.isEmpty()) titleText = v.title;
                    streams.addAll(v.streams);
                    if (v.hls != null && streams.isEmpty()) streams.add(hlsStream(v.hls));
                    name = "video.mp4";
                } else if (directUrl != null) {
                    streams.add(directStream(directUrl, directType));
                    name = lastSegment(directUrl);
                }
                StreamPicker.Caps device = MediaCaps.device();
                final int height = videoHeight();
                String mode = forceBuiltin ? "builtin" : cfg.videoDecoder;
                // Load the bundled decoder only when it may be needed: devices with a hardware H.264 (Main)
                // decoder never touch it.
                boolean hwAvc = device.hwAvc && (device.avcMain || device.avcHigh);
                boolean builtinOk = !mode.equals("system") && (mode.equals("builtin") || !hwAvc) && NativePlayer.available();
                StreamPicker.Choice c = StreamPicker.pick(streams, device, mode, audioOnly, cfg.videoSound, height, builtinOk);
                if ((c == null || c.reason != null) && !builtinOk && !mode.equals("system") && NativePlayer.available()) {
                    c = StreamPicker.pick(streams, device, mode, audioOnly, cfg.videoSound, height, true);
                }
                if (c == null) {
                    fail(getString(R.string.player_no_decoder) + "\n" + MediaCaps.describe());
                    return;
                }
                final StreamPicker.Choice choice = c;
                final String fileName = name;
                handler.post(() -> start(choice, fileName, startMs));
            } catch (IOException e) {
                fail(getString(R.string.player_error, String.valueOf(e.getMessage())));
            }
        }, "video-resolve").start();
    }

    /**
     * Picture height to aim for. "Auto" follows the CPU and free RAM: a single core (Cortex-A8/A9 at ~1 GHz) decodes
     * 240p comfortably in software, two or more cores 360p; with RAM nearly gone everything drops a step.
     */
    private int videoHeight() {
        if (cfg.videoHeight > 0) return cfg.videoHeight;
        int cores = Runtime.getRuntime().availableProcessors();
        int band = MemoryState.update(this, 0);
        int h = cores >= 2 ? 360 : 240;
        if (band == MemoryState.CRITICAL) h = cores >= 2 ? 240 : 144;
        Log.i(TAG, "auto video height " + h + "p (" + cores + " cores, " + MemoryState.availMb() + " MB free)");
        return h;
    }

    private static YouTube.Stream hlsStream(String url) {
        YouTube.Stream s = new YouTube.Stream();
        s.url = url;
        s.mime = "application/x-mpegurl";
        s.video = s.audio = true;
        return s;
    }

    private static YouTube.Stream directStream(String url, String type) {
        YouTube.Stream s = new YouTube.Stream();
        s.url = url;
        String t = type == null ? "" : type.trim();
        if (t.isEmpty()) {
            String ext = UrlUtil.extension(url);
            switch (ext) {
                case "mp3": t = "audio/mpeg"; break;
                case "m4a": case "aac": t = "audio/mp4"; break;
                case "ogg": case "oga": case "opus": t = "audio/ogg"; break;
                case "webm": t = "video/webm"; break;
                case "m3u8": t = "application/x-mpegurl"; break;
                case "3gp": t = "video/3gpp"; break;
                default: t = "video/mp4"; break;
            }
        }
        YouTube.parseMime(t, s); // "video/mp4; codecs=..." fills codecs and the audio/video flags
        if (s.mime.contains("mpegurl")) s.audio = s.video = true;
        if (s.mime.startsWith("video/") && s.codecs.isEmpty()) s.audio = true;
        return s;
    }

    private static String lastSegment(String url) {
        String path = UrlUtil.stripFragment(url);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        String n = path.substring(path.lastIndexOf('/') + 1);
        return n.isEmpty() || n.length() > 60 ? "media" : n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void start(StreamPicker.Choice c, String name, long startMs) {
        if (isFinishing()) return;
        releaseEngine();
        builtin = c.builtin;
        audioOnly = c.audioOnly;
        ended = false;
        applyAudioModeUi();
        if (c.reason != null && !wantAudioOnly) Ui.toast(this, getString(R.string.player_audio_only_reason));
        String playUrl = c.stream.url;
        if (UrlUtil.isHttp(playUrl)) {
            try {
                VideoProxy proxy = VideoProxy.get(this);
                String ua = ytId != null ? null : cfg.userAgent;
                localUrl = proxy.register(c.stream.url, ua, referer, c.stream.mime.contains("mpegurl") ? "index.m3u8" : name);
                playUrl = localUrl;
            } catch (IOException e) {
                fail(getString(R.string.player_error, String.valueOf(e.getMessage())));
                return;
            }
        } else if (playUrl.startsWith("file:")) {
            String path = android.net.Uri.parse(playUrl).getPath();
            if (path != null) playUrl = path;
        } else if (builtin) {
            builtin = false; // content:// has no path the built-in decoder could open
        }
        engine = builtin ? new BuiltinEngine() : new SystemEngine();
        Log.i(TAG, "playing itag " + c.stream.itag + " " + c.stream.mime + " " + c.stream.codecs + " with "
                + (builtin ? "built-in" : "system") + " decoder" + (audioOnly ? ", sound only" : ""));
        if (startMs > 5000) Ui.toast(this, getString(R.string.player_resume, format(startMs)));
        engine.start(playUrl, startMs);
        if (surfaceReady) engine.setSurface(surface.getHolder());
        updateControls();
    }

    private void releaseEngine() {
        if (engine != null) {
            engine.release();
            engine = null;
        }
        if (localUrl != null) {
            try {
                VideoProxy.get(this).unregister(localUrl);
            } catch (IOException ignored) {
                // never started
            }
            localUrl = null;
        }
    }

    private void onEngineError(String message, boolean decoderProblem) {
        long pos = engine != null ? engine.position() : 0;
        Log.w(TAG, "playback error: " + message + (decoderProblem ? " (decoder)" : ""));
        releaseEngine();
        if (!decoderProblem && ytId != null && !refreshed) {
            refreshed = true; // stream links expire: fetch fresh ones once
            resolveAndPlay(pos);
            return;
        }
        if (!builtin && !triedBuiltin && !"system".equals(cfg.videoDecoder) && NativePlayer.available()) {
            triedBuiltin = true;
            forceBuiltin = true;
            Ui.toast(this, getString(R.string.player_switch_builtin));
            resolveAndPlay(pos);
            return;
        }
        if (!audioOnly && !triedAudio && cfg.videoSound) {
            triedAudio = true;
            audioOnly = true;
            Ui.toast(this, getString(R.string.player_try_audio));
            resolveAndPlay(pos);
            return;
        }
        showError(getString(R.string.player_error, message));
    }

    private void switchAudioMode() {
        long pos = engine != null ? engine.position() : savedPosition();
        wantAudioOnly = !audioOnly;
        audioOnly = wantAudioOnly;
        triedAudio = false;
        releaseEngine();
        applyAudioModeUi();
        resolveAndPlay(pos);
    }

    private void fail(final String message) {
        handler.post(() -> showError(message));
    }

    private void showError(String message) {
        if (isFinishing()) return;
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        showControls();
    }

    // ------------------------------------------------------------------ remembered positions

    private SharedPreferences positions() {
        return getSharedPreferences("video_positions", MODE_PRIVATE);
    }

    private long savedPosition() {
        if (posKey == null) return 0;
        return positions().getLong(posKey, 0);
    }

    private void savePosition(long ms) {
        if (posKey == null || engine == null) return;
        long d = engine.duration();
        if (ms < 10_000 || (d > 0 && ms > d - 15_000)) {
            clearPosition();
            return;
        }
        SharedPreferences sp = positions();
        SharedPreferences.Editor e = sp.edit().putLong(posKey, ms);
        Map<String, ?> all = sp.getAll();
        if (all.size() > 80) {
            int drop = all.size() - 60;
            for (String k : all.keySet()) {
                if (drop-- <= 0) break;
                if (!k.equals(posKey)) e.remove(k);
            }
        }
        e.apply();
    }

    private void clearPosition() {
        if (posKey != null) positions().edit().remove(posKey).apply();
    }

    private void showA2HintOnce() {
        if (!Prefs.bool("video_hint_shown", false)) {
            Prefs.put("video_hint_shown", true);
            Ui.toast(this, getString(R.string.player_a2_hint));
        }
    }
}
