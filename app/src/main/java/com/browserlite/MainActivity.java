package com.browserlite;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.browserlite.data.Db;
import com.browserlite.net.NetEngine;
import com.browserlite.net.UrlUtil;
import com.browserlite.ui.Icon;
import com.browserlite.ui.Ui;
import com.browserlite.web.BrowserView;
import com.browserlite.web.CertVerifier;
import com.browserlite.web.Injector;
import com.browserlite.web.Interceptor;
import com.browserlite.web.Pages;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class MainActivity extends Activity implements BrowserView.Listener, BrowserApp.TrimListener {
    private static final int REQ_FILE = 1;
    private static final int REQ_SETTINGS = 2;

    private BrowserApp app;
    private Config cfg;
    private Interceptor interceptor;
    private Pages pages;
    private Injector injector;
    private Db db;
    private final Tabs tabs = new Tabs();
    private final Handler handler = new Handler();

    private FrameLayout root;
    private FrameLayout webHolder;
    private FrameLayout videoHolder;
    private LinearLayout topBar;
    private LinearLayout findBar;
    private LinearLayout bottomBar;
    private LinearLayout memBanner;
    private AutoCompleteTextView address;
    private ImageView reloadButton;
    private Icon reloadIcon;
    private Icon stopIcon;
    private long lastRelief;
    private TextView tabsButton;
    private TextView pageInfo;
    private TextView findCount;
    private EditText findInput;
    private ProgressLine progress;
    private View flash;
    private ImageView exitFullscreen;

    private BrowserView web;
    private boolean resumed;
    private boolean debuggable;
    private boolean hibernated;
    private Bundle hibernatedState;
    private boolean fullscreen;
    private boolean readerMode;
    private String readerNonce;
    private String readerSource;
    private boolean readerRestoreJsOff;
    private String liteHost;
    private int pageTurns;
    private long lastBackPress;
    private long lastMemToast;
    private String currentUrl = "";
    private String settingsSnapshot;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri> uploadOne;
    private ValueCallback<Uri[]> uploadMany;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        BrowserApp.applyLocale(this);
        app = BrowserApp.get();
        cfg = Config.get();
        interceptor = app.interceptor();
        pages = app.pages();
        injector = app.injector();
        db = Db.get(this);
        CookieSyncManager.createInstance(this);
        debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            WebView.setWebContentsDebuggingEnabled(true);
            com.browserlite.net.LazyStream.debug = true;
        }
        buildUi();
        setContentView(root);
        // Keep focus (and a blinking caret, which re-flashes e-ink panels) out of the address bar. When a view
        // clears its focus Android hands it to the first focusable view, so the root itself takes it first.
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        root.requestFocus();
        app.setTrimListener(this);

        Bundle webState = null;
        if (saved != null) webState = tabs.restoreFromBundle(saved);
        if (tabs.size() == 0 && Prefs.bool(Prefs.RESTORE_TABS, true)) tabs.load(this);
        String intentUrl = saved == null ? urlFromIntent(getIntent()) : null;
        if (intentUrl != null) {
            tabs.add(intentUrl, "", true, cfg.maxTabs);
            tabs.enforceLimit(cfg.maxTabs);
            webState = null;
        }
        if (tabs.size() == 0) tabs.add(homeUrl(), "", true, cfg.maxTabs);
        createWebView();
        showTab(tabs.current(), webState);
        settingsSnapshot = snapshotOf(cfg);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null) openInNewTab(url, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (hibernated) wakeFromHibernation();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
        startMemoryWatch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stopMemoryWatch();
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
        try {
            CookieSyncManager.getInstance().sync();
        } catch (Exception ignored) {
            // ignore
        }
        saveCurrentTab();
        tabs.save(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Bundle state = null;
        if (web != null) {
            state = new Bundle();
            web.saveState(state);
        } else if (hibernatedState != null) {
            state = hibernatedState;
        }
        tabs.saveToBundle(out, state);
    }

    @Override
    protected void onDestroy() {
        stopMemoryWatch();
        handler.removeCallbacksAndMessages(null);
        app.clearTrimListener(this);
        destroyWebView();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updatePageInfoSoon();
    }

    // ------------------------------------------------------------------ UI construction

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top bar: address, reload, tabs, menu.
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.WHITE);
        topBar.setPadding(dp(4), dp(3), dp(2), dp(3));
        address = new AutoCompleteTextView(this);
        address.setSingleLine(true);
        address.setHint(R.string.hint_address);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        address.setTextColor(Color.BLACK);
        address.setHintTextColor(0xFF555555);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setBackgroundDrawable(Ui.border(this, 1, Color.WHITE));
        address.setPadding(dp(10), dp(6), dp(10), dp(6));
        address.setThreshold(2);
        address.setDropDownBackgroundDrawable(Ui.border(this, 2, Color.WHITE));
        address.setAdapter(new SuggestAdapter());
        address.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                navigateFromAddressBar(address.getText().toString());
                return true;
            }
            return false;
        });
        address.setOnItemClickListener((parent, view, position, id) -> {
            Object o = parent.getItemAtPosition(position);
            if (o instanceof Db.Entry) navigateFromAddressBar(((Db.Entry) o).url);
        });
        address.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) showUrl(currentUrl);
        });
        topBar.addView(address, new LinearLayout.LayoutParams(0, dp(42), 1));
        reloadButton = Ui.iconButton(this, Icon.RELOAD, "reload", v -> {
            if (web == null) return;
            if (progress.isLoading()) web.stopLoading();
            else reload();
        });
        topBar.addView(reloadButton, new LinearLayout.LayoutParams(dp(46), dp(46)));
        float density = getResources().getDisplayMetrics().density;
        reloadIcon = new Icon(Icon.RELOAD, density);
        stopIcon = new Icon(Icon.STOP, density);
        tabsButton = Ui.text(this, "1", 15, true);
        tabsButton.setGravity(Gravity.CENTER);
        tabsButton.setBackgroundDrawable(Ui.border(this, 2, Color.WHITE));
        tabsButton.setOnClickListener(v -> showTabs());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        tlp.leftMargin = dp(8);
        tlp.rightMargin = dp(8);
        topBar.addView(tabsButton, tlp);
        topBar.addView(Ui.iconButton(this, Icon.MENU, "menu", v -> showMenu()), new LinearLayout.LayoutParams(dp(42), dp(46)));
        column.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Find bar (hidden).
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setPadding(dp(4), dp(3), dp(2), dp(3));
        findBar.setVisibility(View.GONE);
        findInput = new EditText(this);
        findInput.setSingleLine(true);
        findInput.setHint(R.string.find_hint);
        findInput.setTextColor(Color.BLACK);
        findInput.setBackgroundDrawable(Ui.border(this, 1, Color.WHITE));
        findInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        findInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        findInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (web != null) web.findAllAsync(s.toString());
            }
        });
        findInput.setOnEditorActionListener((v, actionId, event) -> {
            if (web != null) web.findNext(true);
            return true;
        });
        findBar.addView(findInput, new LinearLayout.LayoutParams(0, dp(42), 1));
        findCount = Ui.text(this, "", 14, false);
        findCount.setPadding(dp(8), 0, dp(4), 0);
        findBar.addView(findCount);
        findBar.addView(Ui.iconButton(this, Icon.UP, "previous", v -> { if (web != null) web.findNext(false); }),
                new LinearLayout.LayoutParams(dp(44), dp(46)));
        findBar.addView(Ui.iconButton(this, Icon.DOWN, "next", v -> { if (web != null) web.findNext(true); }),
                new LinearLayout.LayoutParams(dp(44), dp(46)));
        findBar.addView(Ui.iconButton(this, Icon.CLOSE, "close", v -> closeFind()), new LinearLayout.LayoutParams(dp(44), dp(46)));
        column.addView(findBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View topLine = new View(this);
        topLine.setBackgroundColor(Color.BLACK);
        column.addView(topLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        progress = new ProgressLine(this);
        column.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // Low memory banner (hidden).
        memBanner = new LinearLayout(this);
        memBanner.setOrientation(LinearLayout.HORIZONTAL);
        memBanner.setGravity(Gravity.CENTER_VERTICAL);
        memBanner.setBackgroundDrawable(Ui.border(this, 2, Color.WHITE));
        memBanner.setPadding(dp(10), dp(4), dp(4), dp(4));
        memBanner.setVisibility(View.GONE);
        TextView memText = Ui.text(this, getString(R.string.mem_banner), 15, false);
        memBanner.addView(memText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView memAction = Ui.button(this, getString(R.string.mem_banner_action), true, v -> liteReload());
        memBanner.addView(memAction);
        memBanner.addView(Ui.iconButton(this, Icon.CLOSE, "close", v -> memBanner.setVisibility(View.GONE)),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        column.addView(memBanner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webHolder = new FrameLayout(this);
        webHolder.setBackgroundColor(Color.WHITE);
        column.addView(webHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Bottom bar: back, forward, page up, page counter, page down, reader, home.
        View bottomLine = new View(this);
        bottomLine.setBackgroundColor(Color.BLACK);
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(48), 1);
        bottomBar.addView(Ui.iconButton(this, Icon.BACK, "back", v -> goBack()), blp);
        bottomBar.addView(Ui.iconButton(this, Icon.FORWARD, "forward", v -> goForward()), new LinearLayout.LayoutParams(0, dp(48), 1));
        bottomBar.addView(Ui.iconButton(this, Icon.PAGE_UP, "page up", v -> pageTurn(-1)), new LinearLayout.LayoutParams(0, dp(48), 1));
        pageInfo = Ui.text(this, "", 13, false);
        pageInfo.setGravity(Gravity.CENTER);
        pageInfo.setSingleLine(true);
        bottomBar.addView(pageInfo, new LinearLayout.LayoutParams(0, dp(48), 1));
        bottomBar.addView(Ui.iconButton(this, Icon.PAGE_DOWN, "page down", v -> pageTurn(1)), new LinearLayout.LayoutParams(0, dp(48), 1));
        bottomBar.addView(Ui.iconButton(this, Icon.READER, "reader", v -> toggleReader()), new LinearLayout.LayoutParams(0, dp(48), 1));
        bottomBar.addView(Ui.iconButton(this, Icon.HOME, "home", v -> load(homeUrl(), false)), new LinearLayout.LayoutParams(0, dp(48), 1));
        column.addView(bottomLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        column.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boolean showBottom = Prefs.bool(Prefs.BOTTOM_BAR, true);
        bottomBar.setVisibility(showBottom ? View.VISIBLE : View.GONE);
        bottomLine.setVisibility(showBottom ? View.VISIBLE : View.GONE);
        bottomBar.setTag(bottomLine);

        videoHolder = new FrameLayout(this);
        videoHolder.setBackgroundColor(Color.BLACK);
        videoHolder.setVisibility(View.GONE);
        root.addView(videoHolder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        exitFullscreen = Ui.iconButton(this, Icon.FULLSCREEN_EXIT, "exit full screen", v -> setFullscreen(false));
        exitFullscreen.setBackgroundDrawable(Ui.border(this, 1, Color.WHITE));
        exitFullscreen.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.RIGHT);
        elp.topMargin = dp(4);
        elp.rightMargin = dp(4);
        root.addView(exitFullscreen, elp);

        flash = new View(this);
        flash.setVisibility(View.GONE);
        flash.setClickable(true);
        root.addView(flash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void setBottomBarVisible(boolean visible) {
        bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        ((View) bottomBar.getTag()).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Thin loading bar that only redraws in big steps: every redraw is an e-ink refresh. */
    static final class ProgressLine extends View {
        private int shown = -1;
        private final Paint paint = new Paint();

        ProgressLine(Context c) {
            super(c);
            paint.setColor(Color.BLACK);
        }

        boolean isLoading() {
            return shown >= 0;
        }

        void set(int p) {
            int step = p >= 100 ? -1 : Math.max(10, (p / 25) * 25);
            if (step == shown) return;
            shown = step;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (shown < 0) return;
            c.drawRect(0, 0, getWidth() * shown / 100f, getHeight(), paint);
        }
    }

    // ------------------------------------------------------------------ WebView management

    private void createWebView() {
        web = new BrowserView(this);
        web.setListener(this);
        configureWebView(web);
        webHolder.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        try {
            CookieManager.getInstance().setAcceptCookie(cfg.cookies);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void destroyWebView() {
        if (web == null) return;
        BrowserView w = web;
        web = null;
        webHolder.removeView(w);
        try {
            w.stopLoading();
            w.setWebChromeClient(null);
            w.setDownloadListener(null);
            w.removeAllViews();
            w.destroy();
        } catch (Exception ignored) {
            // already torn down
        }
    }

    @SuppressWarnings("deprecation")
    private void configureWebView(BrowserView w) {
        applySettings(w.getSettings());
        w.setGestures(Prefs.bool(Prefs.SWIPE_PAGES, false), Prefs.bool(Prefs.TAP_ZONES, false));
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setScrollbarFadingEnabled(false);
        w.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        w.setBackgroundColor(Color.WHITE);
        if (cfg.softwareRendering) w.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        w.setWebViewClient(new Client());
        w.setWebChromeClient(new Chrome());
        w.addJavascriptInterface(new Bridge(), "__blBridge");
        w.setDownloadListener((url, userAgent, disposition, mimetype, length) ->
                confirmDownload(url, userAgent, disposition, mimetype, length));
        w.setFindListener((active, count, done) -> findCount.setText(count > 0 ? (active + 1) + "/" + count : "0/0"));
        w.setOnLongClickListener(v -> onLongPress());
    }

    @SuppressWarnings("deprecation")
    private void applySettings(WebSettings s) {
        s.setJavaScriptEnabled(cfg.javascript);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false);
        s.setGeolocationEnabled(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLayoutAlgorithm(Prefs.bool(Prefs.AUTOSIZE, true) ? WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                : WebSettings.LayoutAlgorithm.NORMAL);
        s.setTextZoom(Prefs.rawInt(Prefs.TEXT_ZOOM, 100));
        s.setUserAgentString(cfg.userAgent);
        boolean images = cfg.imageMode != Config.IMAGES_OFF;
        s.setLoadsImagesAutomatically(images);
        s.setBlockNetworkImage(!images);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setDefaultTextEncodingName("utf-8");
        s.setMinimumFontSize(9);
        s.setNeedInitialFocus(false);
        s.setSaveFormData(true);
        s.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni == null || ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private void applySiteSettings(String url) {
        if (web == null || url == null) return;
        String host = UrlUtil.host(url);
        if (liteHost != null && !liteHost.equals(host)) liteHost = null;
        boolean js = url.startsWith(Interceptor.HOME_URL) || (cfg.jsAllowedFor(host) && !host.equals(liteHost));
        WebSettings s = web.getSettings();
        if (s.getJavaScriptEnabled() != js) s.setJavaScriptEnabled(js);
    }

    private String homeUrl() {
        if (cfg.homeUrl.isEmpty()) return Interceptor.HOME_URL;
        String u = UrlUtil.fromInput(cfg.homeUrl, cfg.searchTemplate);
        return u == null ? Interceptor.HOME_URL : u;
    }

    // ------------------------------------------------------------------ navigation

    private void load(String url, boolean typed) {
        if (web == null || url == null) return;
        readerMode = false;
        applySiteSettings(url);
        interceptor.expectMainFrame(url, null, typed);
        web.loadUrl(url);
    }

    private void navigateFromAddressBar(String input) {
        String trimmed = input == null ? "" : input.trim();
        String url = UrlUtil.fromInput(trimmed, cfg.searchTemplate);
        if (url == null) return;
        boolean typed = trimmed.indexOf("://") < 0 && trimmed.indexOf(' ') < 0 && url.startsWith("https://");
        hideKeyboard(address);
        address.dismissDropDown();
        address.clearFocus();
        root.requestFocus();
        load(url, typed);
        showUrl(url);
    }

    private void expectHistoryItem(int offset) {
        if (web == null) return;
        WebBackForwardList list = web.copyBackForwardList();
        int i = list.getCurrentIndex() + offset;
        if (i < 0 || i >= list.getSize()) return;
        WebHistoryItem item = list.getItemAtIndex(i);
        String u = item.getUrl();
        if (UrlUtil.isHttp(u)) interceptor.expectMainFrame(u, null, false);
        applySiteSettings(u);
    }

    private void goBack() {
        if (web == null) return;
        if (web.canGoBack()) {
            expectHistoryItem(-1);
            web.goBack();
        }
    }

    private void goForward() {
        if (web == null) return;
        if (web.canGoForward()) {
            expectHistoryItem(1);
            web.goForward();
        }
    }

    private void reload() {
        if (web == null) return;
        if (readerMode) {
            readerMode = false;
            load(readerSource, false);
            return;
        }
        String u = web.getUrl();
        if (u != null && UrlUtil.isHttp(u)) interceptor.expectMainFrame(u, null, false);
        applySiteSettings(u);
        web.reload();
    }

    private void liteReload() {
        memBanner.setVisibility(View.GONE);
        if (web == null) return;
        liteHost = UrlUtil.host(web.getUrl());
        reload();
    }

    private void showUrl(String url) {
        if (address.hasFocus()) return;
        String shown;
        if (url == null || url.startsWith(Interceptor.HOME_URL) || url.equals("about:blank")) shown = "";
        else if (readerMode && readerSource != null) shown = readerSource;
        else shown = url;
        address.setText(shown);
    }

    private static String urlFromIntent(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            String u = intent.getData().toString();
            return u.isEmpty() ? null : u;
        }
        if (Intent.ACTION_WEB_SEARCH.equals(action) || Intent.ACTION_SEARCH.equals(action)) {
            String q = intent.getStringExtra(SearchManager.QUERY);
            if (q != null) return UrlUtil.fromInput(q, Config.get().searchTemplate);
        }
        if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) return null;
            java.util.regex.Matcher m = Pattern.compile("https?://\\S+").matcher(text);
            if (m.find()) return m.group();
            return UrlUtil.fromInput(text, Config.get().searchTemplate);
        }
        return null;
    }

    // ------------------------------------------------------------------ tabs

    private void updateTabsButton() {
        tabsButton.setText(String.valueOf(tabs.size()));
    }

    private void saveCurrentTab() {
        Tabs.Tab t = tabs.current();
        if (t == null || web == null) return;
        Bundle b = new Bundle();
        web.saveState(b);
        t.state = b;
        String u = readerMode && readerSource != null ? readerSource : web.getUrl();
        if (u != null) t.url = u;
        String title = web.getTitle();
        if (title != null) t.title = title;
    }

    private void showTab(Tabs.Tab t, Bundle state) {
        if (t == null || web == null) return;
        readerMode = false;
        currentUrl = t.url == null ? "" : t.url;
        showUrl(currentUrl);
        updateTabsButton();
        Bundle s = state != null ? state : t.state;
        t.state = null;
        if (s != null) {
            if (UrlUtil.isHttp(t.url)) interceptor.expectMainFrame(t.url, null, false);
            applySiteSettings(t.url);
            WebBackForwardList restored = web.restoreState(s);
            if (restored != null && restored.getSize() > 0) return;
        }
        load(t.url == null || t.url.isEmpty() ? homeUrl() : t.url, false);
    }

    private void switchToTab(int index) {
        if (index == tabs.currentIndex() && web != null) return;
        saveCurrentTab();
        tabs.select(index);
        destroyWebView();
        createWebView();
        showTab(tabs.current(), null);
    }

    private void openInNewTab(String url, boolean foreground) {
        if (url == null) return;
        if (foreground) saveCurrentTab();
        tabs.add(url, "", foreground, cfg.maxTabs);
        if (tabs.enforceLimit(cfg.maxTabs)) Ui.toast(this, getString(R.string.toast_tab_limit));
        updateTabsButton();
        if (foreground) {
            destroyWebView();
            createWebView();
            showTab(tabs.current(), null);
        } else {
            Ui.toast(this, getString(R.string.toast_opened_background));
        }
    }

    private void closeTab(int index) {
        boolean wasCurrent = index == tabs.currentIndex();
        int next = tabs.remove(index);
        if (tabs.size() == 0) {
            tabs.add(homeUrl(), "", true, cfg.maxTabs);
            next = 0;
            wasCurrent = true;
        }
        updateTabsButton();
        if (wasCurrent) {
            tabs.select(next);
            destroyWebView();
            createWebView();
            showTab(tabs.current(), null);
        }
    }

    private void showTabs() {
        saveCurrentTab();
        Ui.Header h = new Ui.Header(getString(R.string.tabs_title, tabs.size()));
        h.buttons.add(new Ui.Item(getString(R.string.menu_new_tab), null, () -> openInNewTab(homeUrl(), true)));
        h.buttons.add(new Ui.Item(getString(R.string.close_all), null, () -> {
            tabs.clear();
            closeTab(0);
        }));
        List<Ui.Item> items = new ArrayList<>();
        List<Tabs.Tab> all = tabs.all();
        for (int i = 0; i < all.size(); i++) {
            final int index = i;
            Tabs.Tab t = all.get(i);
            String url = t.url == null || t.url.startsWith(Interceptor.HOME_URL) ? getString(R.string.home_title) : t.url;
            String title = t.title == null || t.title.isEmpty() ? url : t.title;
            Ui.Item it = new Ui.Item((i == tabs.currentIndex() ? "» " : "") + title, url, null, () -> switchToTab(index));
            it.trailingIcon = Icon.CLOSE;
            it.trailingAction = () -> closeTab(index);
            items.add(it);
        }
        Ui.sheet(this, h, items, false);
    }

    // ------------------------------------------------------------------ page turning (e-ink)

    @Override
    public void onPageTurnGesture(int direction) {
        pageTurn(direction);
    }

    @Override
    public void onScrolled() {
        updatePageInfoSoon();
    }

    @SuppressWarnings("deprecation")
    private int maxScrollY() {
        if (web == null) return 0;
        return Math.max(0, (int) Math.floor(web.getContentHeight() * web.getScale()) - web.getHeight());
    }

    private int pageStep() {
        int overlap = Math.max(0, Math.min(50, Prefs.integer(Prefs.OVERLAP, 10)));
        return Math.max(1, web.getHeight() * (100 - overlap) / 100);
    }

    private void pageTurn(int dir) {
        if (web == null) return;
        int before = web.getScrollY();
        int step = pageStep();
        int max = maxScrollY();
        if (max > 0) {
            int target = Math.max(0, Math.min(max, before + dir * step));
            if (target != before) web.scrollTo(web.getScrollX(), target);
        } else {
            web.scrollBy(0, dir * step);
        }
        if (web.getScrollY() == before) {
            float overlap = Prefs.integer(Prefs.OVERLAP, 10) / 100f;
            web.evaluateJavascript("window.__bl&&__bl.page(" + dir + "," + overlap + ")", null);
        }
        pageTurns++;
        int every = Prefs.integer(Prefs.REFRESH_EVERY, 0);
        if (every > 0 && pageTurns % every == 0) flashRefresh();
        updatePageInfoSoon();
    }

    private final Runnable pageInfoUpdate = new Runnable() {
        @SuppressWarnings("deprecation")
        @Override
        public void run() {
            if (web == null || web.getHeight() == 0) return;
            int contentH = (int) (web.getContentHeight() * web.getScale());
            int h = web.getHeight();
            int step = pageStep();
            if (contentH <= h + 2) {
                pageInfo.setText("");
                return;
            }
            int total = 1 + (int) Math.ceil((contentH - h) / (double) step);
            int cur = Math.min(total, 1 + (int) Math.round(web.getScrollY() / (double) step));
            if (web.getScrollY() >= contentH - h - 2) cur = total;
            String text = cur + "/" + total;
            if (!text.contentEquals(pageInfo.getText())) pageInfo.setText(text);
        }
    };

    private void updatePageInfoSoon() {
        handler.removeCallbacks(pageInfoUpdate);
        handler.postDelayed(pageInfoUpdate, 350);
    }

    /** Black then white flash: forces the e-ink controller to do a full (ghost-clearing) refresh. */
    private void flashRefresh() {
        flash.setBackgroundColor(Color.BLACK);
        flash.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> flash.setBackgroundColor(Color.WHITE), 140);
        handler.postDelayed(() -> flash.setVisibility(View.GONE), 260);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean volume = (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)
                && Prefs.bool(Prefs.VOLUME_KEYS, true);
        boolean pageKey = code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_PAGE_UP;
        if ((volume || pageKey) && customView == null && !address.hasFocus() && !findInput.hasFocus()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                pageTurn(code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN ? 1 : -1);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (findBar.getVisibility() == View.VISIBLE) {
            closeFind();
            return;
        }
        if (address.hasFocus()) {
            address.clearFocus();
            root.requestFocus();
            hideKeyboard(address);
            return;
        }
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        if (web != null && web.canGoBack()) {
            goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2500) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            Ui.toast(this, getString(R.string.toast_press_back_exit));
        }
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        topBar.setVisibility(on ? View.GONE : View.VISIBLE);
        setBottomBarVisible(!on && Prefs.bool(Prefs.BOTTOM_BAR, true));
        exitFullscreen.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ find in page

    private void openFind() {
        topBar.setVisibility(View.GONE);
        findBar.setVisibility(View.VISIBLE);
        findInput.setText("");
        findCount.setText("");
        findInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeFind() {
        hideKeyboard(findInput);
        findBar.setVisibility(View.GONE);
        if (!fullscreen) topBar.setVisibility(View.VISIBLE);
        if (web != null) web.clearMatches();
    }

    // ------------------------------------------------------------------ reader mode

    private void toggleReader() {
        if (web == null) return;
        if (readerMode) {
            readerMode = false;
            if (web.canGoBack()) goBack();
            else load(readerSource, false);
            return;
        }
        String url = web.getUrl();
        if (url == null || url.startsWith(Interceptor.HOME_URL)) return;
        readerSource = url;
        readerNonce = Long.toHexString(new SecureRandom().nextLong());
        WebSettings s = web.getSettings();
        readerRestoreJsOff = !s.getJavaScriptEnabled();
        if (readerRestoreJsOff) s.setJavaScriptEnabled(true);
        web.evaluateJavascript(injector.readerScript() + "\n;window.__blReader(" + UrlUtil.jsString(readerNonce) + ");", null);
    }

    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
            "(?is)<(script|style|iframe|object|embed|form|template)\\b.*?</\\1\\s*>");
    private static final Pattern DANGEROUS_TAG = Pattern.compile(
            "(?is)<(script|style|iframe|object|embed|link|meta|base|form|input|button|textarea|select)\\b[^>]*>");
    private static final Pattern EVENT_ATTR = Pattern.compile("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JS_URL = Pattern.compile("(?i)(href|src)\\s*=\\s*([\"']?)\\s*(javascript|vbscript|data):[^\"'>\\s]*\\2");

    static String sanitize(String html) {
        String s = DANGEROUS_BLOCK.matcher(html).replaceAll("");
        s = DANGEROUS_TAG.matcher(s).replaceAll("");
        s = EVENT_ATTR.matcher(s).replaceAll("");
        s = JS_URL.matcher(s).replaceAll("$1=\"#\"");
        return s;
    }

    private void onReaderResult(String nonce, String json) {
        if (readerNonce == null || !readerNonce.equals(nonce) || web == null) return;
        readerNonce = null;
        if (readerRestoreJsOff) web.getSettings().setJavaScriptEnabled(false);
        try {
            if (json == null || json.isEmpty()) throw new IllegalStateException();
            JSONObject o = new JSONObject(json);
            String html = sanitize(o.optString("html", ""));
            if (html.trim().isEmpty()) throw new IllegalStateException();
            String page = pages.reader(o.optString("title", ""), o.optString("byline", ""), o.optString("site", ""),
                    html, injector.readerCss(), readerSource, o.optInt("minutes", 0));
            readerMode = true;
            web.loadDataWithBaseURL(readerSource, page, "text/html", "UTF-8", readerSource);
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.toast_reader_failed));
        }
    }

    // ------------------------------------------------------------------ menus

    private void showMenu() {
        final String url = web != null ? web.getUrl() : null;
        final boolean httpPage = url != null && UrlUtil.isHttp(url) && !url.startsWith(Interceptor.HOME_URL);
        final String host = UrlUtil.host(readerMode && readerSource != null ? readerSource : url);
        List<Ui.Item> items = new ArrayList<>();
        items.add(new Ui.Item(getString(R.string.menu_new_tab), null, () -> openInNewTab(homeUrl(), true)));
        items.add(new Ui.Item(getString(R.string.menu_bookmarks), null, this::showBookmarks));
        if (httpPage) {
            final String pageUrl = readerMode && readerSource != null ? readerSource : url;
            final boolean marked = db.isBookmarked(pageUrl);
            items.add(new Ui.Item(getString(marked ? R.string.menu_remove_bookmark : R.string.menu_add_bookmark), null, () -> {
                if (marked) {
                    db.removeBookmark(pageUrl);
                    Ui.toast(this, getString(R.string.toast_bookmark_removed));
                } else {
                    db.addBookmark(pageUrl, web != null ? web.getTitle() : pageUrl);
                    Ui.toast(this, getString(R.string.toast_bookmark_added));
                }
            }));
        }
        items.add(new Ui.Item(getString(R.string.menu_history), null, this::showHistory));
        items.add(new Ui.Item(getString(R.string.menu_find), null, this::openFind));
        items.add(new Ui.Item(getString(R.string.menu_reader), readerMode, this::toggleReader));
        items.add(new Ui.Item(getString(R.string.menu_text_size), null, this::showTextSize));
        items.add(new Ui.Item(getString(R.string.menu_fullscreen), fullscreen, () -> setFullscreen(!fullscreen)));
        items.add(new Ui.Item(getString(R.string.menu_refresh_screen), null, this::flashRefresh));
        items.add(new Ui.Item(getString(R.string.menu_unstick), null, () -> {
            if (web != null) web.evaluateJavascript("window.__bl&&__bl.unstick()", null);
        }));
        items.add(new Ui.Item(getString(R.string.menu_desktop), cfg.desktop, () -> togglePref(Prefs.DESKTOP, !cfg.desktop)));
        if (httpPage) {
            final boolean jsOn = cfg.jsAllowedFor(host);
            items.add(new Ui.Item(getString(R.string.menu_js_site), jsOn, () -> {
                Prefs.toggleInSet(Prefs.JS_OFF_SITES, host, jsOn);
                reloadConfigAndPage();
            }));
            final boolean adsOn = cfg.adblockFor(host);
            if (cfg.adblock) {
                items.add(new Ui.Item(getString(R.string.menu_ads_site), adsOn, () -> {
                    Prefs.toggleInSet(Prefs.ADBLOCK_OFF_SITES, host, adsOn);
                    reloadConfigAndPage();
                }));
            }
        }
        items.add(new Ui.Item(getString(R.string.menu_images), cfg.imageMode != Config.IMAGES_OFF, () -> {
            Prefs.put(Prefs.IMAGES, cfg.imageMode == Config.IMAGES_OFF ? "optimize" : "off");
            reloadConfigAndPage();
        }));
        items.add(new Ui.Item(getString(R.string.menu_contrast), cfg.highContrast, () -> togglePref(Prefs.CONTRAST, !cfg.highContrast)));
        if (httpPage) {
            items.add(new Ui.Item(getString(R.string.menu_lite_reload), null, this::liteReload));
            items.add(new Ui.Item(getString(R.string.menu_share), null, () -> share(readerMode ? readerSource : url)));
            items.add(new Ui.Item(getString(R.string.menu_copy_link), null, () -> copy(readerMode ? readerSource : url)));
        }
        items.add(new Ui.Item(getString(R.string.menu_downloads), null, () -> {
            try {
                startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
            } catch (ActivityNotFoundException e) {
                Ui.toast(this, getString(R.string.toast_no_app));
            }
        }));
        items.add(new Ui.Item(getString(R.string.menu_settings), null,
                () -> startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS)));
        items.add(new Ui.Item(getString(R.string.menu_exit), null, this::exitAndFree));
        Ui.sheet(this, null, items, true);
    }

    private void togglePref(String key, boolean value) {
        Prefs.put(key, value);
        reloadConfigAndPage();
    }

    private void reloadConfigAndPage() {
        cfg = app.reloadConfig();
        settingsSnapshot = snapshotOf(cfg);
        if (web != null) {
            applySettings(web.getSettings());
            web.setGestures(Prefs.bool(Prefs.SWIPE_PAGES, false), Prefs.bool(Prefs.TAP_ZONES, false));
        }
        reload();
    }

    private static String snapshotOf(Config c) {
        return c.hash + "|" + c.userAgent + "|" + c.javascript + "|" + c.jsOffSites + "|" + c.adblockOffSites + "|"
                + c.cssCompat + "|" + c.modernNet + "|" + c.routeAssets + "|" + c.blockFonts + "|" + c.grayImages + "|"
                + c.imageQuality + "|" + c.softwareRendering + "|" + Prefs.bool(Prefs.AUTOSIZE, true);
    }

    private void showTextSize() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView label = Ui.text(this, "", 18, true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, 0, 0, dp(12));
        box.addView(label);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final int[] zoom = {Prefs.rawInt(Prefs.TEXT_ZOOM, 100)};
        final Runnable apply = () -> {
            label.setText(getString(R.string.text_size_title, zoom[0]));
            Prefs.putInt(Prefs.TEXT_ZOOM, zoom[0]);
            if (web != null) web.getSettings().setTextZoom(zoom[0]);
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(Ui.button(this, "A-", false, v -> { zoom[0] = Math.max(50, zoom[0] - 10); apply.run(); }), lp);
        row.addView(Ui.button(this, "100%", false, v -> { zoom[0] = 100; apply.run(); }), new LinearLayout.LayoutParams(lp));
        row.addView(Ui.button(this, "A+", true, v -> { zoom[0] = Math.min(300, zoom[0] + 10); apply.run(); }), new LinearLayout.LayoutParams(lp));
        box.addView(row);
        label.setText(getString(R.string.text_size_title, zoom[0]));
        Dialog d = Ui.panel(this, box);
        d.show();
    }

    private void showBookmarks() {
        List<Db.Entry> list = db.bookmarks();
        List<Ui.Item> items = new ArrayList<>();
        for (final Db.Entry e : list) {
            Ui.Item it = new Ui.Item(e.title, e.url, null, () -> load(e.url, false));
            it.trailingIcon = Icon.CLOSE;
            it.trailingAction = () -> {
                db.removeBookmark(e.url);
                Ui.toast(this, getString(R.string.toast_bookmark_removed));
            };
            it.longAction = () -> openInNewTab(e.url, false);
            items.add(it);
        }
        if (items.isEmpty()) items.add(new Ui.Item(getString(R.string.bookmarks_empty), null, null));
        Ui.sheet(this, new Ui.Header(getString(R.string.menu_bookmarks)), items, false);
    }

    private void showHistory() {
        List<Db.Entry> list = db.history(150, 0);
        List<Ui.Item> items = new ArrayList<>();
        java.text.DateFormat fmt = android.text.format.DateFormat.getTimeFormat(this);
        java.text.DateFormat dfmt = android.text.format.DateFormat.getDateFormat(this);
        long startOfDay = System.currentTimeMillis() - System.currentTimeMillis() % 86_400_000L;
        for (final Db.Entry e : list) {
            java.util.Date d = new java.util.Date(e.time);
            String when = e.time >= startOfDay ? fmt.format(d) : dfmt.format(d);
            Ui.Item it = new Ui.Item(e.title, when + " · " + e.url, null, () -> load(e.url, false));
            it.longAction = () -> openInNewTab(e.url, false);
            it.trailingIcon = Icon.CLOSE;
            it.trailingAction = () -> db.removeHistory(e.url);
            items.add(it);
        }
        if (items.isEmpty()) items.add(new Ui.Item(getString(R.string.history_empty), null, null));
        Ui.Header h = new Ui.Header(getString(R.string.menu_history));
        h.buttons.add(new Ui.Item(getString(R.string.delete), null, () -> new AlertDialog.Builder(this)
                .setMessage(R.string.confirm_clear_history)
                .setPositiveButton(R.string.delete, (dlg, w) -> {
                    db.clearHistory();
                    Ui.toast(this, getString(R.string.toast_cleared));
                })
                .setNegativeButton(R.string.cancel, null)
                .show()));
        Ui.sheet(this, h, items, false);
    }

    private void share(String url) {
        if (url == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, url);
        try {
            startActivity(Intent.createChooser(i, null));
        } catch (ActivityNotFoundException e) {
            Ui.toast(this, getString(R.string.toast_no_app));
        }
    }

    private void copy(String text) {
        if (text == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", text));
        Ui.toast(this, getString(R.string.toast_copied));
    }

    private void exitAndFree() {
        saveCurrentTab();
        tabs.save(this);
        try {
            CookieSyncManager.getInstance().sync();
        } catch (Exception ignored) {
            // ignore
        }
        destroyWebView();
        finish();
        // The WebView engine lives in our process on KitKat: only ending the process returns all its RAM.
        handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 400);
    }

    // ------------------------------------------------------------------ long press on links / images

    private boolean onLongPress() {
        if (web == null) return false;
        WebView.HitTestResult r = web.getHitTestResult();
        if (r == null) return false;
        final String extra = r.getExtra();
        switch (r.getType()) {
            case WebView.HitTestResult.SRC_ANCHOR_TYPE:
                linkMenu(extra, null);
                return true;
            case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE: {
                Handler h = new Handler(msg -> {
                    String link = msg.getData() != null ? msg.getData().getString("url") : null;
                    linkMenu(link != null ? link : extra, extra);
                    return true;
                });
                web.requestFocusNodeHref(h.obtainMessage());
                return true;
            }
            case WebView.HitTestResult.IMAGE_TYPE:
                linkMenu(null, extra);
                return true;
            default:
                return false;
        }
    }

    private void linkMenu(final String link, final String image) {
        List<Ui.Item> items = new ArrayList<>();
        if (link != null && !link.startsWith("javascript:")) {
            items.add(new Ui.Item(getString(R.string.ctx_open_new_tab), null, () -> openInNewTab(link, false)));
            items.add(new Ui.Item(getString(R.string.ctx_open), null, () -> load(link, false)));
            items.add(new Ui.Item(getString(R.string.ctx_copy_link), null, () -> copy(link)));
            items.add(new Ui.Item(getString(R.string.ctx_share_link), null, () -> share(link)));
            if (UrlUtil.isHttp(link)) {
                items.add(new Ui.Item(getString(R.string.ctx_download_link), null,
                        () -> Downloader.start(this, link, cfg.userAgent, null, null, currentUrl)));
            }
        }
        if (image != null) {
            items.add(new Ui.Item(getString(R.string.ctx_open_image), null, () -> load(image, false)));
            if (UrlUtil.isHttp(image) || image.startsWith("data:")) {
                items.add(new Ui.Item(getString(R.string.ctx_save_image), null,
                        () -> Downloader.start(this, image, cfg.userAgent, null, null, currentUrl)));
            }
        }
        if (items.isEmpty()) return;
        String title = link != null ? link : image;
        Ui.sheet(this, new Ui.Header(title.length() > 80 ? title.substring(0, 80) + "…" : title), items, false);
    }

    // ------------------------------------------------------------------ downloads

    private void confirmDownload(final String url, final String userAgent, final String disposition, final String mime,
            long length) {
        if (url.startsWith("blob:")) {
            Ui.toast(this, getString(R.string.toast_download_failed, "blob"));
            return;
        }
        String name = Downloader.fileName(url, disposition, mime);
        String size = length > 0 ? (length > 1024 * 1024 ? String.format(Locale.US, "%.1f MB", length / 1048576f)
                : Math.max(1, length / 1024) + " KB") : "?";
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_title)
                .setMessage(name + " (" + size + ")")
                .setPositiveButton(R.string.download, (d, w) -> Downloader.start(this, url, userAgent, disposition, mime, currentUrl))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ memory

    private final Runnable memoryTick = new Runnable() {
        @Override
        public void run() {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (mi.lowMemory) relieveMemory(true);
                else if (mi.availMem < mi.threshold * 3 / 2) relieveMemory(false);
            } catch (Exception ignored) {
                // ignore
            }
            if (resumed) handler.postDelayed(this, 10_000);
        }
    };

    private void startMemoryWatch() {
        handler.removeCallbacks(memoryTick);
        if (Prefs.bool(Prefs.MEMORY_GUARD, true)) handler.postDelayed(memoryTick, 10_000);
    }

    private void stopMemoryWatch() {
        handler.removeCallbacks(memoryTick);
    }

    @SuppressWarnings("deprecation")
    private void relieveMemory(boolean hard) {
        long t0 = System.currentTimeMillis();
        if (!hard && t0 - lastRelief < 30_000) return;
        lastRelief = t0;
        interceptor.onLowMemory();
        if (web != null) {
            web.freeMemory();
            web.clearCache(false);
            if (hard) web.evaluateJavascript("window.__bl&&__bl.lowMemory()", null);
        }
        for (Tabs.Tab t : tabs.all()) {
            if (hard && t != tabs.current()) t.state = null; // background tabs fall back to reloading their URL
        }
        System.gc();
        long now = System.currentTimeMillis();
        if (hard && now - lastMemToast > 60_000 && resumed) {
            lastMemToast = now;
            Ui.toast(this, getString(R.string.toast_low_memory));
        }
    }

    @Override
    public void onTrim(int level) {
        if (!resumed && level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            hibernate();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            relieveMemory(level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            relieveMemory(true);
        }
    }

    /** In the background under memory pressure: drop the whole WebView, keep only its saved state. */
    private void hibernate() {
        if (web == null || hibernated) return;
        Bundle b = new Bundle();
        web.saveState(b);
        hibernatedState = b;
        saveCurrentTab();
        tabs.save(this);
        destroyWebView();
        hibernated = true;
        NetEngine.trim();
        System.gc();
    }

    private void wakeFromHibernation() {
        hibernated = false;
        createWebView();
        Bundle s = hibernatedState;
        hibernatedState = null;
        showTab(tabs.current(), s);
    }

    private void showMemoryBanner() {
        if (memBanner.getVisibility() != View.VISIBLE) memBanner.setVisibility(View.VISIBLE);
        relieveMemory(false);
    }

    // ------------------------------------------------------------------ activity results

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            Uri result = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (uploadOne != null) {
                uploadOne.onReceiveValue(result);
                uploadOne = null;
            }
            if (uploadMany != null) {
                uploadMany.onReceiveValue(result != null ? new Uri[] {result} : null);
                uploadMany = null;
            }
        } else if (requestCode == REQ_SETTINGS) {
            cfg = app.reloadConfig();
            app.reloadAdBlocker();
            if (SettingsActivity.localeChanged) {
                SettingsActivity.localeChanged = false;
                BrowserApp.applyLocale(this);
                recreate();
                return;
            }
            setBottomBarVisible(!fullscreen && Prefs.bool(Prefs.BOTTOM_BAR, true));
            String snap = snapshotOf(cfg);
            if (web != null) {
                applySettings(web.getSettings());
                web.setGestures(Prefs.bool(Prefs.SWIPE_PAGES, false), Prefs.bool(Prefs.TAP_ZONES, false));
                if (!snap.equals(settingsSnapshot)) {
                    if (cfg.softwareRendering) web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                    else web.setLayerType(View.LAYER_TYPE_NONE, null);
                    reload();
                }
            }
            settingsSnapshot = snap;
        }
    }

    // ------------------------------------------------------------------ WebView callbacks

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url == null) return false;
            String lower = url.toLowerCase(Locale.US);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                readerMode = false;
                // Obvious files go straight to the WebView's download path, so the current page stays put.
                if (!isDownloadUrl(lower)) interceptor.expectMainFrame(url, UrlUtil.referrer(currentUrl, url), false);
                applySiteSettings(url);
                return false;
            }
            if (lower.startsWith("about:") || lower.startsWith("javascript:") || lower.startsWith("data:")
                    || lower.startsWith("blob:") || lower.startsWith("file:")) {
                return false;
            }
            if (lower.startsWith("intent:")) {
                openIntentUrl(url);
                return true;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(i);
            } catch (ActivityNotFoundException | SecurityException e) {
                Ui.toast(MainActivity.this, getString(R.string.toast_no_app));
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return interceptor.intercept(url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (view != web) return;
            if (readerMode && url != null && !url.equals(readerSource) && !url.startsWith("data:")) readerMode = false;
            currentUrl = url == null ? "" : url;
            interceptor.setPageUrl(readerMode ? readerSource : currentUrl);
            showUrl(currentUrl);
            progress.set(10);
            reloadButton.setImageDrawable(stopIcon);
            memBanner.setVisibility(View.GONE);
            if (!readerMode) applySiteSettings(url);
            pageInfo.setText("");
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (view != web) return;
            progress.set(100);
            reloadButton.setImageDrawable(reloadIcon);
            if (url != null && UrlUtil.isHttp(url) && !readerMode && !url.startsWith(Interceptor.HOME_URL)) {
                db.addVisit(url, view.getTitle());
                Tabs.Tab t = tabs.current();
                if (t != null) {
                    t.url = url;
                    t.title = view.getTitle();
                }
                if (view.getSettings().getJavaScriptEnabled()) {
                    // Pages we could not rewrite (POST results, JS-driven loads) still get the e-ink CSS and helpers.
                    view.evaluateJavascript("!!window.__bl", value -> {
                        if (web == view && !"true".equals(value)) {
                            view.evaluateJavascript(injector.lateScript(cfg), null);
                        }
                    });
                }
            }
            updatePageInfoSoon();
            handler.postDelayed(MainActivity.this::updatePageInfoSoon, 1500);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (view != web || url == null || readerMode) return;
            if (!url.equals(currentUrl)) {
                // pushState/replaceState navigations in single page apps.
                currentUrl = url;
                interceptor.setPageUrl(url);
                showUrl(url);
            }
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (view != web || errorCode == ERROR_UNSUPPORTED_SCHEME || failingUrl == null) return;
            String html = pages.error(failingUrl, new java.io.IOException(description + " (" + errorCode + ")"));
            view.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl);
        }

        @Override
        public void onReceivedSslError(final WebView view, final SslErrorHandler handler, final SslError error) {
            CertVerifier.verify(MainActivity.this, error, trusted -> {
                if (trusted) {
                    handler.proceed();
                    return;
                }
                String errHost = UrlUtil.host(error.getUrl());
                boolean mainDocument = errHost.equals(UrlUtil.host(currentUrl)) || error.getUrl().equals(view.getUrl());
                if (!mainDocument || isFinishing()) {
                    handler.cancel();
                    return;
                }
                showSslDialog(handler, error);
            });
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, final HttpAuthHandler handler, String host, String realm) {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(16);
            box.setPadding(pad, pad / 2, pad, 0);
            final EditText user = new EditText(MainActivity.this);
            user.setHint(R.string.auth_user);
            user.setSingleLine(true);
            final EditText pass = new EditText(MainActivity.this);
            pass.setHint(R.string.auth_pass);
            pass.setSingleLine(true);
            pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            box.addView(user);
            box.addView(pass);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(getString(R.string.auth_title, host))
                    .setView(box)
                    .setPositiveButton(R.string.ok, (d, w) -> handler.proceed(user.getText().toString(), pass.getText().toString()))
                    .setNegativeButton(R.string.cancel, (d, w) -> handler.cancel())
                    .setOnCancelListener(d -> handler.cancel())
                    .show();
        }

        @Override
        public void onFormResubmission(WebView view, Message dontResend, Message resend) {
            resend.sendToTarget();
        }
    }

    private static final java.util.HashSet<String> DOWNLOAD_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            "apk", "zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "exe", "msi", "dmg", "iso", "pdf", "epub", "mobi",
            "azw", "azw3", "fb2", "djvu", "cbz", "cbr", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf",
            "mp3", "m4a", "flac", "wav", "ogg", "mp4", "mkv", "avi", "mov", "wmv", "torrent", "bin"));

    static boolean isDownloadUrl(String url) {
        return DOWNLOAD_EXT.contains(UrlUtil.extension(url));
    }

    private void openIntentUrl(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            if (getPackageManager().resolveActivity(intent, 0) != null) {
                startActivity(intent);
                return;
            }
            String fallback = intent.getStringExtra("browser_fallback_url");
            if (fallback != null && UrlUtil.isHttp(fallback)) {
                load(fallback, false);
                return;
            }
            String pkg = intent.getPackage();
            if (pkg != null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
                return;
            }
        } catch (Exception ignored) {
            // fall through
        }
        Ui.toast(this, getString(R.string.toast_no_app));
    }

    private void showSslDialog(final SslErrorHandler handler, SslError error) {
        int reason;
        switch (error.getPrimaryError()) {
            case SslError.SSL_EXPIRED: reason = R.string.ssl_expired; break;
            case SslError.SSL_IDMISMATCH: reason = R.string.ssl_mismatch; break;
            case SslError.SSL_NOTYETVALID: reason = R.string.ssl_notyet; break;
            case SslError.SSL_DATE_INVALID: reason = R.string.ssl_date; break;
            case SslError.SSL_UNTRUSTED: reason = R.string.ssl_untrusted; break;
            default: reason = R.string.ssl_invalid; break;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ssl_title)
                .setMessage(getString(R.string.ssl_message, UrlUtil.host(error.getUrl()), getString(reason)))
                .setPositiveButton(R.string.ssl_back, (d, w) -> handler.cancel())
                .setNegativeButton(R.string.ssl_proceed, (d, w) -> handler.proceed())
                .setOnCancelListener(d -> handler.cancel())
                .show();
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            if (view == web) progress.set(p);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (view != web) return;
            Tabs.Tab t = tabs.current();
            if (t != null && !readerMode) t.title = title;
            String url = view.getUrl();
            if (url != null && UrlUtil.isHttp(url) && !readerMode) db.updateTitle(url, title);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
            if (debuggable) {
                android.util.Log.d("BrowserLiteJS", m.messageLevel() + " " + m.sourceId() + ":" + m.lineNumber() + " " + m.message());
            }
            return true; // release builds skip logcat spam: it costs CPU on slow devices
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            videoHolder.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            videoHolder.setVisibility(View.VISIBLE);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }

        // Hidden callbacks used by the KitKat WebView for <input type=file>.
        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> cb) {
            openFileChooser(cb, "*/*", null);
        }

        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> cb, String accept) {
            openFileChooser(cb, accept, null);
        }

        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> cb, String accept, String capture) {
            if (uploadOne != null) uploadOne.onReceiveValue(null);
            uploadOne = cb;
            pickFile(TextUtils.isEmpty(accept) ? "*/*" : accept.split(",")[0].trim());
        }

        @android.annotation.TargetApi(21)
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (uploadMany != null) uploadMany.onReceiveValue(null);
            uploadMany = cb;
            String[] types = params.getAcceptTypes();
            pickFile(types != null && types.length > 0 && !TextUtils.isEmpty(types[0]) ? types[0] : "*/*");
            return true;
        }
    }

    private void pickFile(String type) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type.contains("/") ? type : "*/*");
        try {
            startActivityForResult(Intent.createChooser(i, null), REQ_FILE);
        } catch (ActivityNotFoundException e) {
            if (uploadOne != null) uploadOne.onReceiveValue(null);
            if (uploadMany != null) uploadMany.onReceiveValue(null);
            uploadOne = null;
            uploadMany = null;
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        videoHolder.removeView(customView);
        videoHolder.setVisibility(View.GONE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    /** Called from page JavaScript on the JavaBridge thread. */
    private final class Bridge {
        @android.webkit.JavascriptInterface
        public void formPost(String url) {
            interceptor.bypassOnce(url);
        }

        @android.webkit.JavascriptInterface
        public void memory(int mb) {
            handler.post(MainActivity.this::showMemoryBanner);
        }

        private long lastOpen;

        @android.webkit.JavascriptInterface
        public void openTab(final String url) {
            if (!UrlUtil.isHttp(url)) return;
            BrowserView w = web;
            long now = android.os.SystemClock.uptimeMillis();
            // Popups only right after the user touched the page, and at most one per tap.
            if (w == null || now - w.lastTouchUptime() > 3000 || now - lastOpen < 1500) return;
            lastOpen = now;
            handler.post(() -> openInNewTab(url, true));
        }

        @android.webkit.JavascriptInterface
        public void readerResult(final String nonce, final String json) {
            handler.post(() -> onReaderResult(nonce, json));
        }
    }

    // ------------------------------------------------------------------ address bar suggestions

    private final class SuggestAdapter extends BaseAdapter implements Filterable {
        private List<Db.Entry> items = new ArrayList<>();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                TextView title = Ui.text(MainActivity.this, "", 16, false);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                TextView url = Ui.text(MainActivity.this, "", 12, false);
                url.setTextColor(0xFF333333);
                url.setSingleLine(true);
                url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                row.addView(title);
                row.addView(url);
            }
            Db.Entry e = items.get(position);
            ((TextView) row.getChildAt(0)).setText(e.title);
            ((TextView) row.getChildAt(1)).setText(e.url);
            return row;
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults r = new FilterResults();
                    List<Db.Entry> found = constraint == null || constraint.length() < 2
                            ? new ArrayList<Db.Entry>() : db.suggest(constraint.toString(), 8);
                    r.values = found;
                    r.count = found.size();
                    return r;
                }

                @SuppressWarnings("unchecked")
                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    items = results.values == null ? new ArrayList<Db.Entry>() : (List<Db.Entry>) results.values;
                    notifyDataSetChanged();
                }

                @Override
                public CharSequence convertResultToString(Object resultValue) {
                    return ((Db.Entry) resultValue).url;
                }
            };
        }
    }
}
