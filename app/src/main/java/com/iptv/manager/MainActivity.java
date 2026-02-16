package com.iptv.manager;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main activity that wraps the IPTV Manager web app in a fullscreen WebView.
 * Optimized for Android TV with native sidebar and D-pad navigation.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    // Native sidebar
    private RecyclerView sidebarRecycler;
    private SidebarAdapter sidebarAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive mode
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        // Set up native sidebar
        sidebarRecycler = findViewById(R.id.sidebar_recycler);
        sidebarAdapter = new SidebarAdapter();
        sidebarRecycler.setLayoutManager(new LinearLayoutManager(this));
        sidebarRecycler.setAdapter(sidebarAdapter);
        sidebarAdapter.setLoadingState();

        // Sidebar action listener
        sidebarAdapter.setActionListener(new SidebarAdapter.OnSidebarActionListener() {
            @Override
            public void onItemClick(SidebarAdapter.SidebarItem item) {
                handleSidebarClick(item);
            }

            @Override
            public void onFocusTransferToWebView() {
                webView.requestFocus();
                webView.evaluateJavascript("javascript:window.tvFocusFirstChannel && window.tvFocusFirstChannel()", null);
            }
        });

        // Sidebar search listener
        sidebarAdapter.setSearchListener(query -> {
            String escaped = query.replace("\\", "\\\\").replace("'", "\\'");
            webView.evaluateJavascript("javascript:window.tvSearch && window.tvSearch('" + escaped + "')", null);
        });

        configureWebView();

        // Expose native player to JavaScript: window.Android.playStream(url, name)
        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        // Load the IPTV Manager URL
        String url = getString(R.string.app_url);
        webView.loadUrl(url);

        // Check for app updates in background
        new AppUpdater(this, url).checkForUpdate();
    }

    /**
     * Handle sidebar item click — calls appropriate JS bridge function.
     */
    private void handleSidebarClick(SidebarAdapter.SidebarItem item) {
        if (item.actionType == null) return;

        String js = null;
        switch (item.actionType) {
            case "all":
                js = "window.tvNavigateTo('all')";
                break;
            case "favourites":
                js = "window.tvNavigateTo('favourites')";
                break;
            case "recent":
                js = "window.tvNavigateTo('recent')";
                break;
            case "group":
                String groupName = item.actionData.replace("\\", "\\\\").replace("'", "\\'");
                js = "window.tvNavigateTo('group','" + groupName + "')";
                break;
            case "server":
                js = "window.tvOpenServerSelector()";
                break;
            case "credential":
                js = "window.tvOpenCredentialSelector()";
                break;
            case "manage":
                js = "window.tvOpenManage()";
                break;
            case "password":
                js = "window.tvChangePassword()";
                break;
            case "admin":
                js = "window.tvOpenAdmin()";
                break;
            case "logout":
                js = "window.tvLogout()";
                break;
        }

        if (js != null) {
            webView.evaluateJavascript("javascript:" + js, null);
        }
    }

    /**
     * JavaScript interface exposed as window.Android in the WebView.
     * Allows the web app to launch the native ExoPlayer for HLS streams
     * and communicate sidebar data.
     */
    private class WebAppInterface {
        @JavascriptInterface
        public void playStream(String url, String name) {
            playStream(url, name, null, null);
        }

        @JavascriptInterface
        public void playStream(String url, String name, String token, String baseUrl) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URL, url);
                intent.putExtra(PlayerActivity.EXTRA_NAME, name);
                if (token != null) intent.putExtra(PlayerActivity.EXTRA_TOKEN, token);
                if (baseUrl != null) intent.putExtra(PlayerActivity.EXTRA_BASE_URL, baseUrl);
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public void playStream(String url, String name, String token, String baseUrl, String fallbackUrl) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URL, url);
                intent.putExtra(PlayerActivity.EXTRA_NAME, name);
                if (token != null) intent.putExtra(PlayerActivity.EXTRA_TOKEN, token);
                if (baseUrl != null) intent.putExtra(PlayerActivity.EXTRA_BASE_URL, baseUrl);
                if (fallbackUrl != null) intent.putExtra(PlayerActivity.EXTRA_FALLBACK_URL, fallbackUrl);
                startActivity(intent);
            });
        }

        /**
         * Called by web app when sidebar data is ready (after login, data changes, etc).
         * JSON format: { allCount, favCount, recentCount, groups: [{name, count}],
         *                serverLabel, credentialLabel, isAdmin, activeGroup }
         */
        @JavascriptInterface
        public void onMenuDataReady(String jsonStr) {
            runOnUiThread(() -> {
                try {
                    JSONObject data = new JSONObject(jsonStr);
                    List<SidebarAdapter.SidebarItem> items = new ArrayList<>();

                    String active = data.optString("activeGroup", "favourites");

                    // All Channels
                    items.add(SidebarAdapter.SidebarItem.item(
                            R.drawable.ic_all_channels, "All Channels",
                            String.valueOf(data.optInt("allCount", 0)),
                            "all", null,
                            "all".equals(active)
                    ));

                    // Favourites
                    int favCount = data.optInt("favCount", 0);
                    if (favCount > 0) {
                        items.add(SidebarAdapter.SidebarItem.item(
                                R.drawable.ic_star, "Favourites",
                                String.valueOf(favCount),
                                "favourites", null,
                                "favourites".equals(active)
                        ));
                    }

                    // Recently Played
                    int recentCount = data.optInt("recentCount", 0);
                    if (recentCount > 0) {
                        items.add(SidebarAdapter.SidebarItem.item(
                                R.drawable.ic_history, "Recently Played",
                                String.valueOf(recentCount),
                                "recent", null,
                                "recent".equals(active)
                        ));
                    }

                    // Divider
                    items.add(SidebarAdapter.SidebarItem.divider());

                    // Groups
                    JSONArray groups = data.optJSONArray("groups");
                    if (groups != null) {
                        for (int i = 0; i < groups.length(); i++) {
                            JSONObject g = groups.getJSONObject(i);
                            String name = g.getString("name");
                            int count = g.getInt("count");
                            items.add(SidebarAdapter.SidebarItem.item(
                                    R.drawable.ic_collection, name,
                                    String.valueOf(count),
                                    "group", name,
                                    name.equals(active)
                            ));
                        }
                    }

                    // Divider before settings
                    items.add(SidebarAdapter.SidebarItem.divider());

                    // Server selector
                    String serverLabel = data.optString("serverLabel", "No Server");
                    items.add(SidebarAdapter.SidebarItem.item(
                            R.drawable.ic_server, "Server: " + truncate(serverLabel, 15),
                            null, "server", null, false
                    ));

                    // Credential selector
                    String credLabel = data.optString("credentialLabel", "No Credential");
                    items.add(SidebarAdapter.SidebarItem.item(
                            R.drawable.ic_user, "User: " + truncate(credLabel, 15),
                            null, "credential", null, false
                    ));

                    // Divider
                    items.add(SidebarAdapter.SidebarItem.divider());

                    // Manage
                    items.add(SidebarAdapter.SidebarItem.item(
                            R.drawable.ic_settings, "Manage",
                            null, "manage", null, false
                    ));

                    // Change Password
                    items.add(SidebarAdapter.SidebarItem.item(
                            R.drawable.ic_key, "Change Password",
                            null, "password", null, false
                    ));

                    // Admin Panel (conditional)
                    if (data.optBoolean("isAdmin", false)) {
                        items.add(SidebarAdapter.SidebarItem.item(
                                R.drawable.ic_admin, "Admin Panel",
                                null, "admin", null, false
                        ));
                    }

                    // Logout
                    items.add(SidebarAdapter.SidebarItem.item(
                            R.drawable.ic_logout, "Logout",
                            null, "logout", null, false
                    ));

                    sidebarAdapter.updateItems(items);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing menu data", e);
                }
            });
        }

        /**
         * Called by WebView when D-pad Left is pressed at the edge of content.
         * Transfers focus back to the native sidebar.
         */
        @JavascriptInterface
        public void onWebViewEdge(String direction) {
            runOnUiThread(() -> {
                if ("left".equals(direction)) {
                    int pos = sidebarAdapter.findSelectedOrFirstPosition();
                    sidebarRecycler.scrollToPosition(pos);
                    RecyclerView.ViewHolder vh = sidebarRecycler.findViewHolderForAdapterPosition(pos);
                    if (vh != null) {
                        vh.itemView.requestFocus();
                    } else {
                        // ViewHolder not yet laid out, wait for layout
                        sidebarRecycler.post(() -> {
                            RecyclerView.ViewHolder vh2 = sidebarRecycler.findViewHolderForAdapterPosition(pos);
                            if (vh2 != null) {
                                vh2.itemView.requestFocus();
                            } else {
                                sidebarRecycler.requestFocus();
                            }
                        });
                    }
                }
            });
        }

        /**
         * Native speed test — tests server connectivity directly from the device.
         */
        @JavascriptInterface
        public void speedTestNative(String serversJson) {
            new Thread(() -> {
                try {
                    JSONArray servers = new JSONArray(serversJson);
                    JSONArray results = new JSONArray();
                    int threadCount = Math.min(servers.length(), 4);
                    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
                    CountDownLatch latch = new CountDownLatch(servers.length());

                    JSONObject[] resultSlots = new JSONObject[servers.length()];

                    for (int i = 0; i < servers.length(); i++) {
                        final int index = i;
                        final JSONObject server = servers.getJSONObject(i);
                        executor.submit(() -> {
                            JSONObject result = new JSONObject();
                            try {
                                int serverId = server.optInt("server_id", 0);
                                String hostname = server.getString("hostname");
                                String username = server.getString("username");
                                String password = server.getString("password");

                                String proto = hostname.startsWith("cf.") ? "https" : "http";
                                String testUrl = proto + "://" + hostname +
                                        "/player_api.php?username=" + username + "&password=" + password;

                                result.put("server_id", serverId);
                                result.put("hostname", hostname);

                                long startTime = System.currentTimeMillis();
                                HttpURLConnection conn = (HttpURLConnection) new URL(testUrl).openConnection();
                                conn.setRequestMethod("HEAD");
                                conn.setConnectTimeout(5000);
                                conn.setReadTimeout(10000);
                                conn.setInstanceFollowRedirects(true);

                                int responseCode = conn.getResponseCode();
                                long latency = System.currentTimeMillis() - startTime;
                                conn.disconnect();

                                if (responseCode >= 200 && responseCode < 400) {
                                    result.put("latency_ms", latency);
                                    result.put("status", "online");
                                } else {
                                    result.put("latency_ms", JSONObject.NULL);
                                    result.put("status", "offline");
                                }
                            } catch (Exception e) {
                                try {
                                    result.put("latency_ms", JSONObject.NULL);
                                    result.put("status", "offline");
                                } catch (Exception ignored) {}
                                Log.w("SpeedTest", "Server test failed: " + e.getMessage());
                            }
                            resultSlots[index] = result;
                            latch.countDown();
                        });
                    }

                    latch.await();
                    executor.shutdown();

                    for (JSONObject r : resultSlots) {
                        if (r != null) results.put(r);
                    }

                    final String jsonResult = results.toString();
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript(
                                    "javascript:window._nativeSpeedTestResult('" +
                                            jsonResult.replace("'", "\\'") + "')",
                                    null
                            );
                        }
                    });
                } catch (Exception e) {
                    Log.e("SpeedTest", "Speed test error", e);
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript(
                                    "javascript:window._nativeSpeedTestResult('[]')", null);
                        }
                    });
                }
            }).start();
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings webSettings = webView.getSettings();

        // Core settings
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setSupportZoom(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);

        // Media settings - critical for in-app video playback
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        // Mixed content - needed for HTTPS page loading HTTP IPTV streams
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Enable wide viewport so the viewport meta tag is respected
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);

        // Cache settings
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Support multiple windows (for player.html opening in new window)
        webSettings.setSupportMultipleWindows(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Custom User-Agent to identify as Android TV app
        String defaultUA = webSettings.getUserAgentString();
        webSettings.setUserAgentString(defaultUA + " AndroidTV IPTVManager/1.0");

        // Enable cookies
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        // WebViewClient - keeps all navigation in-app
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                hideSystemUI();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideSystemUI();
            }
        });

        // WebChromeClient - handles HTML5 fullscreen video playback
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;

                // Hide WebView and sidebar, show fullscreen video container
                webView.setVisibility(View.GONE);
                sidebarRecycler.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }

                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                sidebarRecycler.setVisibility(View.VISIBLE);

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }

                customView = null;
                customViewCallback = null;

                hideSystemUI();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView.HitTestResult result = view.getHitTestResult();
                String url = result.getExtra();

                if (url != null) {
                    view.loadUrl(url);
                    return false;
                }

                WebView tempWebView = new WebView(MainActivity.this);
                tempWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        String targetUrl = request.getUrl().toString();
                        webView.loadUrl(targetUrl);
                        return true;
                    }

                    @Override
                    @SuppressWarnings("deprecation")
                    public boolean shouldOverrideUrlLoading(WebView v, String targetUrl) {
                        webView.loadUrl(targetUrl);
                        return true;
                    }
                });

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tempWebView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                super.onCloseWindow(window);
            }
        });

        // Download listener - intercept .m3u file downloads
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                if (url.endsWith(".m3u") || url.endsWith(".m3u8") ||
                        "application/x-mpegurl".equals(mimetype) ||
                        "audio/x-mpegurl".equals(mimetype) ||
                        "application/vnd.apple.mpegurl".equals(mimetype)) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.parse(url), "video/*");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        webView.loadUrl(url);
                    }
                } else {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    } catch (Exception e) {
                        // Fallback - ignore the download
                    }
                }
            }
        });

        // Set WebView background to black (matches TV UI)
        webView.setBackgroundColor(0xFF000000);
    }

    /**
     * Enable immersive fullscreen mode - hides status bar, navigation bar.
     */
    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    /**
     * Handle back button - navigate WebView history before exiting.
     */
    @Override
    public void onBackPressed() {
        // If fullscreen video is showing, exit fullscreen first
        if (customView != null) {
            webView.getWebChromeClient();
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
            }
            fullscreenContainer.removeView(customView);
            fullscreenContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            sidebarRecycler.setVisibility(View.VISIBLE);
            customView = null;
            customViewCallback = null;
            return;
        }

        // If WebView has focus, transfer to sidebar
        if (webView.hasFocus()) {
            webView.evaluateJavascript("javascript:window.tvCheckLeftEdge && window.tvCheckLeftEdge()", null);
            return;
        }

        // If WebView can go back, navigate back in history
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Handle key events for D-pad navigation on Android TV.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideSystemUI();

        // Restore focus after returning from player
        mainHandler.postDelayed(() -> {
            if (webView != null) {
                webView.evaluateJavascript("javascript:window.tvRestoreFocus && window.tvRestoreFocus()", null);
            }
        }, 300);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.clearCache(true);
            webView.loadUrl("about:blank");
            webView.onPause();
            webView.removeAllViews();
            webView.destroyDrawingCache();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
