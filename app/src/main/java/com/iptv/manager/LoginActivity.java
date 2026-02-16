package com.iptv.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

public class LoginActivity extends Activity {

    private EditText inputServerUrl;
    private EditText inputUsername;
    private EditText inputPassword;
    private TextView btnLogin;
    private TextView errorText;
    private ProgressBar loginSpinner;

    private TokenManager tokenManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        // If already logged in, skip to main
        tokenManager = new TokenManager(this);
        if (tokenManager.isLoggedIn()) {
            launchMain();
            return;
        }

        setContentView(R.layout.activity_login);
        hideSystemUI();

        inputServerUrl = findViewById(R.id.input_server_url);
        inputUsername = findViewById(R.id.input_username);
        inputPassword = findViewById(R.id.input_password);
        btnLogin = findViewById(R.id.btn_login);
        errorText = findViewById(R.id.error_text);
        loginSpinner = findViewById(R.id.login_spinner);

        // Pre-fill server URL from resources
        String defaultUrl = getString(R.string.app_url);
        inputServerUrl.setText(defaultUrl);

        // Enter key on password field triggers login
        inputPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doLogin();
                return true;
            }
            return false;
        });

        // Button click
        btnLogin.setOnClickListener(v -> doLogin());

        // D-pad enter on button
        btnLogin.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                doLogin();
                return true;
            }
            return false;
        });

        // Focus the server URL field initially
        inputServerUrl.requestFocus();
    }

    private void doLogin() {
        String baseUrl = inputServerUrl.getText().toString().trim();
        String username = inputUsername.getText().toString().trim();
        String password = inputPassword.getText().toString().trim();

        // Validation
        if (baseUrl.isEmpty()) {
            showError(getString(R.string.error_server_required));
            return;
        }
        if (username.isEmpty()) {
            showError(getString(R.string.error_username_required));
            return;
        }
        if (password.isEmpty()) {
            showError(getString(R.string.error_password_required));
            return;
        }

        // Normalise URL
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "https://" + baseUrl;
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        setLoading(true);
        errorText.setVisibility(View.GONE);

        final String finalBaseUrl = baseUrl;
        ApiClient client = ApiClient.getInstance(tokenManager);
        client.login(finalBaseUrl, username, password, new ApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                String token = response.optString("token", null);
                if (token == null || token.isEmpty()) {
                    setLoading(false);
                    showError(getString(R.string.error_invalid_response));
                    return;
                }
                tokenManager.saveToken(token);
                tokenManager.saveBaseUrl(finalBaseUrl);
                tokenManager.saveUsername(username);
                launchMain();
            }

            @Override
            public void onError(String error) {
                setLoading(false);
                showError(error);
            }
        });
    }

    private void launchMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        btnLogin.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        loginSpinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        inputServerUrl.setEnabled(!loading);
        inputUsername.setEnabled(!loading);
        inputPassword.setEnabled(!loading);
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
}
