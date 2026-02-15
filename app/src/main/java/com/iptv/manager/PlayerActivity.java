package com.iptv.manager;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
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

    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar loadingSpinner;
    private TextView errorText;
    private TextView channelName;

    private String streamUrl;
    private String streamName;
    private String authToken;
    private String baseUrl;

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

    private void initPlayer(String url) {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        loadingSpinner.setVisibility(View.VISIBLE);
                        errorText.setVisibility(View.GONE);
                        reportDebug("player", "Buffering", "channel", streamName);
                        break;
                    case Player.STATE_READY:
                        loadingSpinner.setVisibility(View.GONE);
                        errorText.setVisibility(View.GONE);
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
                loadingSpinner.setVisibility(View.GONE);
                String errorCode = "code=" + error.errorCode;
                String errorMsg = error.getMessage() != null ? error.getMessage() : "unknown";
                Throwable cause = error.getCause();
                String causeMsg = cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "none";

                Log.e(TAG, "Playback error: " + errorMsg + " " + errorCode + " cause: " + causeMsg);
                reportDebug("player", "ERROR: " + errorMsg,
                        "channel", streamName,
                        "errorCode", errorCode,
                        "cause", causeMsg,
                        "url", streamUrl != null && streamUrl.length() > 120 ? streamUrl.substring(0, 120) : streamUrl);
                showError("Playback error: " + errorMsg);
            }
        });

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(mediaItem);
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
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }
}
