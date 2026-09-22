package com.babasitaram.pro;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private String pendingPickerCallback;
    private String pendingBiometricCallback;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) readFileAndSendToJs(uri);
                } else {
                    if (pendingPickerCallback != null) {
                        webView.evaluateJavascript(pendingPickerCallback + "(null, null);", null);
                        pendingPickerCallback = null;
                    }
                }
            }
        );

        webView.loadUrl("file:///android_asset/index.html");
    }

    // ── Biometric Prompt ──
    private void showBiometricPrompt(String callbackFn) {
        pendingBiometricCallback = callbackFn;

        BiometricManager bm = BiometricManager.from(this);
        int canAuth = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG |
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        );

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Device lock not set up
            sendBiometricResult(false, "Device lock not set up");
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt.AuthenticationCallback callback = new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                sendBiometricResult(true, "ok");
            }
            @Override
            public void onAuthenticationFailed() {
                // User tried but failed — don't close prompt, let them retry
            }
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                sendBiometricResult(false, errString.toString());
            }
        };

        BiometricPrompt prompt = new BiometricPrompt(this, executor, callback);

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("BabaSitaRam Pro")
            .setSubtitle("Vault unlock karne ke liye verify karein")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build();

        prompt.authenticate(info);
    }

    private void sendBiometricResult(boolean success, String msg) {
        if (pendingBiometricCallback == null) return;
        String escaped = msg.replace("'", "\\'");
        String js = pendingBiometricCallback + "(" + success + ", '" + escaped + "');";
        webView.post(() -> webView.evaluateJavascript(js, null));
        pendingBiometricCallback = null;
    }

    // ── File Read ──
    private void readFileAndSendToJs(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) throw new IOException("Cannot open file");

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) buffer.write(chunk, 0, n);
            is.close();

            byte[] bytes = buffer.toByteArray();
            String contentB64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            String fileName = getFileName(uri);
            String safeB64 = JSONObject.quote(contentB64);
            String safeName = JSONObject.quote(fileName);

            String js = pendingPickerCallback + "(" + safeB64 + ", " + safeName + ");";
            webView.post(() -> webView.evaluateJavascript(js, null));
        } catch (Exception e) {
            if (pendingPickerCallback != null)
                webView.post(() -> webView.evaluateJavascript(pendingPickerCallback + "(null, null);", null));
            runOnUiThread(() -> Toast.makeText(this,
                    "❌ File read failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } finally {
            pendingPickerCallback = null;
        }
    }

    private String getFileName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        String name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "backup.vaultbak";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    // ── JS Bridge ──
    private class JsBridge {

        @JavascriptInterface
        public void saveFile(String content, String filename, String mimeType) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IOException("MediaStore insert failed");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new IOException("Cannot open output stream");
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, filename);
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "✅ Saved: " + filename, Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "❌ Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void syncVault(String passwordsJson) {
            try {
                getSharedPreferences("WebViewAppPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("vx3_passwords", passwordsJson != null ? passwordsJson : "[]")
                        .apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openFilePicker(String callbackFn) {
            pendingPickerCallback = callbackFn;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/json", "text/csv", "application/zip", "*/*"});
            filePickerLauncher.launch(Intent.createChooser(intent, "Backup file select karein"));
        }

        @JavascriptInterface
        public void showBiometric(String callbackFn) {
            runOnUiThread(() -> showBiometricPrompt(callbackFn));
        }

        @JavascriptInterface
        public boolean isBiometricAvailable() {
            BiometricManager bm = BiometricManager.from(MainActivity.this);
            int result = bm.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            );
            return result == BiometricManager.BIOMETRIC_SUCCESS;
        }

        @JavascriptInterface
        public boolean isAndroid() { return true; }

        @JavascriptInterface
        public int getSdkVersion() { return Build.VERSION.SDK_INT; }
    }
}
