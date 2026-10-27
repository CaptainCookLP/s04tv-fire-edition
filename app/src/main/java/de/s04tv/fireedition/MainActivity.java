package de.s04tv.fireedition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;


import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

public final class MainActivity extends Activity {
    private static final String HOME = "https://tv.schalke04.de/";
    private static final String LOGIN_HOST = "account.schalke04.de";
    private static final String PREFS = "s04tv_settings";
    private static final String KEY_ALIAS = "s04tv_login_key";
    private static final String[] SIZE_LABELS = {
            "Automatisch", "720p (1280 CSS-Pixel)", "1080p (1920 CSS-Pixel)",
            "4K (3840 CSS-Pixel)"
    };
    private static final int[] SIZE_WIDTHS = {0, 1280, 1920, 3840};
    private SharedPreferences preferences;
    private UpdateManager updateManager;
    private WebView webView;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private long lastBackPress;
    private final Handler loginPromptHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingLoginCheck;
    private boolean loginPromptShown;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        updateManager = new UpdateManager(this, preferences);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                cancelLoginCheck();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                return !("https".equalsIgnoreCase(req.getUrl().getScheme()));
            }
            @Override public void onPageFinished(WebView view, String url) {
                applyViewport();
                if (isLoginPage(url)) {
                    // Presentation is scoped only to account.schalke04.de.
                    LoginTvOverlay.apply(view);
                    fillLogin();
                } else {
                    LoginAutoFill.stop(view);
                    if (isTvPage(url)) {
                        scheduleLoginCheck();
                        if (isTvCatalog(url)) TvFocusNavigation.install(view);
                        else if (isTvVideo(url)) VideoTvMode.present(view);
                    }
                }
                CookieManager.getInstance().flush();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                webView.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }
            @Override public void onHideCustomView() { hideVideo(); }
        });
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME);
        }
        webView.requestFocus();
        updateManager.checkForUpdates(false);
    }

    private boolean isLoginPage(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                    !LOGIN_HOST.equalsIgnoreCase(uri.getHost())) return false;
            String path = uri.getPath();
            return path != null && (path.equals("/") ||
                    path.equalsIgnoreCase("/login") ||
                    path.equalsIgnoreCase("/login/") ||
                    path.equalsIgnoreCase("/s/login") ||
                    path.equalsIgnoreCase("/s/login/"));
        } catch (Exception ignored) { return false; }
    }

    private boolean isTvPage(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            return "https".equalsIgnoreCase(uri.getScheme()) &&
                    "tv.schalke04.de".equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) { return false; }
    }

    private boolean isTvCatalog(String url) {
        return isTvPage(url) && !isTvVideo(url);
    }

    private boolean isTvVideo(String url) {
        if (!isTvPage(url)) return false;
        try {
            String path = android.net.Uri.parse(url).getPath();
            return path != null && path.startsWith("/videos/");
        } catch (Exception ignored) { return false; }
    }

    private void cancelLoginCheck() {
        if (pendingLoginCheck != null) loginPromptHandler.removeCallbacks(pendingLoginCheck);
        pendingLoginCheck = null;
    }

    private void scheduleLoginCheck() {
        if (loginPromptShown) return;
        cancelLoginCheck();
        pendingLoginCheck = () -> {
            pendingLoginCheck = null;
            if (loginPromptShown || fullscreenView != null || !isTvPage(webView.getUrl())) return;
            // Read the existing link states. Do not alter S04TV page DOM.
            String js = "(function(){" +
                    "if(location.protocol!=='https:'||location.hostname!=='tv.schalke04.de')return 'unknown';" +
                    "var links=Array.prototype.slice.call(document.querySelectorAll('a[href]'));" +
                    "var hasAbo=links.some(function(a){return /\\/subscription(?:[/?#]|$)/.test(a.href)" +
                    "||/mein abo/i.test((a.textContent||'').trim());});" +
                    "var hasLogout=!!document.querySelector('button[aria-label=\\\"Logout\\\"]');" +
                    "var hasLogin=links.some(function(a){return /(^|\\s)login(\\s|$)/i.test((a.textContent||'').trim())" +
                    "||/account\\.schalke04\\.de\\/s\\/login/.test(a.href);});" +
                    "return hasLogin&&!hasAbo&&!hasLogout?'logged_out':'unknown';})();";
            webView.evaluateJavascript(js, result -> {
                if (!"\"logged_out\"".equals(result) || loginPromptShown ||
                        isFinishing() || isDestroyed() || !isTvPage(webView.getUrl())) return;
                loginPromptShown = true;
                new AlertDialog.Builder(this)
                        .setTitle("Bei S04TV+ anmelden")
                        .setMessage("Du bist derzeit nicht angemeldet. Melde dich mit deinem Schalke-Konto an, um die Inhalte deines Abos anzusehen.")
                        .setPositiveButton("Jetzt anmelden", (d, which) -> {
                            // Click the site's own login link. No custom auth flow.
                            webView.evaluateJavascript("(function(){var a=Array.prototype.slice.call(document.querySelectorAll('a[href]')).find(function(a){return /(^|\\s)login(\\s|$)/i.test((a.textContent||'').trim())||/account\\.schalke04\\.de\\/s\\/login/.test(a.href);});if(a)a.click();})();", null);
                        })
                        .setNegativeButton("Später", null)
                        .show();
            });
        };
        loginPromptHandler.postDelayed(pendingLoginCheck, 2000);
    }

    private void applyViewport() {
        int index = preferences.getInt("viewport", 0);
        if (index < 0 || index >= SIZE_WIDTHS.length) index = 0;
        final int width = SIZE_WIDTHS[index];
        if (width == 0) return;
        // Virtual CSS viewport only; does not change HDMI resolution or video quality.
        String js = "(function(){var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                "m.setAttribute('content','width=" + width + ",initial-scale=1');})();";
        webView.evaluateJavascript(js, null);
    }

    private void fillLogin() {
        String email;
        String password;
        try {
            email = decrypt(preferences.getString("email", ""));
            password = decrypt(preferences.getString("password", ""));
        } catch (Exception ignored) { return; }
        // Only the HTTPS S04 login origin; no automatic form submission.
        LoginAutoFill.fill(webView, email, password);
    }

    private void showSettings() {
        boolean focusOn = preferences.getBoolean("tv_focus", true);
        new AlertDialog.Builder(this)
                .setTitle("S04TV Einstellungen")
                .setItems(new String[]{
                        "Darstellungsgröße",
                        focusOn ? "TV-Fokusnavigation ausschalten" : "TV-Fokusnavigation einschalten",
                        "Zugangsdaten",
                        "Website neu laden",
                        "Anmeldesitzung löschen",
                        "Updates prüfen / installieren"
                }, (d, which) -> {
                    switch (which) {
                        case 0: chooseViewport(); break;
                        case 1:
                            boolean enabled = !preferences.getBoolean("tv_focus", true);
                            preferences.edit().putBoolean("tv_focus", enabled).apply();
                            if (enabled && isTvCatalog(webView.getUrl()))
                                TvFocusNavigation.install(webView);
                            Toast.makeText(this,
                                    enabled ? "TV-Fokusnavigation aktiviert" :
                                            "Website-Tastensteuerung aktiviert",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 2: editCredentials(); break;
                        case 3: webView.reload(); break;
                        case 4: clearSession(); break;
                        case 5: updateManager.checkForUpdates(true); break;
                        default: break;
                    }
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    /**
     * On catalog pages use spatial D-pad navigation instead of a cursor.
     * The embedded 3Q player and native login forms keep their own key handling.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        // Fire TV/Android WebView can consume Back before Activity.onKeyDown.
        // Handle it first, including both native fullscreen and web history.
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE ||
                key == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                handleBack();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                showSettings();
            return true;
        }
        if (isTvCatalog(webView.getUrl()) && preferences.getBoolean("tv_focus", true) &&
                fullscreenView == null) {
            String direction = null;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT: direction = "left"; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: direction = "right"; break;
                case KeyEvent.KEYCODE_DPAD_UP: direction = "up"; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: direction = "down"; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        TvFocusNavigation.activate(webView);
                    return true;
                default: break;
            }
            if (direction != null) {
                if (event.getAction() == KeyEvent.ACTION_DOWN)
                    TvFocusNavigation.move(webView, direction);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void chooseViewport() {
        int selected = preferences.getInt("viewport", 0);
        if (selected < 0 || selected >= SIZE_LABELS.length) selected = 0;
        new AlertDialog.Builder(this)
                .setTitle("Virtuelle Web-Seitenbreite (nicht HDMI)")
                .setSingleChoiceItems(SIZE_LABELS, selected, (d, which) -> {
                    preferences.edit().putInt("viewport", which).apply();
                    d.dismiss();
                    // Reload to remove any viewport override when returning to automatic.
                    webView.reload();
                }).setNegativeButton("Abbrechen", null).show();
    }

    private void editCredentials() {
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(32, 16, 32, 8);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText email = new EditText(this);
        email.setSingleLine(true);
        email.setHint("S04-Account E-Mail");
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setHint("Passwort");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        try { email.setText(decrypt(preferences.getString("email", ""))); }
        catch (Exception ignored) { }
        layout.addView(email);
        layout.addView(password);
        new AlertDialog.Builder(this).setTitle("Zugangsdaten lokal speichern")
                .setMessage("Optional. Mit Android Keystore verschlüsselt. Nur auf der S04-Anmeldeseite in leere Felder eintragen; niemals automatisch absenden.")
                .setView(layout)
                .setPositiveButton("Speichern", (d, w) -> {
                    try {
                        String user = email.getText().toString().trim();
                        String pass = password.getText().toString();
                        if (pass.isEmpty()) {
                            // An empty password means keep the existing encrypted password.
                            preferences.edit().putString("email", encrypt(user)).apply();
                        } else {
                            preferences.edit().putString("email", encrypt(user))
                                    .putString("password", encrypt(pass)).apply();
                        }
                        if (isLoginPage(webView.getUrl())) fillLogin();
                        Toast.makeText(this, "Zugangsdaten gespeichert", Toast.LENGTH_SHORT).show();
                    } catch (Exception ex) {
                        Toast.makeText(this, "Speichern fehlgeschlagen", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Zugangsdaten löschen", (d, w) -> {
                    preferences.edit().remove("email").remove("password").apply();
                    LoginAutoFill.stop(webView);
                    Toast.makeText(this, "Zugangsdaten gelöscht", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Abbrechen", null).show();
    }

    private void clearSession() {
        new AlertDialog.Builder(this).setTitle("Sitzung löschen?")
                .setMessage("Cookies, Webspeicher und Cache löschen. Gespeicherte Zugangsdaten bleiben erhalten.")
                .setPositiveButton("Löschen", (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(value -> {
                        CookieManager.getInstance().flush();
                        webView.clearCache(true);
                        webView.clearHistory();
                        android.webkit.WebStorage.getInstance().deleteAllData();
                        LoginAutoFill.stop(webView);
                        webView.loadUrl(HOME);
                    });
                }).setNegativeButton("Abbrechen", null).show();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private String encrypt(String input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.split(":", 2);
        if (parts.length != 2) return "";
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private void hideVideo() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        webView.setVisibility(View.VISIBLE);
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    private void handleBack() {
        if (fullscreenView != null) {
            hideVideo();
            return;
        }
        if (webView != null) {
            if (isTvVideo(webView.getUrl())) {
                // The webview history can contain redirects/player frames;
                // return directly to the nearest S04TV catalog, if available.
                android.webkit.WebBackForwardList history = webView.copyBackForwardList();
                int index = history.getCurrentIndex();
                for (int previous = index - 1; previous >= 0; previous--) {
                    android.webkit.WebHistoryItem item = history.getItemAtIndex(previous);
                    if (item != null && isTvCatalog(item.getUrl())) {
                        webView.goBackOrForward(previous - index);
                        return;
                    }
                }
                webView.loadUrl(HOME);
                return;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPress <= 2000) finish();
        else {
            lastBackPress = now;
            Toast.makeText(this, "Erneut Zurück drücken zum Beenden", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        handleBack();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        webView.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        if (webView != null) webView.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (updateManager != null) updateManager.onResume();
    }
    @Override protected void onDestroy() {
        cancelLoginCheck();
        LoginAutoFill.stop(webView);
        if (updateManager != null) updateManager.destroy();
        hideVideo();
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
