package importfix;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;

/**
 * Adds a "Sync from online" button to the offline game in the Modern app.
 *
 * The button downloads the player's own save from the official server, using the
 * login they already have from Online mode in this app, and stores it as the offline
 * save. It is one-way (online to offline) and never uploads anything.
 *
 * The Modern app has no public source, so this class is compiled separately and
 * added to the APK; two one-line hooks in the app's own code call into it.
 */
public final class SaveSync {
    private static final String SITE = "https://pokerogue.net";
    private static final String API = "https://api.pokerogue.net";
    private static final String SESSION_COOKIE = "pokerogue_sessionId";
    private static final String OFFLINE_HOST = "localhost";
    private static final String JS_NAME = "saveSync";

    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    private SaveSync(WebView webView) {
        this.webView = webView;
    }

    /** Hook: called where the app configures the game WebView. */
    public static void attach(WebView webView) {
        webView.addJavascriptInterface(new SaveSync(webView), JS_NAME);
    }

    /** Hook: called from the app's onPageFinished. */
    public static void onPageFinished(WebView view, String url) {
        if (isOfflineGame(url)) {
            view.evaluateJavascript(BUTTON_JS, null);
        }
    }

    /** Called by the button. Runs on the WebView's bridge thread, so hop to the main thread. */
    @JavascriptInterface
    public void sync() {
        main.post(new Runnable() {
            @Override
            public void run() {
                start();
            }
        });
    }

    private void start() {
        // Only the offline game may trigger a sync; other pages share this WebView.
        if (busy || !isOfflineGame(webView.getUrl())) {
            return;
        }
        final String token = sessionToken();
        if (token == null) {
            status("Not logged in. Open Online mode, log in, then try again.", false);
            return;
        }
        busy = true;
        status("Downloading your online save...", true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                download(token);
            }
        }, "save-sync").start();
    }

    private void download(String token) {
        String save = null;
        String error = null;
        HttpURLConnection connection = null;
        try {
            URL url = new URL(API + "/savedata/system/get?clientSessionId=" + randomId());
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Authorization", token);
            connection.setRequestProperty("Content-Type", "application/json");
            int code = connection.getResponseCode();
            if (code == 200) {
                String body = readAll(connection.getInputStream());
                if (looksLikeSave(body)) {
                    save = body;
                } else {
                    error = "The server reply was not a save. Nothing was changed.";
                }
            } else if (code == 401 || code == 403) {
                error = "Login expired. Open Online mode, log in again, then retry.";
            } else {
                error = "Server returned " + code + ". Nothing was changed.";
            }
        } catch (IOException e) {
            error = "Network error. Nothing was changed.";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        final String finalSave = save;
        final String finalError = error;
        main.post(new Runnable() {
            @Override
            public void run() {
                finish(finalSave, finalError);
            }
        });
    }

    private void finish(String save, String error) {
        busy = false;
        if (error != null) {
            status(error, false);
            return;
        }
        if (!isOfflineGame(webView.getUrl())) {
            return;
        }
        webView.evaluateJavascript(
                "window.__saveSyncApply && window.__saveSyncApply(" + JSONObject.quote(save) + ");", null);
    }

    private void status(String text, boolean working) {
        webView.evaluateJavascript(
                "window.__saveSyncStatus && window.__saveSyncStatus(" + JSONObject.quote(text) + ","
                        + working + ");", null);
    }

    private static boolean isOfflineGame(String url) {
        if (url == null) {
            return false;
        }
        try {
            return OFFLINE_HOST.equals(new URL(url).getHost());
        } catch (IOException e) {
            return false;
        }
    }

    /** The login cookie the official site sets after logging in through Online mode. */
    private static String sessionToken() {
        String cookies = CookieManager.getInstance().getCookie(SITE);
        if (cookies == null) {
            return null;
        }
        String prefix = SESSION_COOKIE + "=";
        for (String part : cookies.split(";")) {
            String cookie = part.trim();
            if (cookie.startsWith(prefix) && cookie.length() > prefix.length()) {
                return cookie.substring(prefix.length());
            }
        }
        return null;
    }

    private static boolean looksLikeSave(String body) {
        String text = body.trim();
        return text.startsWith("{") && text.contains("\"dexData\"") && text.contains("\"timestamp\"");
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static String randomId() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder id = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            id.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    /**
     * Page script: shows the button for a short while after the offline game loads.
     * The first tap arms it and the second tap starts the sync, because a sync
     * replaces the offline save. The offline game reads its save from the
     * "data_Guest" key, stored as btoa(encodeURIComponent(json)).
     */
    static final String BUTTON_JS = String.join("\n",
            "(function() {",
            "  if (window.__saveSyncInstalled) { return; }",
            "  window.__saveSyncInstalled = true;",
            "  var KEY = 'data_Guest';",
            "  var DONE_FLAG = '__saveSyncDone';",
            "  var btn = document.createElement('button');",
            "  btn.style.cssText = 'position:fixed;top:8px;left:50%;transform:translateX(-50%);'",
            "    + 'z-index:2147483647;padding:10px 16px;font:16px sans-serif;color:#fff;'",
            "    + 'background:#2563eb;border:0;border-radius:8px;max-width:90vw;touch-action:manipulation';",
            "  var armed = false;",
            "  var busy = false;",
            "  var hideTimer = null;",
            "  function remove() { if (!busy && btn.parentNode) { btn.parentNode.removeChild(btn); } }",
            "  function hideAfter(ms) { clearTimeout(hideTimer); hideTimer = setTimeout(remove, ms); }",
            "  function show(text) { btn.textContent = text; }",
            "  window.__saveSyncStatus = function(text, working) {",
            "    busy = !!working; armed = false; show(text);",
            "    if (busy) { clearTimeout(hideTimer); } else { hideAfter(8000); }",
            "  };",
            "  window.__saveSyncApply = function(data) {",
            "    try {",
            "      JSON.parse(data);",
            "      localStorage.setItem(KEY, btoa(encodeURIComponent(data)));",
            "      sessionStorage.setItem(DONE_FLAG, '1');",
            "      busy = true; show('Synced. Reloading...');",
            "      setTimeout(function() { location.reload(); }, 600);",
            "    } catch (e) {",
            "      window.__saveSyncStatus('Could not store the save. Nothing was changed.', false);",
            "    }",
            "  };",
            "  function tap(ev) {",
            "    ev.preventDefault(); ev.stopPropagation();",
            "    if (busy) { return; }",
            "    if (!armed) { armed = true; show('Tap again: this replaces your offline save'); hideAfter(10000); return; }",
            "    armed = false;",
            "    if (window.saveSync) { window.saveSync.sync(); } else { show('Sync is not available'); hideAfter(5000); }",
            "  }",
            "  btn.addEventListener('pointerup', tap);",
            "  var justSynced = false;",
            "  try { justSynced = sessionStorage.getItem(DONE_FLAG) === '1'; sessionStorage.removeItem(DONE_FLAG); } catch (e) {}",
            "  if (justSynced) { busy = false; show('Online save synced'); btn.disabled = true; hideAfter(5000); }",
            "  else { show('Sync from online'); hideAfter(20000); }",
            "  (document.body || document.documentElement).appendChild(btn);",
            "})();");
}
