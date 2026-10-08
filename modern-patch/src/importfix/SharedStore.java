package importfix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Settings and backups in the owner's store (modern-patch/server/history_hub.py),
 * next to the shared run history. The desktop app follows the same rules
 * (desktop/src/settings-sync.js).
 *
 * Settings: the game keeps them in each browser only. Until the player chooses, each
 * device leaves its settings in the store as a candidate. After the choice, a device
 * that changed its settings since its last exchange sends them, and otherwise takes
 * the shared ones; when both changed, the later change wins. A few entries belong to
 * the device (touch controls, vibration) and are never taken from another one.
 *
 * Backups: copies of save data, run history and runs in progress, so they can be
 * restored on any device. Nothing here touches Android, so it runs and is tested on a JVM.
 */
final class SharedStore {
    /** What the game keeps in localStorage that counts as settings (game version 1.12). */
    static final String[] KEYS = {"settings", "settingsKeyboard", "settingsGamepad", "mappingConfigs", "prLang",
        "tutorials", "seenDialogues"};
    /** Entries of "settings" that stay as each device has them. */
    static final String[] OWN = {"TOUCH_CONTROLS", "MOVE_TOUCH_CONTROLS", "VIBRATION", "gameVersion"};

    static final String CHOOSE = "choose";
    static final String SAME = "same";
    static final String PUSHED = "pushed";
    static final String APPLY = "apply";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_ANSWER_BYTES = 16 * 1024 * 1024;

    private SharedStore() {
    }

    /** What a settings exchange did. */
    static final class Result {
        final String action;
        /** Who the shared settings came from, "" if there are none yet. */
        final String from;
        /** What to write for {@link #APPLY}: key to text. Null otherwise. */
        final JSONObject write;
        /** To keep as this device's last exchange, or null to keep the old one. */
        final JSONObject last;

        Result(String action, String from, JSONObject write, JSONObject last) {
            this.action = action;
            this.from = from;
            this.write = write;
            this.last = last;
        }
    }

    /**
     * One settings exchange for one device.
     *
     * @param store   the account's address in the store, ending in "/"
     * @param local   this device's settings, key to text (missing keys are not set)
     * @param last    this device's last exchange, {"updated", "hash"}, or null
     * @param changed when this device's change was noticed, 0 if not known
     */
    static Result exchange(String store, String code, String device, String label, JSONObject local,
            JSONObject last, long changed, long now) throws IOException, JSONException {
        JSONObject mine = new JSONObject();
        mine.put("updated", now);
        mine.put("from", label);
        mine.put("data", pick(local));
        // Kept fresh, so a later choice offers what each device has now.
        check(request("PUT", store + "settings/candidates/" + device, code, bytes(mine.toString())));

        Answer got = request("GET", store + "settings", code, null);
        if (got.status == 404) {
            return new Result(CHOOSE, "", null, null);
        }
        check(got);
        JSONObject shared = new JSONObject(got.text());
        String localHash = hash(local);
        String sharedHash = hash(shared.optJSONObject("data"));
        if (localHash.equals(sharedHash)) {
            return new Result(SAME, shared.optString("from"), null, lastOf(shared.optLong("updated"), sharedHash));
        }
        boolean localChanged = last != null && !localHash.equals(last.optString("hash"));
        boolean sharedChanged = last == null || !sharedHash.equals(last.optString("hash"));
        boolean localWins = localChanged && (!sharedChanged || changed > shared.optLong("updated"));
        if (localWins) {
            long updated = Math.max(changed > 0 ? changed : now, shared.optLong("updated") + 1);
            mine.put("updated", updated);
            Answer sent = request("PUT", store + "settings", code, bytes(mine.toString()));
            if (sent.status != 409) {
                check(sent);
                return new Result(PUSHED, label, null, lastOf(updated, localHash));
            }
            shared = new JSONObject(sent.text()); // changed elsewhere in the meantime: that change is later
        }
        JSONObject data = shared.optJSONObject("data");
        return new Result(APPLY, shared.optString("from"), merge(local, data == null ? new JSONObject() : data),
                lastOf(shared.optLong("updated"), hash(data)));
    }

    /** The devices that have left their settings, as {"device", "from", "updated"}. */
    static JSONArray candidates(String store, String code) throws IOException, JSONException {
        Answer got = request("GET", store + "settings/candidates/", code, null);
        check(got);
        JSONArray list = new JSONObject(got.text()).optJSONArray("candidates");
        return list == null ? new JSONArray() : list;
    }

    /** Makes one device's candidate the shared settings. */
    static void choose(String store, String code, String device, long now) throws IOException, JSONException {
        Answer got = request("GET", store + "settings/candidates/" + device, code, null);
        check(got);
        JSONObject candidate = new JSONObject(got.text());
        JSONObject shared = new JSONObject();
        shared.put("updated", now);
        shared.put("from", candidate.optString("from"));
        shared.put("data", candidate.optJSONObject("data"));
        check(request("PUT", store + "settings", code, bytes(shared.toString())));
    }

    // ---- backups ----

    /**
     * Stores one backup under "&lt;made&gt;-&lt;device&gt;". sessions is a JSON array of
     * five texts ("" for an empty slot), or "" for none.
     */
    static void putBackup(String store, String code, String device, String label, String origin, String account,
            String save, String history, String sessions, long made) throws IOException, JSONException {
        JSONObject backup = new JSONObject();
        backup.put("made", made);
        backup.put("from", label);
        backup.put("origin", origin);
        backup.put("account", account);
        backup.put("save", save == null ? "" : save);
        backup.put("history", history == null ? "" : history);
        backup.put("sessions", sessions == null || sessions.isEmpty() ? new JSONArray() : new JSONArray(sessions));
        check(request("PUT", store + "backups/" + made + "-" + device, code, bytes(backup.toString())));
    }

    /** The account's backups in the store, newest first: {"id", "from", "made", "size"}. */
    static JSONArray backups(String store, String code) throws IOException, JSONException {
        Answer got = request("GET", store + "backups/", code, null);
        check(got);
        JSONArray list = new JSONObject(got.text()).optJSONArray("backups");
        return list == null ? new JSONArray() : list;
    }

    static JSONObject backup(String store, String code, String id) throws IOException, JSONException {
        Answer got = request("GET", store + "backups/" + id, code, null);
        check(got);
        return new JSONObject(got.text());
    }

    // ---- comparing settings ----

    /** A short fingerprint of settings, ignoring key order and the device's own entries. */
    static String hash(JSONObject data) {
        return sha256(stable(normalize(data)));
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes(text));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The settings as compared between devices: parsed, own entries left out. */
    static JSONObject normalize(JSONObject data) {
        JSONObject out = new JSONObject();
        for (String key : KEYS) {
            String text = data == null || data.isNull(key) ? "" : data.optString(key, "");
            if (text.isEmpty()) {
                continue;
            }
            try {
                Object value = parse(text);
                if ("settings".equals(key) && value instanceof JSONObject) {
                    for (String own : OWN) {
                        ((JSONObject) value).remove(own);
                    }
                }
                out.put(key, stable(value));
            } catch (JSONException e) {
                try {
                    out.put(key, text);
                } catch (JSONException cannot) {
                    // a string value under a known key always fits
                }
            }
        }
        return out;
    }

    /** What to write so this device uses the shared settings but keeps its own entries. */
    static JSONObject merge(JSONObject local, JSONObject shared) throws JSONException {
        JSONObject out = new JSONObject();
        for (String key : KEYS) {
            String text = shared.isNull(key) ? "" : shared.optString(key, "");
            if (text.isEmpty()) {
                continue;
            }
            if (!"settings".equals(key)) {
                out.put(key, text);
                continue;
            }
            try {
                JSONObject value = new JSONObject(text);
                JSONObject mine;
                try {
                    String own = local == null || local.isNull("settings") ? "" : local.optString("settings", "");
                    mine = own.isEmpty() ? new JSONObject() : new JSONObject(own);
                } catch (JSONException e) {
                    mine = new JSONObject();
                }
                for (String own : OWN) {
                    if (mine.has(own)) {
                        value.put(own, mine.get(own));
                    } else {
                        value.remove(own);
                    }
                }
                out.put(key, value.toString());
            } catch (JSONException e) {
                out.put(key, text);
            }
        }
        return out;
    }

    private static JSONObject pick(JSONObject local) throws JSONException {
        JSONObject out = new JSONObject();
        for (String key : KEYS) {
            if (local != null && !local.isNull(key) && local.has(key)) {
                out.put(key, local.optString(key));
            }
        }
        return out;
    }

    private static JSONObject lastOf(long updated, String hash) {
        JSONObject last = new JSONObject();
        try {
            last.put("updated", updated);
            last.put("hash", hash);
        } catch (JSONException e) {
            // cannot happen: the keys are not null
        }
        return last;
    }

    private static Object parse(String text) throws JSONException {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            return new JSONObject(trimmed);
        }
        if (trimmed.startsWith("[")) {
            return new JSONArray(trimmed);
        }
        if (trimmed.startsWith("\"")) {
            return new JSONArray("[" + trimmed + "]").get(0);
        }
        return trimmed;
    }

    /** JSON text with object keys sorted, so equal settings give equal text. */
    static String stable(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> names = new ArrayList<String>();
            for (Iterator<String> it = object.keys(); it.hasNext();) {
                names.add(it.next());
            }
            Collections.sort(names);
            StringBuilder out = new StringBuilder("{");
            for (String name : names) {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append(JSONObject.quote(name)).append(':').append(stable(object.opt(name)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(stable(array.opt(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof String) {
            return JSONObject.quote((String) value);
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
        }
        return String.valueOf(value);
    }

    // ---- talking to the store ----

    private static final class Answer {
        final int status;
        final byte[] body;

        Answer(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static void check(Answer answer) throws IOException {
        if (answer.status == 401) {
            throw new IOException("the store did not accept the code");
        }
        if (answer.status < 200 || answer.status >= 300) {
            throw new IOException("the store answered " + answer.status);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Answer request(String method, String url, String code, byte[] body) throws IOException {
        URL address = new URL(url);
        boolean local = "127.0.0.1".equals(address.getHost());
        if (!"https".equals(address.getProtocol()) && !local) {
            throw new IOException("the store is not on https");
        }
        HttpURLConnection connection = (HttpURLConnection) address.openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + code);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", "application/json");
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    if (out.size() > MAX_ANSWER_BYTES) {
                        throw new IOException("the store's answer is too large");
                    }
                }
            }
            return new Answer(status, out.toByteArray());
        } finally {
            connection.disconnect();
        }
    }
}
