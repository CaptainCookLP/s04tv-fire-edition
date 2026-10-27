package de.s04tv.fireedition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
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

import org.json.JSONObject;

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
    private WebView webView;
    private FrameLayout root;
    private CursorOverlay cursor;
    private boolean mouseEnabled;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private long lastBackPress;
    private String lastLoginFillUrl = "";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
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
        mouseEnabled = preferences.getBoolean("mouse_enabled", true);
        cursor = new CursorOverlay(this);
        cursor.setVisibility(mouseEnabled ? View.VISIBLE : View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
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
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                return !("https".equalsIgnoreCase(req.getUrl().getScheme()));
            }
            @Override public void onPageFinished(WebView view, String url) {
                applyViewport();
                if (isLoginPage(url) && !url.equals(lastLoginFillUrl)) {
                    lastLoginFillUrl = url;
                    fillLogin();
                } else if (!isLoginPage(url)) {
                    lastLoginFillUrl = "";
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
                cursor.bringToFront();
            }
            @Override public void onHideCustomView() { hideVideo(); }
        });
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME);
        }
        webView.requestFocus();
    }

    private boolean isLoginPage(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            return "https".equalsIgnoreCase(uri.getScheme()) &&
                    LOGIN_HOST.equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) { return false; }
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
        if (email.isEmpty() || password.isEmpty()) return;
        // No submit. Only inject on the verified S04 account origin.
        final String js = "(function(){" +
                "if(location.protocol!=='https:'||location.hostname!==" + JSONObject.quote(LOGIN_HOST) + ")return;" +
                "var e=document.querySelector('input[type=email],input[autocomplete=username],input[name=email]');" +
                "var p=document.querySelector('input[type=password]');" +
                "function put(el,v){if(!el||el.value)return;" +
                "var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
                "d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));}" +
                "put(e," + JSONObject.quote(email) + ");put(p," + JSONObject.quote(password) + ");})();";
        webView.evaluateJavascript(js, null);
    }

    private void showSettings() {
        new AlertDialog.Builder(this)
                .setTitle("S04TV Einstellungen")
                .setItems(new String[]{
                        "Darstellungsgröße",
                        mouseEnabled ? "Mauszeiger ausschalten" : "Mauszeiger einschalten",
                        "Mausgeschwindigkeit",
                        "Mauszeiger zentrieren",
                        "Zugangsdaten",
                        "Website neu laden",
                        "Anmeldesitzung löschen"
                }, (d, which) -> {
                    switch (which) {
                        case 0: chooseViewport(); break;
                        case 1: toggleMouse(); break;
                        case 2: chooseMouseSpeed(); break;
                        case 3: cursor.center(); break;
                        case 4: editCredentials(); break;
                        case 5: webView.reload(); break;
                        case 6: clearSession(); break;
                        default: break;
                    }
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    private void toggleMouse() {
        mouseEnabled = !mouseEnabled;
        preferences.edit().putBoolean("mouse_enabled", mouseEnabled).apply();
        cursor.setVisibility(mouseEnabled ? View.VISIBLE : View.GONE);
        Toast.makeText(this, mouseEnabled ? "Mauszeiger aktiviert" : "Mauszeiger deaktiviert",
                Toast.LENGTH_SHORT).show();
    }

    private void chooseMouseSpeed() {
        final String[] labels = {"Langsam", "Normal", "Schnell"};
        int speed = Math.max(0, Math.min(2, preferences.getInt("mouse_speed", 1)));
        new AlertDialog.Builder(this)
                .setTitle("Mausgeschwindigkeit")
                .setSingleChoiceItems(labels, speed, (dialog, selection) -> {
                    preferences.edit().putInt("mouse_speed", selection).apply();
                    dialog.dismiss();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /**
     * Capture D-pad before WebView so HTML/iframe cookie dialogs can be clicked
     * with physical touch events rather than cross-origin DOM injection.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                showSettings();
            return true;
        }
        if (mouseEnabled && cursor != null) {
            int dx = 0;
            int dy = 0;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
                case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        performMouseClick();
                    return true;
                default: break;
            }
            if (dx != 0 || dy != 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int speed = Math.max(0, Math.min(2, preferences.getInt("mouse_speed", 1)));
                    float baseDp = speed == 0 ? 22f : speed == 2 ? 70f : 42f;
                    float acceleration = 1f + Math.min(12, event.getRepeatCount()) * .13f;
                    cursor.move(dx * baseDp * acceleration * getResources().getDisplayMetrics().density,
                                dy * baseDp * acceleration * getResources().getDisplayMetrics().density);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void performMouseClick() {
        View target = fullscreenView != null ? fullscreenView : webView;
        if (target == null || target.getVisibility() != View.VISIBLE) return;
        final int[] cursorLocation = new int[2];
        final int[] targetLocation = new int[2];
        cursor.getLocationOnScreen(cursorLocation);
        target.getLocationOnScreen(targetLocation);
        float x = cursor.getCursorX() + cursorLocation[0] - targetLocation[0];
        float y = cursor.getCursorY() + cursorLocation[1] - targetLocation[1];
        if (x < 0 || y < 0 || x >= target.getWidth() || y >= target.getHeight()) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 90, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        target.dispatchTouchEvent(down);
        target.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static final class CursorOverlay extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private float cursorX = -1f;
        private float cursorY = -1f;

        CursorOverlay(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setFocusable(false);
            setClickable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.WHITE);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(2.7f * density);
            border.setStrokeJoin(Paint.Join.ROUND);
            border.setColor(Color.BLACK);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (cursorX < 0 || cursorY < 0) center();
            else {
                cursorX = Math.min(cursorX, Math.max(0, w - 1));
                cursorY = Math.min(cursorY, Math.max(0, h - 1));
            }
        }

        void center() {
            cursorX = getWidth() * .5f;
            cursorY = getHeight() * .5f;
            invalidate();
        }

        void move(float x, float y) {
            cursorX = Math.max(0, Math.min(getWidth() - 1f, cursorX + x));
            cursorY = Math.max(0, Math.min(getHeight() - 1f, cursorY + y));
            invalidate();
        }

        float getCursorX() { return cursorX; }
        float getCursorY() { return cursorY; }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (cursorX < 0 || cursorY < 0) return;
            float s = density;
            Path pointer = new Path();
            pointer.moveTo(cursorX, cursorY);
            pointer.lineTo(cursorX, cursorY + 35f * s);
            pointer.lineTo(cursorX + 9f * s, cursorY + 26f * s);
            pointer.lineTo(cursorX + 17f * s, cursorY + 42f * s);
            pointer.lineTo(cursorX + 24f * s, cursorY + 38f * s);
            pointer.lineTo(cursorX + 15f * s, cursorY + 23f * s);
            pointer.lineTo(cursorX + 29f * s, cursorY + 22f * s);
            pointer.close();
            canvas.drawPath(pointer, fill);
            canvas.drawPath(pointer, border);
        }
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
                        lastLoginFillUrl = "";
                        Toast.makeText(this, "Zugangsdaten gespeichert", Toast.LENGTH_SHORT).show();
                    } catch (Exception ex) {
                        Toast.makeText(this, "Speichern fehlgeschlagen", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Zugangsdaten löschen", (d, w) -> {
                    preferences.edit().remove("email").remove("password").apply();
                    lastLoginFillUrl = "";
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
                        lastLoginFillUrl = "";
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

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettings();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (fullscreenView != null) {
                hideVideo();
                return true;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastBackPress <= 2000) {
                finish();
            } else {
                lastBackPress = now;
                Toast.makeText(this, "Erneut Zurück drücken zum Beenden", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
    }
    @Override protected void onDestroy() {
        hideVideo();
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
