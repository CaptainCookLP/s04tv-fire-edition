package de.s04tv.fireedition;

import android.app.Activity;
import android.os.Build;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
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
    private String visitedUrl = HOME;
    private TvPlayerControls playerControls;
    private PersistentAssetCache assetCache;
    private StartupSplash startupSplash;
    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private Runnable startupTimeout;
    private Bundle restoredWebState;
    private boolean websiteStarted;
    private boolean websiteReady;
    private boolean cacheReviewed;
    private boolean updateChecked;
    private boolean updateRequired;
    private boolean leavingVideo;
    private String lastCatalogUrl = HOME;
    private long lastVideoBackTime;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        updateManager = new UpdateManager(this, preferences);
        assetCache = new PersistentAssetCache(this);
        restoredWebState = savedInstanceState;
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
        playerControls = new TvPlayerControls(this, root, this::handlePlayerAction);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // WebView revalidates HTTP responses normally. Its disk cache and
        // DOM storage survive restarts; eligible public images also use filesDir.
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
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
                if (startupSplash != null)
                    startupSplash.status("Lade Website und aktualisiere den Cache …");
            }
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                // This callback is off the UI thread. Only cache public, unsigned images.
                return assetCache.intercept(request);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                return !("https".equalsIgnoreCase(req.getUrl().getScheme()));
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                visitedUrl = url;
                if (isTvCatalog(url)) lastCatalogUrl = url;
                if (isTvPage(url)) {
                    // The catalog may use SPA routing: attach both controllers on URL changes.
                    ThreeQPlayerController.install(view);
                    VideoTvMode.present(view);
                    if (isTvCatalog(url)) {
                        playerControls.hide();
                        TvFocusNavigation.install(view);
                    }
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                visitedUrl = url;
                if (isTvCatalog(url)) lastCatalogUrl = url;
                if (!isTvVideo(url)) playerControls.hide();
                applyViewport();
                if (isLoginPage(url)) {
                    // Presentation is scoped only to account.schalke04.de.
                    LoginTvOverlay.apply(view);
                    fillLogin();
                } else {
                    LoginAutoFill.stop(view);
                    if (isTvPage(url)) {
                        scheduleLoginCheck();
                        // Install on both catalog and detail pages; onPageFinished is
                        // not fired on all SPA transitions.
                        VideoTvMode.present(view);
                        ThreeQPlayerController.install(view);
                        if (isTvCatalog(url)) TvFocusNavigation.install(view);
                    }
                }
                CookieManager.getInstance().flush();
                if (websiteStarted && startupSplash != null) {
                    // onPageFinished does not mean every image is done loading.
                    startupHandler.postDelayed(thisActivityReady(), 850);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (startupSplash != null && websiteStarted) startupSplash.progress(progress);
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                webView.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                view.setFocusable(true);
                view.requestFocus();
                if (playerControls.isShowing()) playerControls.hide();
            }
            @Override public void onHideCustomView() { hideVideo(); }
        });
        // Load and revalidate the website/images BEHIND the splash immediately.
        // Update lookup runs independently and never blocks cache warming.
        startupSplash = new StartupSplash(this);
        root.addView(startupSplash, new FrameLayout.LayoutParams(-1, -1));
        startupSplash.bringToFront();
        startupSplash.requestFocus();

        new Thread(() -> {
            int[] count = assetCache.prepare();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                cacheReviewed = true;
                if (startupSplash != null) startupSplash.status(
                        "Cache geprüft: " + count[0] + " Bilder bereit · Lade aktuelle Inhalte …");
                maybeFinishStartup();
            });
        }, "s04tv-cache-prepare").start();

        startWebsite("Lade Website und aktualisiere den Cache …");
        updateManager.checkAtStartup(new UpdateManager.StartupListener() {
            @Override public void onStatus(String text) {
                if (startupSplash != null) startupSplash.updateStatus(text);
            }
            @Override public void onChecked(boolean available) {
                updateChecked = true;
                updateRequired = available;
                if (available && startupSplash == null) {
                    // The offline watchdog might have revealed the website.
                    // A late positive update result must re-block it immediately.
                    startupSplash = new StartupSplash(MainActivity.this);
                    root.addView(startupSplash, new FrameLayout.LayoutParams(-1, -1));
                    startupSplash.bringToFront();
                    startupSplash.requestFocus();
                }
                if (startupSplash != null) startupSplash.updateStatus(
                        available ? "Update erforderlich · Installation folgt" :
                                "Updateprüfung abgeschlossen");
                maybeFinishStartup();
            }
        });
        // Never leave the app stuck when the site has stalled. The separate
        // update check still gates the website until resolved.
        startupTimeout = () -> {
            websiteReady = true;
            cacheReviewed = true;
            maybeFinishStartup();
        };
        startupHandler.postDelayed(startupTimeout, 25000);
        // Network has explicit connect/read timeouts; handle an unexpected
        // stalled worker as offline and still gate later positive results.
        startupHandler.postDelayed(() -> {
            if (!updateChecked && startupSplash != null) {
                updateChecked = true;
                startupSplash.updateStatus("Updateprüfung nicht erreichbar (Offline-Modus)");
                maybeFinishStartup();
            }
        }, 16000);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private Runnable thisActivityReady() {
        return () -> {
            if (!websiteStarted || isFinishing() || isDestroyed()) return;
            websiteReady = true;
            maybeFinishStartup();
        };
    }

    private void maybeFinishStartup() {
        if (startupSplash == null || !websiteReady || !cacheReviewed || !updateChecked)
            return;
        if (updateRequired) {
            startupSplash.status("Cache bereit · Neue Version erforderlich");
            startupSplash.updateStatus("Bitte das Update jetzt installieren");
            updateManager.showRequiredUpdateDialog();
            return;
        }
        startupSplash.dismiss();
        startupSplash = null;
        startupHandler.removeCallbacks(startupTimeout);
        if (webView != null && fullscreenView == null) webView.requestFocus();
    }

    private void startWebsite(String message) {
        if (websiteStarted || isFinishing() || isDestroyed()) return;
        websiteStarted = true;
        if (startupSplash != null) startupSplash.status(message);
        // WebView must remain VISIBLE to render/load images under the overlay.
        webView.setVisibility(View.VISIBLE);
        if (restoredWebState != null) {
            if (webView.restoreState(restoredWebState) == null) webView.loadUrl(HOME);
            restoredWebState = null;
        } else webView.loadUrl(HOME);
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
                        "Updates prüfen / installieren",
                        "Player-Steuerung / Diagnose",
                        "Gespeicherten Bildercache leeren"
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
                        case 6: showPlayerControls(); break;
                        case 7:
                            new Thread(() -> {
                                assetCache.clear();
                                runOnUiThread(() -> Toast.makeText(this,
                                        "Bildercache geleert", Toast.LENGTH_SHORT).show());
                            }, "s04tv-image-cache-clear").start();
                            break;
                        default: break;
                    }
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    private void showPlayerControls() {
        if (!isTvVideo(visitedUrl)) {
            Toast.makeText(this, "Playersteuerung nur auf Videoseiten verfügbar", Toast.LENGTH_SHORT).show();
            return;
        }
        playerControls.show();
    }

    private void handlePlayerAction(String action) {
        if ("close".equals(action)) {
            playerControls.hide();
            webView.requestFocus();
            return;
        }
        if ("diagnose".equals(action)) {
            playerControls.hide();
            showPlayerDiagnostics();
            return;
        }
        if ("fullscreen".equals(action)) {
            playerControls.hide();
        }
        playerCommand(action);
    }

    private void playerCommand(String action) {
        ThreeQPlayerController.command(webView, action, result -> {
            if (isFinishing() || isDestroyed() || !isTvVideo(visitedUrl)) return;
            if (!result.optBoolean("ok")) {
                String reason = result.optString("reason", "unbekannt");
                Toast.makeText(this, "3Q-Player: " + reason, Toast.LENGTH_SHORT).show();
            }
            if ("fullscreen".equals(action)) {
                // CSS TV mode is deliberately separate from HTML fullscreen.
                // The controller's request status does not imply fullscreen succeeded.
                VideoTvMode.present(webView);
            }
        });
    }

    private void showPlayerDiagnostics() {
        ThreeQPlayerController.diagnostics(webView, details -> {
            if (isFinishing() || isDestroyed()) return;
            String text = details.toString() +
                    "\\nAndroid API: " + android.os.Build.VERSION.SDK_INT +
                    "\\nApp: " + getPackageName();
            new AlertDialog.Builder(this)
                    .setTitle("3Q Player – Diagnose")
                    .setMessage(text)
                    .setPositiveButton("Schließen", null)
                    .show();
        });
    }

    /**
     * On catalog pages use spatial D-pad navigation instead of a cursor.
     * The embedded 3Q player and native login forms keep their own key handling.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (startupSplash != null) {
            // Never allow D-pad/OK to reach preloaded content during the update gate.
            if ((key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE ||
                    key == KeyEvent.KEYCODE_BUTTON_B) &&
                    event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                finish();
            return true;
        }
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
        if (playerControls != null && playerControls.isShowing()) {
            // Delegate D-pad movement and OK to Android's real focused buttons.
            return super.dispatchKeyEvent(event);
        }
        if (isTvVideo(visitedUrl)) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        playerCommand("toggle");
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        showPlayerControls();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        playerCommand("back10");
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        playerCommand("next10");
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        showPlayerControls();
                    return true;
                default: break;
            }
        }
        if (isTvCatalog(visitedUrl) && preferences.getBoolean("tv_focus", true) &&
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
        if (isTvVideo(visitedUrl) && !leavingVideo) VideoTvMode.present(webView);
        webView.requestFocus();
    }

    private void handleBack() {
        if (startupSplash != null) {
            finish();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastVideoBackTime < 450) return; // avoid duplicate API33 + key callbacks
        if (isTvVideo(visitedUrl) || fullscreenView != null) {
            lastVideoBackTime = now;
            leavingVideo = true;
            if (playerControls != null) playerControls.hide();
            // Exit video in ONE Back action, even from Android WebChrome fullscreen.
            // Retain the catalog URL for HTML5 history / SPA navigations.
            if (fullscreenView != null) hideVideo();
            ThreeQPlayerController.command(webView, "pause", null);
            ThreeQPlayerController.command(webView, "exitfullscreen", null);
            VideoTvMode.exit(webView);
            android.webkit.WebBackForwardList history = webView.copyBackForwardList();
            int index = history.getCurrentIndex();
            boolean navigated = false;
            for (int previous = index - 1; previous >= 0; previous--) {
                android.webkit.WebHistoryItem item = history.getItemAtIndex(previous);
                if (item != null && isTvCatalog(item.getUrl())) {
                    webView.goBackOrForward(previous - index);
                    navigated = true;
                    break;
                }
            }
            if (!navigated) webView.loadUrl(isTvCatalog(lastCatalogUrl) ? lastCatalogUrl : HOME);
            visitedUrl = isTvCatalog(lastCatalogUrl) ? lastCatalogUrl : HOME;
            webView.requestFocus();
            leavingVideo = false;
            return;
        }
        if (playerControls != null && playerControls.isShowing()) {
            playerControls.hide();
            webView.requestFocus();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (now - lastBackPress <= 2000) finish();
        else {
            lastBackPress = now;
            Toast.makeText(this, "Erneut Zurück drücken zum Beenden",
                    Toast.LENGTH_SHORT).show();
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
        startupHandler.removeCallbacksAndMessages(null);
        if (startupSplash != null) {
            startupSplash.dismiss();
            startupSplash = null;
        }
        LoginAutoFill.stop(webView);
        if (updateManager != null) updateManager.destroy();
        if (playerControls != null) playerControls.hide();
        hideVideo();
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
