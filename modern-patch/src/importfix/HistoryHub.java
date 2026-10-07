package importfix;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Shares run history between the player's devices through a small store on the
 * owner's own server (modern-patch/server/history_hub.py).
 *
 * The game keeps run history in each browser and never sends it to its server, so
 * a run finished on a laptop is otherwise unknown to this phone. Each device sends
 * the store the finished runs it lacks and fetches the ones the device lacks.
 *
 * A run history is the game's own JSON: an object of finished runs keyed by the
 * time each ended. The store keeps each game account's runs apart, so the address
 * an exchange is given names the account: ".../h/&lt;account&gt;/". Nothing here touches Android, so it runs and is tested on a JVM.
 */
final class HistoryHub {
    /** The game keeps this many finished runs (its RUN_HISTORY_LIMIT). */
    static final int LIMIT = 25;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_RUN_BYTES = 512 * 1024;

    /** What an exchange with the store did. */
    static final class Result {
        /** The history with the store's runs added, cut to the newest {@link #LIMIT}. */
        final String merged;
        final int received;
        final int sent;

        Result(String merged, int received, int sent) {
            this.merged = merged;
            this.received = received;
            this.sent = sent;
        }
    }

    private HistoryHub() {
    }

    /**
     * Sends the store the runs of `history` it lacks, and fetches the runs that belong
     * among the newest {@link #LIMIT} and that `history` lacks.
     *
     * @param storeUrl the address of the account's runs in the store, ending in "/"
     * @param history  a run history as JSON text, "" for none
     */
    static Result exchange(String storeUrl, String code, String history) throws IOException, JSONException {
        JSONObject mine = runs(history);
        Set<String> theirs = new HashSet<String>();
        JSONArray listed = new JSONObject(text(request("GET", storeUrl, code, null))).getJSONArray("runs");
        for (int i = 0; i < listed.length(); i++) {
            theirs.add(listed.getString(i));
        }

        int sent = 0;
        for (String key : keys(mine)) {
            if (!theirs.contains(key) && isKey(key)) {
                request("PUT", storeUrl + key, code, mine.get(key).toString().getBytes(StandardCharsets.UTF_8));
                sent++;
            }
        }

        // Only runs that make it into the newest ones are worth fetching.
        List<String> all = new ArrayList<String>(theirs);
        for (String key : keys(mine)) {
            if (!theirs.contains(key)) {
                all.add(key);
            }
        }
        JSONObject merged = new JSONObject();
        int received = 0;
        for (String key : newest(all)) {
            if (mine.has(key)) {
                merged.put(key, mine.get(key));
            } else {
                merged.put(key, new JSONObject(text(request("GET", storeUrl + key, code, null))));
                received++;
            }
        }
        return new Result(merged.toString(), received, sent);
    }

    /** Sends the store every run of `history` it does not have yet. Returns how many were sent. */
    static int send(String storeUrl, String code, String history) throws IOException, JSONException {
        return exchange(storeUrl, code, history).sent;
    }

    /** A run history as an object; anything that is not one counts as empty. */
    static JSONObject runs(String history) {
        try {
            return history == null || history.isEmpty() ? new JSONObject() : new JSONObject(history);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static List<String> keys(JSONObject object) {
        List<String> out = new ArrayList<String>();
        for (Iterator<String> it = object.keys(); it.hasNext();) {
            out.add(it.next());
        }
        return out;
    }

    private static boolean isKey(String key) {
        return key.matches("[0-9]{10,16}");
    }

    /** The newest {@link #LIMIT} of the keys, which are times in milliseconds. */
    private static List<String> newest(List<String> keys) {
        List<String> usable = new ArrayList<String>();
        for (String key : keys) {
            if (isKey(key)) {
                usable.add(key);
            }
        }
        Collections.sort(usable, (a, b) -> Long.compare(Long.parseLong(b), Long.parseLong(a)));
        return usable.size() > LIMIT ? usable.subList(0, LIMIT) : usable;
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** One request to the store. Anything but a 2xx answer is an error. */
    private static byte[] request(String method, String url, String code, byte[] body) throws IOException {
        URL address = new URL(url);
        boolean local = "127.0.0.1".equals(address.getHost());
        if (!"https".equals(address.getProtocol()) && !local) {
            throw new IOException("the run history store is not on https");
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
            if (status == 401) {
                throw new IOException("the store did not accept the code");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("the store answered " + status);
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > MAX_RUN_BYTES) {
                    throw new IOException("the store's answer is too large");
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }
}
