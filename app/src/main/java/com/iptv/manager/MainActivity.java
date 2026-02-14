package com.iptv.manager;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
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

/**
 * Main activity that wraps the IPTV Manager web app in a fullscreen WebView.
 * Optimized for Android TV with D-pad navigation and in-app video playback.
 */
public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

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
     * JavaScript interface exposed as window.Android in the WebView.
     * Allows the web app to launch the native ExoPlayer for HLS streams.
     */
    private class WebAppInterface {
        @JavascriptInterface
        public void playStream(String url, String name) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URL, url);
                intent.putExtra(PlayerActivity.EXTRA_NAME, name);
                startActivity(intent);
            });
        }
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

        // Enable wide viewport for TV display
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
                // Keep all navigation inside the WebView
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keep all navigation inside the WebView
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
                // HTML5 video entering fullscreen
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;

                // Hide WebView and show fullscreen video container
                webView.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                // HTML5 video exiting fullscreen
                if (customView == null) {
                    return;
                }

                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }

                customView = null;
                customViewCallback = null;

                hideSystemUI();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // Handle window.open() - load new URLs in the same WebView
                // This prevents player.html from opening in an external browser
                WebView.HitTestResult result = view.getHitTestResult();
                String url = result.getExtra();

                if (url != null) {
                    // Direct URL from hit test - load it in the same WebView
                    view.loadUrl(url);
                    return false;
                }

                // For JavaScript-initiated window.open(), create a temporary WebView
                // to capture the URL and load it in the main WebView
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
                // If it's an M3U file, try to open with VLC or other media player
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
                        // If no app handles the intent, try loading in WebView
                        webView.loadUrl(url);
                    }
                } else {
                    // For other downloads, attempt to open via intent
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
            customView = null;
            customViewCallback = null;
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
        // Let the WebView handle D-pad navigation natively
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
