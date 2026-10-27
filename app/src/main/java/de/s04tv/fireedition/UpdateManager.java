package de.s04tv.fireedition;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Public GitHub Releases updater. Never installs silently or bypasses Android installer. */
final class UpdateManager {
    private static final String API =
            "https://api.github.com/repos/CaptainCookLP/s04tv-fire-edition/releases/latest";
    private static final String ASSET = "s04tv-fire-edition.apk";
    private static final String DOWNLOAD_NAME = "s04tv-fire-edition-update.apk";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final DownloadManager downloads;
    private boolean receiverRegistered = false;
    private boolean pendingPermission = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id < 0 || id != prefs.getLong("update_download_id", -1)) return;
            verifyDownloadedUpdate(id);
        }
    };

    UpdateManager(Activity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
        this.downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        activity.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        receiverRegistered = true;
    }

    interface StartupListener {
        void onStatus(String message);
        void onFinished();
    }

    // Always check when starting. Called while the startup splash is covering the WebView.
    void checkAtStartup(StartupListener listener) {
        checkForUpdates(false, listener);
    }

    void checkForUpdates(boolean manual) {
        checkForUpdates(manual, null);
    }

    private void startupStatus(StartupListener listener, String message) {
        if (listener == null) return;
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) listener.onStatus(message);
        });
    }

    private void startupDone(StartupListener listener) {
        if (listener == null) return;
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) listener.onFinished();
        });
    }

    private void checkForUpdates(boolean manual, StartupListener startupListener) {
        if (!manual && startupListener == null) {
            long checkedAt = prefs.getLong("update_checked_at", 0L);
            long now = System.currentTimeMillis();
            if (now >= checkedAt && now - checkedAt < DAY_MS) return;
        }
        if (manual) toast("Prüfe GitHub Releases …");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(API).openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "S04TV-Fire-Edition-Android");
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(4500);
                int status = connection.getResponseCode();
                if (status == 404) {
                    if (manual) notifyDialog("Keine Veröffentlichung",
                            "Auf GitHub ist noch kein Release verfügbar.");
                    startupStatus(startupListener, "Keine Veröffentlichung gefunden");
                    startupDone(startupListener);
                    return;
                }
                if (status != 200) throw new IllegalStateException("GitHub HTTP " + status);
                StringBuilder json = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        json.append(line);
                        if (json.length() > 128 * 1024) throw new IllegalStateException("Antwort zu groß");
                    }
                }
                JSONObject release = new JSONObject(json.toString());
                String tag = release.optString("tag_name", "");
                if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) {
                    throw new IllegalStateException("Ungültiger Release-Tag");
                }
                JSONArray assets = release.optJSONArray("assets");
                JSONObject apk = null;
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject item = assets.optJSONObject(i);
                    if (item != null && ASSET.equals(item.optString("name"))) {
                        apk = item;
                        break;
                    }
                }
                String installed = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
                prefs.edit().putLong("update_checked_at", System.currentTimeMillis()).apply();
                if (compareVersions(tag.substring(1), installed) <= 0) {
                    if (manual) notifyDialog("S04TV ist aktuell",
                            "Installiert: v" + installed + "\nGitHub Release: " + tag);
                    startupStatus(startupListener, "S04TV ist aktuell (v" + installed + ")");
                    startupDone(startupListener);
                    return;
                }
                if (apk == null) throw new IllegalStateException("Kein APK im GitHub Release");
                String url = apk.optString("browser_download_url", "");
                String expectedUrl = "https://github.com/CaptainCookLP/s04tv-fire-edition/releases/download/"
                        + tag + "/" + ASSET;
                if (!expectedUrl.equals(url)) {
                    throw new IllegalStateException("Unbekannte Downloadadresse");
                }
                String digest = apk.optString("digest", "").toLowerCase(Locale.ROOT);
                if (!digest.matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalStateException("Release hat keine prüfbare SHA-256-Signatur");
                }
                String sha256 = digest.substring("sha256:".length());
                boolean cached = sha256.equals(prefs.getString("update_expected_sha256", "")) &&
                        tag.equals(prefs.getString("update_download_version", "")) &&
                        updateFile() != null && updateFile().isFile();
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (startupListener != null) startupListener.onStatus("Update " + tag + " verfügbar");
                    new AlertDialog.Builder(activity)
                            .setTitle("Update " + tag + " verfügbar")
                            .setMessage("Installiert: v" + installed + "\nNeu: " + tag +
                                    (cached ? "\n\nDas Update ist bereits heruntergeladen und wird vor der Installation geprüft."
                                            : "\n\nJetzt von GitHub herunterladen und anschließend installieren?"))
                            .setPositiveButton(cached ? "Installieren" : "Herunterladen & installieren", (dialog, which) -> {
                                if (cached) verifyFileAndInstall();
                                else downloadUpdate(url, sha256, tag);
                                if (startupListener != null) startupListener.onFinished();
                            })
                            .setNegativeButton("Später", (dialog, which) -> {
                                if (startupListener != null) startupListener.onFinished();
                            })
                            .setOnCancelListener(dialog -> {
                                if (startupListener != null) startupListener.onFinished();
                            })
                            .show();
                });
            } catch (Exception exception) {
                if (manual) notifyDialog("Updateprüfung fehlgeschlagen",
                        "GitHub konnte nicht geprüft werden: " + exception.getMessage());
                // Never block startup if GitHub is offline or the network times out.
                startupStatus(startupListener, "Updateprüfung derzeit nicht möglich");
                startupDone(startupListener);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "s04tv-update-check").start();
    }

    private int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = i < left.length ? Integer.parseInt(left[i]) : 0;
            int y = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private File updateFile() {
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return dir == null ? null : new File(dir, DOWNLOAD_NAME);
    }

    private void downloadUpdate(String url, String sha256, String tag) {
        try {
            if (downloads == null) throw new IllegalStateException("DownloadManager nicht verfügbar");
            File file = updateFile();
            if (file == null) throw new IllegalStateException("Speicher nicht verfügbar");
            if (file.exists() && !file.delete()) throw new IllegalStateException("Alte APK nicht löschbar");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("S04TV Fire Edition Update");
            request.setDescription("APK von github.com");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity,
                    Environment.DIRECTORY_DOWNLOADS, DOWNLOAD_NAME);
            long id = downloads.enqueue(request);
            prefs.edit().putLong("update_download_id", id)
                    .putString("update_expected_sha256", sha256)
                    .putString("update_download_version", tag).apply();
            toast("Update wird heruntergeladen");
        } catch (Exception error) {
            notifyDialog("Download fehlgeschlagen", error.getMessage());
        }
    }

    private void verifyDownloadedUpdate(long id) {
        if (downloads == null) return;
        int status;
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Download nicht gefunden");
            status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        } catch (Exception exception) {
            notifyDialog("Downloadfehler", exception.getMessage());
            return;
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            notifyDialog("Update fehlgeschlagen", "Der APK-Download war nicht erfolgreich.");
            return;
        }
        verifyFileAndInstall();
    }

    private void verifyFileAndInstall() {
        File file = updateFile();
        String expected = prefs.getString("update_expected_sha256", "");
        if (file == null || !file.isFile() || !expected.matches("[0-9a-f]{64}")) {
            notifyDialog("Kein Update", "Keine verifizierbare heruntergeladene APK vorhanden. Bitte zuerst nach Updates suchen.");
            return;
        }
        new Thread(() -> {
            try (FileInputStream stream = new FileInputStream(file)) {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = stream.read(buffer)) != -1) sha.update(buffer, 0, n);
                StringBuilder computed = new StringBuilder();
                for (byte value : sha.digest()) computed.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                if (!expected.equals(computed.toString())) {
                    activity.runOnUiThread(() -> {
                        file.delete();
                        notifyDialog("Update blockiert", "Die SHA-256-Prüfsumme stimmt nicht mit GitHub überein.");
                    });
                    return;
                }
                activity.runOnUiThread(() -> requestInstall(file));
            } catch (Exception error) {
                notifyDialog("Prüfung fehlgeschlagen", error.getMessage());
            }
        }, "s04tv-apk-verify").start();
    }

    private void requestInstall(File file) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingPermission = true;
            new AlertDialog.Builder(activity).setTitle("Installation erlauben")
                    .setMessage("Erlaube S04TV in den Fire-TV-Einstellungen die Installation unbekannter Apps. Danach wird die geprüfte APK zur Installation angeboten.")
                    .setPositiveButton("Einstellungen", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(intent);
                        } catch (Exception e) {
                            notifyDialog("Einstellungen", "Öffne die Fire-TV-Einstellungen und aktiviere die Installation unbekannter Apps für S04TV.");
                        }
                    }).setNegativeButton("Abbrechen", (dialog, which) ->
                            pendingPermission = false).show();
            return;
        }
        pendingPermission = false;
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            notifyDialog("Installation nicht möglich",
                    "Der APK-Installer konnte nicht gestartet werden: " + e.getMessage());
        }
    }

    void onResume() {
        if (pendingPermission && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                activity.getPackageManager().canRequestPackageInstalls())) {
            pendingPermission = false;
            verifyFileAndInstall();
        }
    }

    private void toast(String text) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        });
    }

    private void notifyDialog(String title, String message) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                        .setPositiveButton("OK", null).show();
            }
        });
    }

    void destroy() {
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }
}
