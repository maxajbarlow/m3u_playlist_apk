package com.iptv.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.iptv.manager.model.Channel;
import com.iptv.manager.model.EpgEntry;
import com.iptv.manager.model.ServerConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Main activity — fully native Android TV channel browser.
 * No WebView. Uses RecyclerView for channels + native sidebar.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final long EPG_REFRESH_INTERVAL = 60_000; // 60s

    // UI
    private RecyclerView sidebarRecycler;
    private SidebarAdapter sidebarAdapter;
    private RecyclerView channelRecycler;
    private ChannelAdapter channelAdapter;
    private TextView headerTitle;
    private TextView headerCount;
    private LinearLayout emptyState;
    private TextView emptyTitle;
    private TextView emptySubtitle;
    private ProgressBar loadingSpinner;

    // Data
    private TokenManager tokenManager;
    private ApiClient apiClient;
    private ServerConfig serverConfig;
    private boolean isAdmin = false;
    private int lastFocusedChannelPosition = 0;

    // EPG refresh
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable epgRefreshRunnable = this::loadEpgForVisibleChannels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        tokenManager = new TokenManager(this);
        apiClient = ApiClient.getInstance(tokenManager);

        // Sidebar
        sidebarRecycler = findViewById(R.id.sidebar_recycler);
        sidebarAdapter = new SidebarAdapter();
        sidebarRecycler.setLayoutManager(new LinearLayoutManager(this));
        sidebarRecycler.setAdapter(sidebarAdapter);
        sidebarAdapter.setLoadingState();

        // Channel list
        channelRecycler = findViewById(R.id.channel_recycler);
        channelAdapter = new ChannelAdapter();
        channelRecycler.setLayoutManager(new LinearLayoutManager(this));
        channelRecycler.setAdapter(channelAdapter);

        headerTitle = findViewById(R.id.header_title);
        headerCount = findViewById(R.id.header_count);
        emptyState = findViewById(R.id.empty_state);
        emptyTitle = findViewById(R.id.empty_title);
        emptySubtitle = findViewById(R.id.empty_subtitle);
        loadingSpinner = findViewById(R.id.loading_spinner);

        // Sidebar actions
        sidebarAdapter.setActionListener(new SidebarAdapter.OnSidebarActionListener() {
            @Override
            public void onItemClick(SidebarAdapter.SidebarItem item) {
                handleSidebarClick(item);
            }

            @Override
            public void onFocusTransferToWebView() {
                // Transfer focus to channel list (replaces WebView)
                if (channelAdapter.getItemCount() > 0) {
                    channelRecycler.requestFocus();
                    RecyclerView.ViewHolder vh = channelRecycler.findViewHolderForAdapterPosition(0);
                    if (vh != null) vh.itemView.requestFocus();
                }
            }
        });

        // Sidebar search
        sidebarAdapter.setSearchListener(query -> {
            channelAdapter.setSearch(query);
            updateEmptyState(); // also updates content header
        });

        // Channel actions
        channelAdapter.setActionListener(new ChannelAdapter.OnChannelActionListener() {
            @Override
            public void onChannelClick(Channel channel) {
                playChannel(channel);
            }

            @Override
            public void onChannelLongClick(Channel channel) {
                toggleFavourite(channel);
            }

            @Override
            public void onFocusTransferToSidebar() {
                int pos = sidebarAdapter.findSelectedOrFirstPosition();
                sidebarRecycler.scrollToPosition(pos);
                RecyclerView.ViewHolder vh = sidebarRecycler.findViewHolderForAdapterPosition(pos);
                if (vh != null) {
                    vh.itemView.requestFocus();
                } else {
                    sidebarRecycler.post(() -> {
                        RecyclerView.ViewHolder vh2 = sidebarRecycler.findViewHolderForAdapterPosition(pos);
                        if (vh2 != null) vh2.itemView.requestFocus();
                        else sidebarRecycler.requestFocus();
                    });
                }
            }
        });

        // Load data
        showLoading(true);
        loadData();

        // Check for app updates
        String url = tokenManager.getBaseUrl();
        if (url != null) {
            new AppUpdater(this, url).checkForUpdate();
        }
    }

    // ── Data Loading ─────────────────────────────────────────

    private void loadData() {
        apiClient.getConfig(new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                serverConfig = ServerConfig.fromJson(response);

                // Check admin status
                apiClient.getMe(new ApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject meResp) {
                        JSONObject user = meResp.optJSONObject("user");
                        if (user != null) {
                            isAdmin = user.optBoolean("is_admin", false);
                        }
                        loadChannels();
                    }

                    @Override
                    public void onError(String error) {
                        // Not critical, continue loading
                        loadChannels();
                    }
                });
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                Log.e(TAG, "Config load failed: " + error);
                showEmptyState(getString(R.string.error_load_failed), error);
            }
        });
    }

    private void loadChannels() {
        apiClient.getChannels(new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                showLoading(false);
                JSONArray channelsArr = response.optJSONArray("channels");
                if (channelsArr == null) {
                    showEmptyState(getString(R.string.empty_no_channels), getString(R.string.empty_import_hint));
                    updateSidebar();
                    return;
                }

                List<Channel> channels = new ArrayList<>();
                for (int i = 0; i < channelsArr.length(); i++) {
                    JSONObject obj = channelsArr.optJSONObject(i);
                    if (obj != null) {
                        channels.add(Channel.fromJson(obj));
                    }
                }

                channelAdapter.setChannels(channels);
                updateSidebar();
                updateEmptyState();
                loadEpgForVisibleChannels();
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                Log.e(TAG, "Channels load failed: " + error);
                showEmptyState(getString(R.string.error_load_failed), error);
                updateSidebar();
            }
        });
    }

    // ── EPG ──────────────────────────────────────────────────

    private void loadEpgForVisibleChannels() {
        mainHandler.removeCallbacks(epgRefreshRunnable);

        List<Channel> visible = channelAdapter.getFilteredChannels();
        if (visible.isEmpty()) {
            mainHandler.postDelayed(epgRefreshRunnable, EPG_REFRESH_INTERVAL);
            return;
        }

        // Build CSV of channel IDs (batch up to 200)
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(visible.size(), 200);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(",");
            sb.append(visible.get(i).channelId);
        }

        apiClient.getEpg(sb.toString(), new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONObject epgObj = response.optJSONObject("epg");
                if (epgObj != null) {
                    Map<String, EpgEntry> epgMap = new HashMap<>();
                    Iterator<String> keys = epgObj.keys();
                    while (keys.hasNext()) {
                        String channelId = keys.next();
                        JSONObject entry = epgObj.optJSONObject(channelId);
                        if (entry != null) {
                            epgMap.put(channelId, EpgEntry.fromJson(entry));
                        }
                    }
                    channelAdapter.updateEpg(epgMap);
                }
                // Schedule next refresh
                mainHandler.postDelayed(epgRefreshRunnable, EPG_REFRESH_INTERVAL);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "EPG load failed: " + error);
                mainHandler.postDelayed(epgRefreshRunnable, EPG_REFRESH_INTERVAL);
            }
        });
    }

    // ── Sidebar ──────────────────────────────────────────────

    private void updateSidebar() {
        List<SidebarAdapter.SidebarItem> items = new ArrayList<>();
        String activeFilter = channelAdapter.getCurrentFilter();

        // All Channels
        items.add(SidebarAdapter.SidebarItem.item(
                R.drawable.ic_all_channels, "All Channels",
                String.valueOf(channelAdapter.getAllCount()),
                "all", null,
                "all".equals(activeFilter)
        ));

        // Favourites
        int favCount = channelAdapter.getFavouriteCount();
        if (favCount > 0) {
            items.add(SidebarAdapter.SidebarItem.item(
                    R.drawable.ic_star, "Favourites",
                    String.valueOf(favCount),
                    "favourites", null,
                    "favourites".equals(activeFilter)
            ));
        }

        // Recently Played
        if (serverConfig != null && !serverConfig.recents.isEmpty()) {
            items.add(SidebarAdapter.SidebarItem.item(
                    R.drawable.ic_history, "Recently Played",
                    String.valueOf(serverConfig.recents.size()),
                    "recent", null,
                    "recent".equals(activeFilter)
            ));
        }

        // Divider
        items.add(SidebarAdapter.SidebarItem.divider());

        // Groups — apply saved group order if available
        List<ChannelAdapter.GroupInfo> groups = channelAdapter.getGroups();
        Map<String, ChannelAdapter.GroupInfo> groupMap = new HashMap<>();
        for (ChannelAdapter.GroupInfo g : groups) groupMap.put(g.name, g);

        List<ChannelAdapter.GroupInfo> orderedGroups = new ArrayList<>();
        if (serverConfig != null && !serverConfig.groupOrder.isEmpty()) {
            // Add groups in saved order first
            for (String name : serverConfig.groupOrder) {
                ChannelAdapter.GroupInfo g = groupMap.remove(name);
                if (g != null) orderedGroups.add(g);
            }
            // Append any new groups not in saved order (alphabetical)
            List<String> remaining = new ArrayList<>(groupMap.keySet());
            java.util.Collections.sort(remaining);
            for (String name : remaining) orderedGroups.add(groupMap.get(name));
        } else {
            // Default: alphabetical
            List<String> names = new ArrayList<>(groupMap.keySet());
            java.util.Collections.sort(names);
            for (String name : names) orderedGroups.add(groupMap.get(name));
        }

        for (ChannelAdapter.GroupInfo g : orderedGroups) {
            items.add(SidebarAdapter.SidebarItem.item(
                    R.drawable.ic_collection, g.name,
                    String.valueOf(g.count),
                    "group", g.name,
                    "group".equals(activeFilter) && g.name.equals(channelAdapter.getCurrentGroup())
            ));
        }

        // Divider
        items.add(SidebarAdapter.SidebarItem.divider());

        // Server selector
        String serverLabel = "No Server";
        if (serverConfig != null && serverConfig.getActiveServer() != null) {
            serverLabel = truncate(serverConfig.getActiveServer(), 15);
        }
        items.add(SidebarAdapter.SidebarItem.item(
                R.drawable.ic_server, "Server: " + serverLabel,
                null, "server", null, false
        ));

        // Credential selector
        String credLabel = "No Credential";
        if (serverConfig != null && serverConfig.getActiveCredential() != null) {
            credLabel = truncate(serverConfig.getActiveCredential().username, 15);
        }
        items.add(SidebarAdapter.SidebarItem.item(
                R.drawable.ic_user, "User: " + credLabel,
                null, "credential", null, false
        ));

        // Divider
        items.add(SidebarAdapter.SidebarItem.divider());

        // Manage (import M3U, etc)
        items.add(SidebarAdapter.SidebarItem.item(
                R.drawable.ic_settings, "Manage",
                null, "manage", null, false
        ));

        // Change Password
        items.add(SidebarAdapter.SidebarItem.item(
                R.drawable.ic_key, "Change Password",
                null, "password", null, false
        ));

        // Admin Panel
        if (isAdmin) {
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
    }

    // ── Sidebar Click Handling ───────────────────────────────

    private void handleSidebarClick(SidebarAdapter.SidebarItem item) {
        if (item.actionType == null) return;

        switch (item.actionType) {
            case "all":
                channelAdapter.setFilter("all", null);
                updateSidebar();
                updateEmptyState();
                loadEpgForVisibleChannels();
                break;

            case "favourites":
                channelAdapter.setFilter("favourites", null);
                updateSidebar();
                updateEmptyState();
                loadEpgForVisibleChannels();
                break;

            case "recent":
                if (serverConfig != null && !serverConfig.recents.isEmpty()) {
                    List<String> recentIds = new ArrayList<>();
                    for (ServerConfig.RecentChannel r : serverConfig.recents) {
                        recentIds.add(r.channelId);
                    }
                    channelAdapter.setRecentFilter(recentIds);
                    updateSidebar();
                    updateEmptyState();
                    loadEpgForVisibleChannels();
                }
                break;

            case "group":
                channelAdapter.setFilter("group", item.actionData);
                updateSidebar();
                updateEmptyState();
                loadEpgForVisibleChannels();
                break;

            case "server":
                showServerSelector();
                break;

            case "credential":
                showCredentialSelector();
                break;

            case "manage":
            case "password":
            case "admin":
                openWebViewFallback(item.actionType);
                break;

            case "logout":
                showLogoutConfirm();
                break;
        }
    }

    // ── Playback ─────────────────────────────────────────────

    private void playChannel(Channel channel) {
        if (serverConfig == null) return;

        String server = serverConfig.getActiveServer();
        ServerConfig.CredentialInfo cred = serverConfig.getActiveCredential();
        if (server == null || cred == null) return;

        // Remember position for focus restore
        View focused = channelRecycler.getFocusedChild();
        if (focused != null) {
            lastFocusedChannelPosition = channelRecycler.getChildAdapterPosition(focused);
        }

        // Build direct stream URL
        String proto = server.startsWith("cf.") ? "https" : "http";
        String directUrl = proto + "://" + server + "/live/" + cred.username + "/" + cred.password + "/" + channel.channelId + ".m3u8";

        // Build proxy fallback URL
        String baseUrl = tokenManager.getBaseUrl();
        String fallbackUrl = baseUrl + "/proxy/" + channel.channelId + ".m3u8";

        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URL, directUrl);
        intent.putExtra(PlayerActivity.EXTRA_NAME, channel.name);
        intent.putExtra(PlayerActivity.EXTRA_TOKEN, tokenManager.getToken());
        intent.putExtra(PlayerActivity.EXTRA_BASE_URL, baseUrl);
        intent.putExtra(PlayerActivity.EXTRA_FALLBACK_URL, fallbackUrl);
        startActivity(intent);

        // Track as recent
        apiClient.addRecent(channel.channelId, channel.name, channel.group, null);
    }

    // ── Favourite Toggle ─────────────────────────────────────

    private void toggleFavourite(Channel channel) {
        apiClient.toggleFavourite(channel.channelId, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                boolean newState = response.optBoolean("favourite", !channel.favourite);
                channelAdapter.updateFavourite(channel.channelId, newState);
                updateSidebar();
                updateEmptyState();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Favourite toggle failed: " + error);
            }
        });
    }

    // ── Server / Credential Selector ─────────────────────────

    private void showServerSelector() {
        if (serverConfig == null || serverConfig.servers.isEmpty()) return;

        String[] items = new String[serverConfig.servers.size()];
        for (int i = 0; i < serverConfig.servers.size(); i++) {
            String status = i < serverConfig.serverStatuses.size() ? serverConfig.serverStatuses.get(i) : "";
            items[i] = serverConfig.servers.get(i);
            if (status != null && !status.isEmpty()) {
                items[i] += " (" + status + ")";
            }
            if (i == serverConfig.activeServerIndex) {
                items[i] += " ✓";
            }
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.select_server))
                .setItems(items, (dialog, which) -> {
                    if (which == serverConfig.activeServerIndex) return;
                    int serverId = serverConfig.serverIds.get(which);
                    apiClient.setActiveServer(serverId, new ApiClient.Callback() {
                        @Override
                        public void onSuccess(JSONObject response) {
                            serverConfig.activeServerIndex = which;
                            updateSidebar();
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Server switch failed: " + error);
                        }
                    });
                })
                .show();
    }

    private void showCredentialSelector() {
        if (serverConfig == null || serverConfig.credentials.isEmpty()) return;

        String[] items = new String[serverConfig.credentials.size()];
        for (int i = 0; i < serverConfig.credentials.size(); i++) {
            items[i] = serverConfig.credentials.get(i).username;
            if (i == serverConfig.activeCredentialIndex) {
                items[i] += " ✓";
            }
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.select_credential))
                .setItems(items, (dialog, which) -> {
                    if (which == serverConfig.activeCredentialIndex) return;
                    int credId = serverConfig.credentials.get(which).id;
                    apiClient.setActiveCredential(credId, new ApiClient.Callback() {
                        @Override
                        public void onSuccess(JSONObject response) {
                            serverConfig.activeCredentialIndex = which;
                            updateSidebar();
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Credential switch failed: " + error);
                        }
                    });
                })
                .show();
    }

    // ── Logout ───────────────────────────────────────────────

    private void showLogoutConfirm() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.confirm_logout))
                .setPositiveButton(getString(R.string.logout), (dialog, which) -> {
                    tokenManager.clear();
                    ApiClient.reset();
                    Intent intent = new Intent(this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    // ── WebView Fallback (admin, manage, password) ───────────

    private void openWebViewFallback(String section) {
        String baseUrl = tokenManager.getBaseUrl();
        if (baseUrl == null) return;

        String targetUrl;
        switch (section) {
            case "admin":
                targetUrl = baseUrl + "/admin";
                break;
            case "manage":
                targetUrl = baseUrl + "/#manage";
                break;
            case "password":
                targetUrl = baseUrl + "/#password";
                break;
            default:
                return;
        }

        // Launch system browser or a simple WebView activity
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(targetUrl));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open: " + targetUrl, e);
        }
    }

    // ── Content Header ────────────────────────────────────────

    private void updateContentHeader() {
        String filter = channelAdapter.getCurrentFilter();
        String title;
        switch (filter) {
            case "favourites":
                title = "Favourites";
                break;
            case "all":
                title = "All Channels";
                break;
            case "recent":
                title = "Recently Played";
                break;
            case "group":
                String group = channelAdapter.getCurrentGroup();
                title = group != null ? group : "Group";
                break;
            default:
                title = "Channels";
                break;
        }
        headerTitle.setText(title);
        int count = channelAdapter.getItemCount();
        headerCount.setText(count + " channel" + (count != 1 ? "s" : ""));
    }

    // ── UI Helpers ───────────────────────────────────────────

    private void showLoading(boolean show) {
        loadingSpinner.setVisibility(show ? View.VISIBLE : View.GONE);
        channelRecycler.setVisibility(show ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(View.GONE);
    }

    private void showEmptyState(String title, String subtitle) {
        emptyState.setVisibility(View.VISIBLE);
        channelRecycler.setVisibility(View.GONE);
        emptyTitle.setText(title);
        emptySubtitle.setText(subtitle != null ? subtitle : "");
    }

    private void updateEmptyState() {
        updateContentHeader();
        if (channelAdapter.getItemCount() == 0) {
            String filter = channelAdapter.getCurrentFilter();
            switch (filter) {
                case "favourites":
                    showEmptyState(getString(R.string.empty_no_favourites), null);
                    break;
                case "recent":
                    showEmptyState(getString(R.string.empty_no_recent), null);
                    break;
                default:
                    showEmptyState(getString(R.string.empty_no_channels), null);
                    break;
            }
        } else {
            emptyState.setVisibility(View.GONE);
            channelRecycler.setVisibility(View.VISIBLE);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    // ── Fullscreen ───────────────────────────────────────────

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

    // ── Back Button ──────────────────────────────────────────

    @Override
    public void onBackPressed() {
        // If channel list has focus, transfer to sidebar
        if (channelRecycler.hasFocus()) {
            int pos = sidebarAdapter.findSelectedOrFirstPosition();
            sidebarRecycler.scrollToPosition(pos);
            RecyclerView.ViewHolder vh = sidebarRecycler.findViewHolderForAdapterPosition(pos);
            if (vh != null) {
                vh.itemView.requestFocus();
            } else {
                sidebarRecycler.requestFocus();
            }
            return;
        }

        // If sidebar has focus, show exit confirmation
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Exit IPTV Manager?")
                .setPositiveButton("Exit", (dialog, which) -> finish())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Lifecycle ────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();

        // Restore focus to last channel
        mainHandler.postDelayed(() -> {
            if (channelAdapter.getItemCount() > 0 && !sidebarRecycler.hasFocus()) {
                int pos = Math.min(lastFocusedChannelPosition, channelAdapter.getItemCount() - 1);
                channelRecycler.scrollToPosition(pos);
                RecyclerView.ViewHolder vh = channelRecycler.findViewHolderForAdapterPosition(pos);
                if (vh != null) vh.itemView.requestFocus();
            }
        }, 200);

        // Restart EPG refresh
        mainHandler.postDelayed(epgRefreshRunnable, EPG_REFRESH_INTERVAL);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(epgRefreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(epgRefreshRunnable);
        super.onDestroy();
    }
}
