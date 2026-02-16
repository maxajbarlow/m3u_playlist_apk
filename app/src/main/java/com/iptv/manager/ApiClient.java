package com.iptv.manager;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Singleton HTTP client for all API calls. Handles JWT auth header,
 * background threads, and main-thread callbacks.
 */
public class ApiClient {

    private static final String TAG = "ApiClient";
    private static ApiClient instance;

    private final TokenManager tokenManager;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onSuccess(JSONObject response);
        void onError(String error);
    }

    private ApiClient(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public static synchronized ApiClient getInstance(TokenManager tokenManager) {
        if (instance == null) {
            instance = new ApiClient(tokenManager);
        }
        return instance;
    }

    /** Reset singleton (for logout) */
    public static synchronized void reset() {
        if (instance != null) {
            instance.executor.shutdownNow();
            instance = null;
        }
    }

    // ── Auth ─────────────────────────────────────────────────

    public void login(String baseUrl, String username, String password, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);
                JSONObject resp = doPost(baseUrl + "/api/auth/login", body, null);
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    public void getMe(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = doGet(getUrl("/api/auth/me"));
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    // ── Config ───────────────────────────────────────────────

    public void getConfig(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = doGet(getUrl("/api/config"));
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    // ── Channels ─────────────────────────────────────────────

    public void getChannels(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = doGet(getUrl("/api/channels"));
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    public void toggleFavourite(String channelId, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = doPost(getUrl("/api/channels/" + channelId + "/favourite"), new JSONObject(), tokenManager.getToken());
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    // ── EPG ──────────────────────────────────────────────────

    public void getEpg(String channelIdsCsv, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = doGet(getUrl("/api/epg?channel_ids=" + channelIdsCsv));
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    // ── Recents ──────────────────────────────────────────────

    public void addRecent(String channelId, String name, String group, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("channel_id", channelId);
                body.put("name", name);
                body.put("group", group);
                doPost(getUrl("/api/user/recent"), body, tokenManager.getToken());
                if (callback != null) mainHandler.post(() -> callback.onSuccess(new JSONObject()));
            } catch (Exception e) {
                if (callback != null) mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    // ── Preferences ──────────────────────────────────────────

    public void setActiveServer(int serverId, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("active_server_id", serverId);
                JSONObject resp = doPut(getUrl("/api/preferences"), body);
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    public void setActiveCredential(int credentialId, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("active_credential_id", credentialId);
                JSONObject resp = doPut(getUrl("/api/preferences"), body);
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(parseError(e)));
            }
        });
    }

    // ── HTTP helpers ─────────────────────────────────────────

    private String getUrl(String path) {
        return tokenManager.getBaseUrl() + path;
    }

    private JSONObject doGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        String token = tokenManager.getToken();
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setRequestProperty("Accept", "application/json");
        return readResponse(conn);
    }

    private JSONObject doPost(String urlStr, JSONObject body, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        if (token == null) token = tokenManager.getToken();
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }
        return readResponse(conn);
    }

    private JSONObject doPut(String urlStr, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("PUT");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        String token = tokenManager.getToken();
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }
        return readResponse(conn);
    }

    private JSONObject readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        BufferedReader reader;
        if (code >= 200 && code < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        if (code >= 200 && code < 300) {
            String body = sb.toString().trim();
            if (body.isEmpty()) return new JSONObject();
            return new JSONObject(body);
        } else {
            String errorBody = sb.toString();
            try {
                JSONObject errObj = new JSONObject(errorBody);
                throw new Exception(errObj.optString("error", "Server error " + code));
            } catch (org.json.JSONException je) {
                throw new Exception("Server error " + code);
            }
        }
    }

    private String parseError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) return "Connection failed";
        if (msg.contains("UnknownHost") || msg.contains("resolve")) return "Server not reachable";
        if (msg.contains("timed out") || msg.contains("Timeout")) return "Connection timed out";
        if (msg.contains("refused")) return "Connection refused";
        return msg;
    }
}
