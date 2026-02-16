package com.iptv.manager;

import android.content.Context;
import android.content.SharedPreferences;

public class TokenManager {

    private static final String PREFS_NAME = "iptv_auth";
    private static final String KEY_TOKEN = "jwt_token";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_USERNAME = "username";

    private final SharedPreferences prefs;

    public TokenManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, null);
    }

    public void saveBaseUrl(String url) {
        prefs.edit().putString(KEY_BASE_URL, url).apply();
    }

    public String getBaseUrl() {
        return prefs.getString(KEY_BASE_URL, null);
    }

    public void saveUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public boolean isLoggedIn() {
        return getToken() != null && getBaseUrl() != null;
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
