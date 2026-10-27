package de.s04tv.fireedition;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/**
 * Private persistent cache for PUBLIC first-party static images only.
 * Files live in filesDir (not cacheDir) and survive app restarts.
 * Never stores HTML, cookies, auth/API responses, manifests, .m4s or DRM traffic.
 * WebView's normal HTTP disk cache handles CSS/JS and revalidation.
 */
final class PersistentAssetCache {
    private static final long MAX_TOTAL = 128L * 1024L * 1024L;
    private static final int MAX_ENTRY = 4 * 1024 * 1024;
    private static final long MAX_AGE = 7L * 24L * 60L * 60L * 1000L;
    private final File folder;

    PersistentAssetCache(Context context) {
        folder = new File(context.getFilesDir(), "public-image-cache");
        if (!folder.isDirectory()) folder.mkdirs();
    }

    private String type(Uri uri) {
        String path = uri.getPath();
        if (path == null) return null;
        path = path.toLowerCase(Locale.ROOT);
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif")) return "image/gif";
        return null;
    }

    private String digest(String url) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null || !"GET".equalsIgnoreCase(request.getMethod())) return null;
        Uri uri = request.getUrl();
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                !"tv.schalke04.de".equalsIgnoreCase(uri.getHost()) ||
                uri.getEncodedQuery() != null || uri.getEncodedFragment() != null) return null;
        String mime = type(uri);
        if (mime == null) return null;
        Map<String, String> headers = request.getRequestHeaders();
        if (headers != null) for (String key : headers.keySet()) {
            if ("cookie".equalsIgnoreCase(key) || "authorization".equalsIgnoreCase(key)) return null;
        }
        try {
            File file = new File(folder, digest(uri.toString()) + ".img");
            WebResourceResponse hit = cached(file, mime);
            if (hit != null) return hit;
            return fetch(uri.toString(), file, mime);
        } catch (Exception e) {
            return null; // Let the original WebView request handle failures.
        }
    }

    private WebResourceResponse cached(File file, String mime) {
        if (!file.isFile() || file.length() <= 8 || file.length() > MAX_ENTRY + 8L) return null;
        try {
            DataInputStream in = new DataInputStream(new FileInputStream(file));
            long created = in.readLong();
            if (created < 1 || System.currentTimeMillis() - created > MAX_AGE) {
                in.close();
                file.delete();
                return null;
            }
            file.setLastModified(System.currentTimeMillis());
            return new WebResourceResponse(mime, null, in);
        } catch (Exception e) {
            file.delete();
            return null;
        }
    }

    private WebResourceResponse fetch(String url, File file, String mime) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(4500);
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif,*/*;q=0.5");
            if (connection.getResponseCode() != 200) return null;
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/"))
                return null;
            int length = connection.getContentLength();
            if (length > MAX_ENTRY) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                    length > 0 ? Math.min(length, MAX_ENTRY) : 32 * 1024);
            try (InputStream in = connection.getInputStream()) {
                byte[] tmp = new byte[16 * 1024];
                int count;
                while ((count = in.read(tmp)) != -1) {
                    if (buffer.size() + count > MAX_ENTRY) return null;
                    buffer.write(tmp, 0, count);
                }
            }
            byte[] body = buffer.toByteArray();
            String policy = connection.getHeaderField("Cache-Control");
            // This request is strictly anonymous, URL has no query, and the
            // resource must be an image with no Set-Cookie or private cache policy.
            // This includes common static assets that use max-age without 'public'.
            String lowerPolicy = policy == null ? "" : policy.toLowerCase(Locale.ROOT);
            boolean publicContent = connection.getHeaderField("Set-Cookie") == null &&
                    !lowerPolicy.contains("no-store") &&
                    !lowerPolicy.contains("no-cache") &&
                    !lowerPolicy.contains("private");
            if (publicContent && body.length > 0) save(file, body);
            return new WebResourceResponse(mime, null, new ByteArrayInputStream(body));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private synchronized void save(File file, byte[] body) {
        if (!folder.isDirectory() && !folder.mkdirs()) return;
        File temp = new File(folder, file.getName() + "." + Thread.currentThread().getId() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temp))) {
            out.writeLong(System.currentTimeMillis());
            out.write(body);
        } catch (IOException e) {
            temp.delete();
            return;
        }
        if (file.exists() && !file.delete()) { temp.delete(); return; }
        if (!temp.renameTo(file)) { temp.delete(); return; }
        trim();
    }

    private void trim() {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long size = 0;
        for (File file : files) size += file.length();
        for (File file : files) {
            if (size <= MAX_TOTAL) break;
            size -= file.length();
            file.delete();
        }
    }

    synchronized void clear() {
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) file.delete();
    }
}
