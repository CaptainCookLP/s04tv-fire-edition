package de.s04tv.fireedition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Opt-in TV remote debugger for video recordings and ADB logcat.
 *
 * Never inspects input text, WebView content, URLs, passwords, cookies,
 * network traffic, or player tokens. Logs only a small allowlist of remote
 * key codes and native application state. Android's system-owned Home key
 * and some hardware keys may not reach Activity.dispatchKeyEvent().
 */
final class RemoteDebugOverlay {
    private static final String PREF_ENABLED = "remote_debug_enabled";
    private static final String LOG_TAG = "S04TVRemote";
    private static final int MAX_RECORDS = 180;
    private static final int HUD_RECORDS = 5;

    private final Activity activity;
    private final SharedPreferences preferences;
    private final TextView hud;
    private final ArrayDeque<String> records = new ArrayDeque<>();
    private final SimpleDateFormat timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY);
    private boolean enabled;

    RemoteDebugOverlay(Activity activity, FrameLayout root, SharedPreferences preferences) {
        this.activity = activity;
        this.preferences = preferences;
        this.enabled = preferences.getBoolean(PREF_ENABLED, false);

        hud = new TextView(activity);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(15);
        hud.setPadding(dp(14), dp(10), dp(14), dp(10));
        hud.setMaxWidth(dp(850));
        hud.setFocusable(false);
        hud.setClickable(false);
        hud.setLongClickable(false);
        hud.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(224, 2, 16, 31));
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), Color.rgb(0, 177, 235));
        hud.setBackground(background);

        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        position.setMargins(dp(16), dp(12), dp(16), 0);
        root.addView(hud, position);
        if (enabled) recordState("Debug-Modus gestartet");
        else refresh();
    }

    boolean isEnabled() { return enabled; }

    void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        preferences.edit().putBoolean(PREF_ENABLED, value).apply();
        if (value) {
            records.clear();
            recordState("Debug-Modus eingeschaltet");
        } else {
            refresh();
        }
    }

    void bringToFront() {
        if (enabled) hud.bringToFront();
    }

    /** Observes keys in Android modal dialogs without swallowing them. */
    void observeDialog(AlertDialog dialog, String context) {
        if (dialog == null) return;
        dialog.setOnKeyListener((d, keyCode, event) -> {
            recordKey(event, context, false);
            return false;
        });
    }

    void recordKey(KeyEvent event, String route, boolean handledByActivity) {
        if (!enabled || event == null || !isRemoteKey(event.getKeyCode())) return;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP)
            return;
        // Keep a held-down button visible, without filling the log with repeats.
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

    private void add(String message) {
        String entry = timestamp.format(new Date()) + "  " + message;
        records.addLast(entry);
        while (records.size() > MAX_RECORDS) records.removeFirst();
        Log.i(LOG_TAG, entry);
        refresh();
    }

    private void refresh() {
        hud.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;
        StringBuilder b = new StringBuilder("S04TV REMOTE DEBUG  [AN]  •  Menü → Debugging\n");
        int skip = Math.max(0, records.size() - HUD_RECORDS);
        int i = 0;
        for (String record : records) {
            if (i++ >= skip) b.append(record).append('\n');
        }
        hud.setText(b.toString().trim());
        hud.bringToFront();
    }

    String report() {
        StringBuilder b = new StringBuilder();
        b.append("S04TV Fire Edition – Fernbedienungsdiagnose\n");
        b.append("Nur Tastencodes und App-Zustand. Keine Inhalte, URLs oder Zugangsdaten.\n");
        b.append("Android API: ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        b.append("Home/System-Tasten können von Fire OS abgefangen werden.\n\n");
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
                .setMessage(preview.toString())
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
