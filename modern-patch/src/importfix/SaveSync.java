package importfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.Date;

/**
 * "Sync saves" for the Modern app.
 *
 * The drawer entry opens a dialog that compares the player's online save with the
 * offline one (when each was last saved, species caught, play time) and can copy
 * the online save over the offline one. It is one-way (online to offline) and
 * never uploads anything.
 *
 * The online save is fetched from the official server with the login the player
 * already has from Online mode in this app. The offline save lives in the WebView's
 * localStorage for the offline game's origin, so it is read and written by a small
 * hidden page served on that origin.
 *
 * The Modern app has no public source, so this class is compiled separately and
 * added to the APK; a few one-line hooks in the app's own code call into it.
 */
public final class SaveSync {
    private static final String SITE = "https://pokerogue.net";
    private static final String API = "https://api.pokerogue.net";
    private static final String SESSION_COOKIE = "pokerogue_sessionId";
    private static final String OFFLINE_HOST = "localhost";
    private static final String HOST_PAGE = "https://localhost:8080/__savesync/index.html";
    private static final long CHECK_TIMEOUT_MS = 50000;
    private static final long APPLY_TIMEOUT_MS = 10000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> activityRef = new WeakReference<Activity>(null);
    private static WeakReference<WebView> gameRef = new WeakReference<WebView>(null);
    private static Session current;

    private SaveSync() {
    }

    // ---- Hooks called from the app's own code ----

    /** Hook: MainActivity.onCreate. */
    public static void setActivity(Activity activity) {
        activityRef = new WeakReference<Activity>(activity);
    }

    /** Hook: where the app configures the game WebView. */
    public static void attach(WebView gameWebView) {
        gameRef = new WeakReference<WebView>(gameWebView);
    }

    /** Hook: the game WebView's onPageFinished. */
    public static void onPageFinished(WebView view, String url) {
        if (isOfflineGame(url)) {
            view.evaluateJavascript(IMPORT_LOG_JS, null);
        }
    }

    /** Hook: the "Sync saves" drawer entry was tapped. Runs on the main thread. */
    public static void openMenu() {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || current != null) {
            return;
        }
        current = new Session(activity);
        current.start();
    }

    // ---- One run of the dialog ----

    private static final class Session {
        private final Activity activity;
        private WebView host;
        private AlertDialog dialog;
        private boolean hostReady;
        private boolean fetchDone;
        private boolean compared;
        private boolean closed;
        private String onlineSave;
        private String onlineProblem;

        Session(Activity activity) {
            this.activity = activity;
        }

        void start() {
            showDialog("Checking your saves...", null, null);

            host = new WebView(activity);
            host.getSettings().setJavaScriptEnabled(true);
            host.getSettings().setDomStorageEnabled(true);
            host.addJavascriptInterface(new HostBridge(this), "saveSyncHost");
            host.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    // Serve the helper page from memory and let nothing else load.
                    String body = HOST_PAGE.equals(request.getUrl().toString()) ? HOST_HTML : "";
                    return new WebResourceResponse("text/html", "utf-8",
                            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
                }
            });
            host.loadUrl(HOST_PAGE);

            final String token = sessionToken();
            if (token == null) {
                onlineProblem = "You are not logged in online. Open Online mode in this app, log in, then come back.";
                fetchDone = true;
            } else {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        download(token);
                    }
                }, "save-sync").start();
            }

            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!closed && !compared) {
                        compared = true;
                        showDialog("Could not read the saves. Nothing was changed.", null, null);
                    }
                }
            }, CHECK_TIMEOUT_MS);
        }

        private void download(String token) {
            String save = null;
            String problem = null;
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
                        problem = "The server reply was not a save.";
                    }
                } else if (code == 401 || code == 403) {
                    problem = "Your online login expired. Open Online mode, log in again, then come back.";
                } else {
                    problem = "The online server returned " + code + ".";
                }
            } catch (IOException e) {
                problem = "Could not reach the online server. Check your connection.";
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final String finalSave = save;
            final String finalProblem = problem;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    onlineSave = finalSave;
                    onlineProblem = finalProblem;
                    fetchDone = true;
                    compareWhenReady();
                }
            });
        }

        void onHostReady() {
            hostReady = true;
            compareWhenReady();
        }

        private void compareWhenReady() {
            if (closed || !hostReady || !fetchDone || compared) {
                return;
            }
            compared = true;
            String argument = onlineSave == null ? "null" : JSONObject.quote(onlineSave);
            host.evaluateJavascript("window.__sync.compare(" + argument + ");", null);
        }

        void onCompared(String json) {
            if (closed) {
                return;
            }
            JSONObject local;
            JSONObject online;
            try {
                JSONObject both = new JSONObject(json);
                local = both.getJSONObject("local");
                online = both.getJSONObject("online");
            } catch (JSONException e) {
                showDialog("Could not read the saves. Nothing was changed.", null, null);
                return;
            }

            StringBuilder message = new StringBuilder();
            message.append("ONLINE SAVE\n");
            message.append(onlineProblem != null ? onlineProblem : describe(online));
            message.append("\n\nOFFLINE SAVE\n").append(describe(local));

            boolean canCopy = onlineProblem == null && usable(online);
            if (canCopy) {
                message.append("\n\n").append(verdict(local, online));
            }
            showDialog(message.toString(), canCopy ? "Copy online to offline" : null,
                    new Runnable() {
                        @Override
                        public void run() {
                            host.evaluateJavascript("window.__sync.apply();", null);
                            // Never leave the session open if the page does not answer.
                            MAIN.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    close();
                                }
                            }, APPLY_TIMEOUT_MS);
                        }
                    });
        }

        void onApplied(boolean ok, String problem) {
            if (closed) {
                return;
            }
            if (!ok) {
                showDialog("Could not store the save. Nothing was changed.\n" + problem, null, null);
                return;
            }
            Toast.makeText(activity, "Offline save replaced with your online save", Toast.LENGTH_LONG).show();
            // A running offline game still holds the old save in memory; reload it.
            WebView game = gameRef.get();
            if (game != null && isOfflineGame(game.getUrl())) {
                game.reload();
            }
            close();
        }

        /**
         * Shows one dialog at a time. With an action the dialog has a confirm button;
         * the session stays open while the action runs and ends otherwise.
         */
        private void showDialog(String message, String actionLabel, final Runnable action) {
            if (closed || activity.isFinishing()) {
                close();
                return;
            }
            final AlertDialog previous = dialog;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle("Sync saves")
                    .setMessage(message)
                    .setNegativeButton(actionLabel == null ? "Close" : "Cancel", null);
            final boolean[] acted = {false};
            if (actionLabel != null) {
                builder.setPositiveButton(actionLabel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        acted[0] = true;
                        action.run();
                    }
                });
            }
            final AlertDialog next = builder.create();
            next.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    // Ends the session unless a newer dialog replaced this one or work is in flight.
                    if (dialog == next && !acted[0]) {
                        close();
                    }
                }
            });
            dialog = next;
            next.show();
            if (previous != null) {
                previous.dismiss();
            }
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
            if (host != null) {
                host.destroy();
                host = null;
            }
            if (current == this) {
                current = null;
            }
        }
    }

    /** Receives results from the hidden helper page. Calls arrive on a WebView thread. */
    private static final class HostBridge {
        private final Session session;

        HostBridge(Session session) {
            this.session = session;
        }

        @JavascriptInterface
        public void onReady() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onHostReady();
                }
            });
        }

        @JavascriptInterface
        public void onCompared(final String json) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onCompared(json);
                }
            });
        }

        @JavascriptInterface
        public void onApplied(final boolean ok, final String problem) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onApplied(ok, problem);
                }
            });
        }
    }

    // ---- Helpers ----

    private static boolean usable(JSONObject summary) {
        return summary.optBoolean("exists") && !summary.optBoolean("broken");
    }

    private static String describe(JSONObject summary) {
        if (!summary.optBoolean("exists")) {
            return "None yet.";
        }
        if (summary.optBoolean("broken")) {
            return "Present, but could not be read.";
        }
        long saved = summary.optLong("timestamp");
        String when = saved > 0
                ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(saved))
                : "unknown";
        long hours = Math.round(summary.optDouble("playTime", 0) / 3600.0);
        return "Last saved: " + when
                + "\nSpecies caught: " + summary.optInt("caught")
                + "\nPlay time: about " + hours + " h";
    }

    private static String verdict(JSONObject local, JSONObject online) {
        if (!usable(local)) {
            return "There is no offline save to lose.";
        }
        long localSaved = local.optLong("timestamp");
        long onlineSaved = online.optLong("timestamp");
        if (onlineSaved > localSaved) {
            return "The online save is newer.";
        }
        if (onlineSaved < localSaved) {
            return "The OFFLINE save is newer. Copying replaces it with the older online save.";
        }
        return "Both were saved at the same time.";
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

    // ---- Page scripts ----

    /**
     * Hidden helper page, served on the offline game's origin so it shares that
     * game's localStorage. The offline game keeps its save under "data_Guest" as
     * btoa(encodeURIComponent(json)).
     */
    static final String HOST_HTML = String.join("\n",
            "<!doctype html><meta charset=\"utf-8\"><script>",
            "(function() {",
            "  var KEY = 'data_Guest';",
            "  var online = null;",
            "  function readLocal() {",
            "    try { var v = localStorage.getItem(KEY); return v ? decodeURIComponent(atob(v)) : null; }",
            "    catch (e) { return '{broken'; }",
            "  }",
            "  function summarize(text) {",
            "    if (!text) { return { exists: false }; }",
            "    try {",
            "      var d = JSON.parse(text);",
            "      var dex = d.dexData || {};",
            "      var caught = 0;",
            "      for (var k in dex) {",
            "        var attr = dex[k] && dex[k].caughtAttr;",
            "        if (attr && String(attr) !== '0') { caught++; }",
            "      }",
            "      return {",
            "        exists: true,",
            "        timestamp: Number(d.timestamp) || 0,",
            "        caught: caught,",
            "        playTime: Number(d.gameStats && d.gameStats.playTime) || 0",
            "      };",
            "    } catch (e) { return { exists: true, broken: true }; }",
            "  }",
            "  window.__sync = {",
            "    compare: function(onlineText) {",
            "      online = onlineText;",
            "      saveSyncHost.onCompared(JSON.stringify({ local: summarize(readLocal()), online: summarize(onlineText) }));",
            "    },",
            "    apply: function() {",
            "      try {",
            "        if (!online) { throw new Error('no online save loaded'); }",
            "        JSON.parse(online);",
            "        localStorage.setItem(KEY, btoa(encodeURIComponent(online)));",
            "        saveSyncHost.onApplied(true, '');",
            "      } catch (e) { saveSyncHost.onApplied(false, String((e && e.message) || e)); }",
            "    }",
            "  };",
            "  saveSyncHost.onReady();",
            "})();",
            "</script>");

    /**
     * Diagnostic for the game's own Import Data: an on-screen log of each step, so a
     * screenshot shows where an import stops. It only records; it changes nothing.
     */
    static final String IMPORT_LOG_JS = String.join("\n",
            "(function() {",
            "  if (window.__importLogInstalled) { return; }",
            "  window.__importLogInstalled = true;",
            "  var CARRY = '__importLogCarry';",
            "  var lines = [];",
            "  var box = null;",
            "  var active = false;",
            "  var t0 = 0;",
            "  var hideTimer = null;",
            "  function render() {",
            "    if (!document.body) { return; }",
            "    if (!box) {",
            "      box = document.createElement('div');",
            "      box.style.cssText = 'position:fixed;left:4px;bottom:4px;z-index:2147483647;max-width:80vw;'",
            "        + 'max-height:70vh;overflow:hidden;background:rgba(0,0,0,.85);color:#7CFC00;'",
            "        + 'font:11px monospace;padding:6px;white-space:pre-wrap;pointer-events:none';",
            "      document.body.appendChild(box);",
            "    }",
            "    box.textContent = 'IMPORT LOG\\n' + lines.join('\\n');",
            "    clearTimeout(hideTimer);",
            "    hideTimer = setTimeout(function() { if (box) { box.remove(); box = null; } active = false; }, 90000);",
            "  }",
            "  function log(text) {",
            "    lines.push(((Date.now() - t0) / 1000).toFixed(1) + 's ' + text);",
            "    render();",
            "  }",
            "  var originalClick = HTMLInputElement.prototype.click;",
            "  HTMLInputElement.prototype.click = function() {",
            "    if (this.type === 'file') {",
            "      active = true; t0 = Date.now(); lines = [];",
            "      log('picker opened');",
            "      this.addEventListener('change', function(e) {",
            "        var f = e.target.files && e.target.files[0];",
            "        log(f ? 'file received: ' + f.name + ', ' + f.size + ' bytes' : 'picker returned no file');",
            "      }, true);",
            "      this.addEventListener('cancel', function() { log('picker cancelled'); });",
            "    }",
            "    return originalClick.apply(this, arguments);",
            "  };",
            "  var originalRead = FileReader.prototype.readAsText;",
            "  FileReader.prototype.readAsText = function() {",
            "    if (active) {",
            "      var reader = this;",
            "      log('reading file');",
            "      reader.addEventListener('load', function() {",
            "        var text = String(reader.result || '');",
            "        log('file read: ' + text.length + ' chars, starts ' + JSON.stringify(text.slice(0, 10)));",
            "      });",
            "      reader.addEventListener('error', function() {",
            "        log('FILE READ FAILED: ' + (reader.error ? reader.error.name : 'unknown'));",
            "      });",
            "    }",
            "    return originalRead.apply(this, arguments);",
            "  };",
            "  var originalSet = Storage.prototype.setItem;",
            "  Storage.prototype.setItem = function(key, value) {",
            "    if (active && this === window.localStorage && /^data_/.test(key)) {",
            "      log('save stored under ' + key + ': ' + String(value).length + ' chars');",
            "    }",
            "    return originalSet.apply(this, arguments);",
            "  };",
            "  var originalError = console.error;",
            "  console.error = function() {",
            "    if (active) { log('game error: ' + String(arguments[0]).slice(0, 120)); }",
            "    return originalError.apply(this, arguments);",
            "  };",
            "  window.addEventListener('error', function(e) {",
            "    if (active) { log('SCRIPT ERROR: ' + String(e.message).slice(0, 160)); }",
            "  });",
            "  window.addEventListener('unhandledrejection', function(e) {",
            "    if (active) { log('PROMISE ERROR: ' + String((e.reason && e.reason.message) || e.reason).slice(0, 160)); }",
            "  });",
            "  document.addEventListener('visibilitychange', function() {",
            "    if (active) { log(document.hidden ? 'app went to background' : 'app came back'); }",
            "  });",
            "  window.addEventListener('pagehide', function() {",
            "    if (!active) { return; }",
            "    lines.push('page reloading');",
            "    try { sessionStorage.setItem(CARRY, JSON.stringify(lines)); } catch (e) {}",
            "  });",
            "  try {",
            "    var carried = sessionStorage.getItem(CARRY);",
            "    if (carried) {",
            "      sessionStorage.removeItem(CARRY);",
            "      lines = JSON.parse(carried);",
            "      var stored = localStorage.getItem('data_Guest');",
            "      lines.push('after reload: offline save is ' + (stored ? stored.length + ' chars' : 'MISSING'));",
            "      render();",
            "    }",
            "  } catch (e) {}",
            "})();");
}
