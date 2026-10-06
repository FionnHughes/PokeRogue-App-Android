package importfix;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The network half of the updater: reads the server's description of the newest
 * build and downloads the APK, making sure it is the file that was described.
 * Nothing here touches Android, so it runs and is tested on a plain JVM.
 */
final class UpdateFiles {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_DESCRIPTION_BYTES = 64 * 1024;
    private static final long MAX_APK_BYTES = 300L * 1024 * 1024;

    /** What the server says about the newest build. */
    static final class Latest {
        final int build;
        final String apkUrl;
        final String sha256;
        /** The APK's size in bytes, or -1 if the description does not say. */
        final long size;
        final String notes;

        Latest(int build, String apkUrl, String sha256, long size, String notes) {
            this.build = build;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
        }
    }

    /** Told how far a download is, and asked whether to go on. Called off the main thread. */
    interface Watcher {
        /** @param percent 0 to 100, or -1 if the size is not known */
        void progress(int percent);

        boolean cancelled();
    }

    private UpdateFiles() {
    }

    /** Fetches and reads the description at the given address. */
    static Latest latest(String descriptionUrl) throws IOException, JSONException {
        HttpURLConnection connection = open(descriptionUrl);
        try {
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > MAX_DESCRIPTION_BYTES) {
                    throw new IOException("the description is too large");
                }
            }
            return parse(out.toString("UTF-8"), descriptionUrl);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Reads a description: {"build": 12, "apk": "app.apk", "sha256": "...", "size": 123, "notes": "..."}.
     * "apk" may be a full address or a name next to the description, and has to end up on https.
     */
    static Latest parse(String json, String descriptionUrl) throws JSONException, IOException {
        JSONObject object = new JSONObject(json);
        URL apk = new URL(new URL(descriptionUrl), object.getString("apk"));
        if (!"https".equals(apk.getProtocol())) {
            throw new IOException("the update is not on https");
        }
        String sha256 = object.getString("sha256").trim().toLowerCase(Locale.US);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("the update's checksum is not a SHA-256");
        }
        return new Latest(object.getInt("build"), apk.toString(), sha256, object.optLong("size", -1),
                object.optString("notes", ""));
    }

    /**
     * Downloads the APK to a file. Fails, leaving no file, unless what arrived has
     * the described size and checksum.
     */
    static void download(Latest latest, File target, Watcher watcher) throws IOException {
        try {
            copy(latest, target, watcher);
        } catch (IOException | RuntimeException e) {
            target.delete();
            throw e;
        }
    }

    private static void copy(Latest latest, File target, Watcher watcher) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("this phone cannot check the download");
        }
        HttpURLConnection connection = open(latest.apkUrl);
        long total = 0;
        try {
            InputStream in = connection.getInputStream();
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[65536];
                int count;
                int shown = -2;
                while ((count = in.read(buffer)) != -1) {
                    if (watcher.cancelled()) {
                        throw new IOException("cancelled");
                    }
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    total += count;
                    if (total > MAX_APK_BYTES) {
                        throw new IOException("the file is too large");
                    }
                    int percent = latest.size > 0 ? (int) Math.min(100, total * 100 / latest.size) : -1;
                    if (percent != shown) {
                        shown = percent;
                        watcher.progress(percent);
                    }
                }
            } finally {
                out.close();
            }
        } finally {
            connection.disconnect();
        }
        if (latest.size > 0 && total != latest.size) {
            throw new IOException("the file is " + total + " bytes, expected " + latest.size);
        }
        if (!hex(digest.digest()).equals(latest.sha256)) {
            throw new IOException("the file does not match its checksum");
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IOException("the server answered " + status);
        }
        return connection;
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
