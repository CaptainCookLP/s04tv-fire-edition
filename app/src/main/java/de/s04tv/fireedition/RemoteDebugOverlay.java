package de.s04tv.fireedition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Optional on-screen Fire TV remote debugger.
 *
 * A token-bound TYPE_APPLICATION_PANEL keeps the HUD above WebView/video
 * content without requiring SYSTEM_ALERT_WINDOW. The HUD deliberately never
 * takes focus or touch events. If the device rejects the child window, we
 * fall back to a high-elevation Activity content overlay.
 *
 * Only allowlisted remote keys and native action names are recorded.
 * No account details, user-entered text, URL, tokens, cookies or streams.
 */
final class RemoteDebugOverlay {
    private static final String PREF_ENABLED = "remote_debug_enabled";
    private static final String LOG_TAG = "S04TVRemote";
    private static final int MAX_RECORDS = 180;
    private static final int HUD_RECORDS = 5;

    private final Activity activity;
    private final FrameLayout root;
    private final WindowManager windowManager;
    private final SharedPreferences preferences;
    private final TextView hud;
    private final ArrayDeque<String> records = new ArrayDeque<>();
    private final SimpleDateFormat timestamp =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY);
    private boolean enabled;
    private boolean windowAttached;
    private boolean fallbackAttached;
    private String lastWindowError = "none";

    RemoteDebugOverlay(Activity activity, FrameLayout root, SharedPreferences preferences) {
        this.activity = activity;
        this.root = root;
        this.preferences = preferences;
        this.windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        this.enabled = preferences.getBoolean(PREF_ENABLED, false);

        hud = new TextView(activity);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(17);
        hud.setPadding(dp(16), dp(12), dp(16), dp(12));
        hud.setMaxWidth(dp(850));
        hud.setFocusable(false);
        hud.setFocusableInTouchMode(false);
        hud.setClickable(false);
        hud.setLongClickable(false);
        hud.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // If a device rejects the dedicated window, still render above siblings.
        hud.setElevation(dp(80));
        hud.setTranslationZ(dp(12));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(240, 2, 17, 40));
        background.setCornerRadius(dp(10));
        background.setStroke(dp(3), Color.rgb(0, 188, 255));
        hud.setBackground(background);

        if (enabled) recordState("Debug-Modus gestartet");
    }

    boolean isEnabled() { return enabled; }

    void setEnabled(boolean value) {
        if (enabled == value) {
            if (value) bringToFront();
            return;
        }
        enabled = value;
        preferences.edit().putBoolean(PREF_ENABLED, value).apply();
        if (value) {
            records.clear();
            recordState("DEBUG EIN – Sichtbarkeitstest bereit");
        } else {
            detach();
        }
    }

    /** Called after the Activity's decor window has a valid token. */
    void onResume() {
        if (enabled) {
            activity.getWindow().getDecorView().post(this::bringToFront);
        }
    }

    void onDestroy() {
        enabled = false;
        detach();
    }

    void bringToFront() {
        if (!enabled) return;
        ensureAttached();
        if (fallbackAttached) hud.bringToFront();
    }

    /**
     * WindowManager owns the HUD instead of root's child drawing order.
     * This preserves visibility during WebChromeClient fullscreen and when
     * the browser/video inserts views later.
     */
    private void ensureAttached() {
        if (windowAttached || fallbackAttached || !enabled) return;

        View decor = activity.getWindow().getDecorView();
        android.os.IBinder token = decor.getWindowToken();
        if (token == null) {
            lastWindowError = "window-token-not-ready";
            // onResume() will retry after the Activity becomes visible.
            return;
        }

        if (windowManager != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.token = token;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(22);
            lp.y = dp(36);
            lp.setTitle("S04TV Fernbedienung Debug");
            try {
                windowManager.addView(hud, lp);
                windowAttached = true;
                lastWindowError = "none";
                Log.i(LOG_TAG, "HUD attached as application panel");
                return;
            } catch (RuntimeException e) {
                // Never prevent the app from starting just because a Fire OS
                // variant rejects an additional app-owned panel.
                lastWindowError = e.getClass().getSimpleName();
                Log.w(LOG_TAG, "HUD panel rejected; falling back to Activity view", e);
            }
        } else {
            lastWindowError = "window-manager-null";
        }

        try {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            params.setMargins(dp(22), dp(36), dp(12), 0);
            root.addView(hud, params);
            fallbackAttached = true;
            hud.bringToFront();
        } catch (RuntimeException e) {
            lastWindowError += "/fallback-" + e.getClass().getSimpleName();
            Log.e(LOG_TAG, "HUD fallback failed", e);
        }
    }

    private void detach() {
        if (windowAttached) {
            try { windowManager.removeViewImmediate(hud); }
            catch (RuntimeException e) {
                Log.w(LOG_TAG, "HUD removal failed", e);
            }
            windowAttached = false;
        }
        if (fallbackAttached) {
            root.removeView(hud);
            fallbackAttached = false;
        }
    }

    void observeDialog(AlertDialog dialog, String context) {
        if (dialog == null) return;
        dialog.setOnKeyListener((d, keyCode, event) -> {
            recordKey(event, context, false);
            return false;
        });
    }

    void recordKey(KeyEvent event, String route, boolean handledByActivity) {
        if (!enabled || event == null || !isRemoteKey(event.getKeyCode())) return;
        if (event.getAction() != KeyEvent.ACTION_DOWN &&
                event.getAction() != KeyEvent.ACTION_UP) return;
        int repeat = event.getRepeatCount();
        if (repeat > 1 && repeat % 5 != 0) return;

        String key = KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "");
        String action = event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP";
        String suffix = repeat > 0 ? " x" + repeat : "";
        add(key + " " + action + suffix + " | " + route +
                " | " + (handledByActivity ? "App" : "Weitergereicht"));
    }

    void recordState(String state) {
        if (enabled) add("APP | " + state);
    }

    void test() {
        if (!enabled) {
            setEnabled(true);
        }
        recordState("SICHTBARKEITSTEST: HUD SOLLTE OBEN LINKS STEHEN");
        bringToFront();
        String status = windowAttached ? "Eigenes HUD-Fenster aktiv" :
                fallbackAttached ? "Fallback im App-Fenster aktiv" :
                "HUD konnte nicht eingeblendet werden: " + lastWindowError;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Debug-Anzeige testen")
                .setMessage(status + "\n\nSchließe dieses Fenster und drücke Links, Rechts und OK. " +
                        "Die Tasten müssen anschließend im HUD oben links und im Protokoll erscheinen.\n\n" +
                        "Ist das HUD unsichtbar, sende mir bitte den Status und das Protokoll.")
                .setPositiveButton("OK", null)
                .show();
        observeDialog(dialog, "HUD-Test");
    }

    private void add(String message) {
        String entry = timestamp.format(new Date()) + "  " + message;
        records.addLast(entry);
        while (records.size() > MAX_RECORDS) records.removeFirst();
        Log.i(LOG_TAG, entry);
        refresh();
    }

    private void refresh() {
        if (!enabled) return;
        StringBuilder b = new StringBuilder(
                "S04TV  •  FERNBEDIENUNG LIVE  •  DEBUG AN\n");
        int skip = Math.max(0, records.size() - HUD_RECORDS);
        int i = 0;
        for (String record : records) {
            if (i++ >= skip) b.append(record).append('\n');
        }
        hud.setText(b.toString().trim());
        ensureAttached();
        if (fallbackAttached) hud.bringToFront();
    }

    String report() {
        StringBuilder b = new StringBuilder();
        b.append("S04TV Fire Edition – Fernbedienungsdiagnose v0.7.2\n");
        b.append("Nur Tastencodes und App-Zustand, keine Inhalte oder Zugangsdaten.\n");
        b.append("Android API: ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        b.append("Debug: ").append(enabled ? "AN" : "AUS").append('\n');
        b.append("HUD: ").append(windowAttached ? "Application-Panel" :
                fallbackAttached ? "Activity-Fallback" : "nicht angehaengt").append('\n');
        b.append("HUD sichtbar: ").append(hud.isShown()).append('\n');
        b.append("HUD Groesse: ").append(hud.getWidth()).append("x")
                .append(hud.getHeight()).append('\n');
        b.append("HUD-Fehler: ").append(lastWindowError).append('\n');
        b.append("Home/System-Tasten koennen von Fire OS abgefangen werden.\n\n");
        if (records.isEmpty()) b.append("(Noch keine Ereignisse)\n");
        else for (String line : records) b.append(line).append('\n');
        return b.toString();
    }

    void showReport() {
        StringBuilder preview = new StringBuilder();
        int skip = Math.max(0, records.size() - 35);
        int i = 0;
        for (String line : records) {
            if (i++ >= skip) preview.append(line).append('\n');
        }
        if (preview.length() == 0) preview.append("Noch keine Ereignisse.");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Fernbedienungsdiagnose (" + records.size() + " Ereignisse)")
                .setMessage("HUD: " + (windowAttached ? "Application-Panel" :
                        fallbackAttached ? "Fallback" : lastWindowError) +
                        "\nSichtbar: " + hud.isShown() +
                        "\n\n" + preview)
                .setPositiveButton("Protokoll kopieren", (d, which) -> copyReport())
                .setNeutralButton("Leeren", (d, which) -> clear())
                .setNegativeButton("Schließen", null)
                .show();
        observeDialog(dialog, "Debug-Protokoll");
    }

    void copyReport() {
        ClipboardManager clipboard = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(activity, "Zwischenablage nicht verfügbar", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("S04TV Debug", report()));
        Toast.makeText(activity, "Debug-Protokoll kopiert", Toast.LENGTH_SHORT).show();
    }

    void clear() {
        records.clear();
        recordState("Protokoll geleert");
        refresh();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static boolean isRemoteKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return true;
            default:
                return false;
        }
    }
}
