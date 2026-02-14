package com.iptv.manager;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

/**
 * Native video player using ExoPlayer for HLS streams.
 * Launched from the WebView via JavaScript interface.
 * Back button returns to the channel list (WebView).
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_NAME = "channel_name";

    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar loadingSpinner;
    private TextView errorText;
    private TextView channelName;

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

        String url = getIntent().getStringExtra(EXTRA_URL);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        if (name != null && !name.isEmpty()) {
            channelName.setText(name);
            channelName.setVisibility(View.VISIBLE);
        } else {
            channelName.setVisibility(View.GONE);
        }

        if (url == null || url.isEmpty()) {
            showError("No stream URL provided");
            return;
        }

        initPlayer(url);
        hideSystemUI();
    }

    private void initPlayer(String url) {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // Hide channel name after playback starts
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        loadingSpinner.setVisibility(View.VISIBLE);
                        errorText.setVisibility(View.GONE);
                        break;
                    case Player.STATE_READY:
                        loadingSpinner.setVisibility(View.GONE);
                        errorText.setVisibility(View.GONE);
                        // Auto-hide channel name after 3 seconds
                        channelName.postDelayed(() ->
                                channelName.setVisibility(View.GONE), 3000);
                        break;
                    case Player.STATE_ENDED:
                        showError("Stream ended");
                        break;
                    case Player.STATE_IDLE:
                        break;
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                loadingSpinner.setVisibility(View.GONE);
                showError("Playback error: " + error.getMessage());
            }
        });

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(mediaItem);
        player.setPlayWhenReady(true);
        player.prepare();
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

    /**
     * Back button finishes this activity and returns to the WebView (channel list).
     */
    @Override
    public void onBackPressed() {
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
