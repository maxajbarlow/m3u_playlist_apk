package com.iptv.manager;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks for app updates on startup and installs new APK if available.
 * Fetches version info from /api/app-version, compares with current versionCode,
 * and downloads + installs the APK if a newer version exists.
 */
public class AppUpdater {

    private static final String TAG = "AppUpdater";
    private final Activity activity;
    private final String baseUrl;

    public AppUpdater(Activity activity, String baseUrl) {
        this.activity = activity;
        // Strip trailing slash
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Check for updates in background. Shows toast and triggers install if update found.
     */
    public void checkForUpdate() {
        new Thread(() -> {
            try {
                int currentVersion = getCurrentVersionCode();
                Log.d(TAG, "Current versionCode: " + currentVersion);

                // Fetch latest version info from server
                String versionUrl = baseUrl + "/api/app-version";
                Log.d(TAG, "Checking: " + versionUrl);
                HttpURLConnection conn = (HttpURLConnection) new URL(versionUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/json");

                if (conn.getResponseCode() != 200) {
                    Log.w(TAG, "Version check failed: HTTP " + conn.getResponseCode());
                    conn.disconnect();
                    return;
                }

                InputStream is = conn.getInputStream();
                byte[] buf = new byte[1024];
                StringBuilder sb = new StringBuilder();
                int len;
                while ((len = is.read(buf)) != -1) {
                    sb.append(new String(buf, 0, len));
                }
                is.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                int latestVersion = json.getInt("versionCode");
                String latestName = json.optString("versionName", "");
                Log.d(TAG, "Latest versionCode: " + latestVersion + " (" + latestName + ")");

                if (latestVersion <= currentVersion) {
                    Log.d(TAG, "App is up to date");
                    return;
                }

                // Update available — notify user and download
                activity.runOnUiThread(() ->
                        Toast.makeText(activity,
                                "Updating app to v" + latestName + "...",
                                Toast.LENGTH_LONG).show());

                downloadAndInstall();

            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
            }
        }).start();
    }

    private void downloadAndInstall() {
        try {
            String apkUrl = baseUrl + "/static/tv.apk";
            Log.d(TAG, "Downloading APK: " + apkUrl);

            HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            if (conn.getResponseCode() != 200) {
                Log.e(TAG, "APK download failed: HTTP " + conn.getResponseCode());
                conn.disconnect();
                showError("Update download failed");
                return;
            }

            // Save to cache dir
            File updateDir = new File(activity.getCacheDir(), "updates");
            if (!updateDir.exists()) updateDir.mkdirs();
            File apkFile = new File(updateDir, "update.apk");

            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(apkFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.close();
            is.close();
            conn.disconnect();

            Log.d(TAG, "APK downloaded: " + apkFile.length() + " bytes");

            // Trigger install
            activity.runOnUiThread(() -> installApk(apkFile));

        } catch (Exception e) {
            Log.e(TAG, "APK download failed", e);
            showError("Update download failed");
        }
    }

    private void installApk(File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+: use FileProvider
                apkUri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            showError("Failed to install update");
        }
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo pInfo = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private void showError(String msg) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }
}
