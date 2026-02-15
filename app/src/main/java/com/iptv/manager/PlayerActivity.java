package com.iptv.manager;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Native video player using ExoPlayer for HLS streams.
 * Launched from the WebView via JavaScript interface.
 * Back button returns to the channel list (WebView).
 * Reports playback errors back to the server for debugging.
 */
public class PlayerActivity extends Activity {

    private static final String TAG = "PlayerActivity";

    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_NAME = "channel_name";
    public static final String EXTRA_TOKEN = "auth_token";
    public static final String EXTRA_BASE_URL = "base_url";
    public static final String EXTRA_FALLBACK_URL = "fallback_url";

    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar loadingSpinner;
    private TextView errorText;
    private TextView channelName;

    private String streamUrl;
    private String streamName;
    private String authToken;
    private String baseUrl;
    private String fallbackUrl;
    private int behindLiveRetries = 0;
    private static final int MAX_BEHIND_LIVE_RETRIES = 3;

    // General error retry with exponential backoff
    private int errorRetryCount = 0;
    private static final int MAX_ERROR_RETRIES = 5;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000, 8000, 8000};
    private final Handler retryHandler = new Handler(Looper.getMainLooper());

    // Custom HLS source factory (stored for reuse during retries)
    private HlsMediaSource.Factory hlsFactory;
    private MediaItem hlsMediaItem;

    // WiFi lock — prevent WiFi power-save during playback
    private WifiManager.WifiLock wifiLock;

    // Audio focus management
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;

    // Player event listener (stored for cleanup)
    private Player.Listener playerListener;

    @Override
    @OptIn(markerClass = UnstableApi.class)
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_view);
        loadingSpinner = findViewById(R.id.loading_spinner);
        errorText = findViewById(R.id.error_text);
        channelName = findViewById(R.id.channel_name);

        streamUrl = getIntent().getStringExtra(EXTRA_URL);
        streamName = getIntent().getStringExtra(EXTRA_NAME);
        authToken = getIntent().getStringExtra(EXTRA_TOKEN);
        baseUrl = getIntent().getStringExtra(EXTRA_BASE_URL);
        fallbackUrl = getIntent().getStringExtra(EXTRA_FALLBACK_URL);

        if (streamName != null && !streamName.isEmpty()) {
            channelName.setText(streamName);
            channelName.setVisibility(View.VISIBLE);
        } else {
            channelName.setVisibility(View.GONE);
        }

        if (streamUrl == null || streamUrl.isEmpty()) {
            showError("No stream URL provided");
            return;
        }

        reportDebug("player", "ExoPlayer starting",
                "channel", streamName, "url", streamUrl.length() > 120 ? streamUrl.substring(0, 120) : streamUrl);
        initPlayer(streamUrl);
        hideSystemUI();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void initPlayer(String url) {
        // WiFi lock — prevent WiFi power-save during playback
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG + ":wifi_lock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                Log.d(TAG, "WiFi lock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire WiFi lock: " + e.getMessage());
        }

        // Audio focus — request focus for media playback
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioFocusChangeListener = focusChange -> {
            if (player == null) return;
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    Log.d(TAG, "Audio focus lost — stopping playback");
                    player.setPlayWhenReady(false);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    Log.d(TAG, "Audio focus lost transiently — pausing");
                    player.setPlayWhenReady(false);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    Log.d(TAG, "Audio focus duck — lowering volume");
                    player.setVolume(0.3f);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.d(TAG, "Audio focus gained — resuming");
                    player.setVolume(1.0f);
                    player.setPlayWhenReady(true);
                    break;
            }
        };
        if (audioManager != null) {
            int result = audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            Log.d(TAG, "Audio focus request result: " + result);
        }

        // 2B. Custom LoadControl — tune buffers for Fire TV Stick
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        25_000,  // minBufferMs (was 10s, back to default 25s)
                        50_000,  // maxBufferMs (was 30s, back to default 50s)
                        1_500,   // bufferForPlaybackMs (keep fast start)
                        3_000    // bufferForPlaybackAfterRebufferMs (keep fast recovery)
                )
                .build();

        // 2C. DefaultTrackSelector — let ABR decide quality (no resolution cap)
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);

        // 2D. DefaultBandwidthMeter — enable adaptive bitrate
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(this).build();

        // Build ExoPlayer with custom components
        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setBandwidthMeter(bandwidthMeter)
                .build();

        playerView.setPlayer(player);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setControllerAutoShow(false);

        playerListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        loadingSpinner.setVisibility(View.VISIBLE);
                        errorText.setVisibility(View.GONE);
                        // Live edge optimization: if too far behind live, skip to live edge
                        if (player.isCurrentMediaItemLive()) {
                            long liveOffset = player.getCurrentLiveOffset();
                            if (liveOffset > 10_000) { // more than 10s behind
                                Log.w(TAG, "Live offset " + liveOffset + "ms — seeking to live edge");
                                reportDebug("player", "Seeking to live edge",
                                        "channel", streamName,
                                        "liveOffsetMs", String.valueOf(liveOffset));
                                player.seekToDefaultPosition();
                            }
                        }
                        reportDebug("player", "Buffering", "channel", streamName);
                        break;
                    case Player.STATE_READY:
                        loadingSpinner.setVisibility(View.GONE);
                        errorText.setVisibility(View.GONE);
                        behindLiveRetries = 0;
                        errorRetryCount = 0; // Reset general retry counter on successful playback
                        reportDebug("player", "Playback started", "channel", streamName);
                        // Auto-hide channel name after 3 seconds
                        channelName.postDelayed(() ->
                                channelName.setVisibility(View.GONE), 3000);
                        break;
                    case Player.STATE_ENDED:
                        reportDebug("player", "Stream ended", "channel", streamName);
                        showError("Stream ended");
                        break;
                    case Player.STATE_IDLE:
                        break;
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Throwable cause = error.getCause();

                String errorCode = "code=" + error.errorCode;
                String errorMsg = error.getMessage() != null ? error.getMessage() : "unknown";
                String causeMsg = cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "none";

                Log.e(TAG, "Playback error: " + errorMsg + " " + errorCode + " cause: " + causeMsg);
                reportDebug("player", "ERROR: " + errorMsg,
                        "channel", streamName,
                        "errorCode", errorCode,
                        "cause", causeMsg,
                        "url", streamUrl != null && streamUrl.length() > 120 ? streamUrl.substring(0, 120) : streamUrl);

                // 2G. BehindLiveWindowException: seek to live edge and retry
                if (isBehindLiveWindow(cause) && behindLiveRetries < MAX_BEHIND_LIVE_RETRIES) {
                    behindLiveRetries++;
                    Log.w(TAG, "Behind live window — re-preparing (attempt " + behindLiveRetries + ")");
                    reportDebug("player", "Behind live window — recovering",
                            "channel", streamName, "attempt", String.valueOf(behindLiveRetries));
                    loadingSpinner.setVisibility(View.VISIBLE);
                    errorText.setVisibility(View.GONE);
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }

                // 2G. General error retry with exponential backoff
                if (errorRetryCount < MAX_ERROR_RETRIES) {
                    long delay = RETRY_DELAYS_MS[errorRetryCount];
                    errorRetryCount++;
                    Log.w(TAG, "Retrying playback in " + delay + "ms (attempt " + errorRetryCount + "/" + MAX_ERROR_RETRIES + ")");
                    reportDebug("player", "Retrying after error",
                            "channel", streamName,
                            "attempt", String.valueOf(errorRetryCount),
                            "delayMs", String.valueOf(delay));

                    // Show loading spinner during retry (not error text)
                    loadingSpinner.setVisibility(View.VISIBLE);
                    errorText.setVisibility(View.GONE);

                    retryHandler.postDelayed(() -> {
                        if (player != null && !isFinishing()) {
                            player.prepare();
                        }
                    }, delay);
                    return;
                }

                // All retries exhausted — try fallback URL if available
                if (fallbackUrl != null && !streamUrl.equals(fallbackUrl)) {
                    Log.w(TAG, "Primary URL failed, switching to proxy fallback");
                    reportDebug("player", "Switching to proxy fallback", "channel", streamName);
                    streamUrl = fallbackUrl;
                    fallbackUrl = null; // don't loop
                    errorRetryCount = 0;
                    behindLiveRetries = 0;
                    releasePlayer();
                    initPlayer(streamUrl);
                    return;
                }

                // All retries exhausted — show error
                showError("Playback error: " + errorMsg);
            }
        };
        player.addListener(playerListener);

        // 2E. Custom HttpDataSource.Factory
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true);

        // 2F. HlsMediaSource with LiveConfiguration
        hlsFactory = new HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true);

        hlsMediaItem = new MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(5_000)    // stay 5s behind live edge
                                .setMinOffsetMs(2_000)       // never closer than 2s
                                .setMaxOffsetMs(12_000)      // if >12s behind, seek to live
                                .setMinPlaybackSpeed(1.0f)   // never slow down
                                .setMaxPlaybackSpeed(1.04f)  // gentle catch-up only
                                .build()
                )
                .build();

        HlsMediaSource hlsMediaSource = hlsFactory.createMediaSource(hlsMediaItem);
        player.setMediaSource(hlsMediaSource);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    /**
     * Report a debug event back to the server for remote diagnostics.
     * Runs on a background thread. Args are key-value pairs.
     */
    private void reportDebug(String category, String message, String... kvPairs) {
        if (authToken == null || baseUrl == null) return;
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("token", authToken);
                json.put("category", category);
                json.put("message", message);
                if (kvPairs.length > 0) {
                    JSONObject data = new JSONObject();
                    for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                        data.put(kvPairs[i], kvPairs[i + 1]);
                    }
                    json.put("data", data);
                }
                byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

                HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/debug/report").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.close();
                int code = conn.getResponseCode();
                Log.d(TAG, "Debug report sent: " + code);
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Debug report failed: " + e.getMessage());
            }
        }).start();
    }

    private static boolean isBehindLiveWindow(Throwable e) {
        while (e != null) {
            if (e instanceof BehindLiveWindowException) return true;
            e = e.getCause();
        }
        return false;
    }

    private void showError(String message) {
        loadingSpinner.setVisibility(View.GONE);
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

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
        if (hasFocus) hideSystemUI();
    }

    @Override
    public void onBackPressed() {
        reportDebug("player", "Back pressed, closing player", "channel", streamName);
        releasePlayer();
        super.onBackPressed();
    }

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
        hideSystemUI();
        if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    protected void onPause() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    private void releasePlayer() {
        retryHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            if (playerListener != null) {
                player.removeListener(playerListener);
            }
            player.stop();
            player.release();
            player = null;
        }
        // Release WiFi lock
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            Log.d(TAG, "WiFi lock released");
        }
        // Abandon audio focus
        if (audioManager != null && audioFocusChangeListener != null) {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
            Log.d(TAG, "Audio focus abandoned");
        }
    }
}
