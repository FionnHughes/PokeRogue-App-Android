package importfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
 * Both saves are reached through a hidden WebView, one small page per origin:
 * a page on the official site's origin fetches the online save exactly as the game
 * does (same login cookie, same request), and a page on the offline game's origin
 * reads and writes that game's localStorage.
 *
 * The Modern app has no public source, so this class is compiled separately and
 * added to the APK; a few one-line hooks in the app's own code call into it.
 */
public final class SaveSync {
    private static final String API_HOST = "api.pokerogue.net";
    private static final String OFFLINE_HOST = "localhost";
    private static final String ONLINE_PAGE = "https://pokerogue.net/__savesync/online.html";
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

    /**
     * Hook: Android handed the file picker's result back to the app. Adds it to the
     * import log, which otherwise cannot tell "no file returned" from "user cancelled".
     */
    public static void onPickerResult(Uri uri) {
        WebView game = gameRef.get();
        if (game == null) {
            return;
        }
        String note = uri == null
                ? "Android gave the app NO file"
                : "Android gave the app a file: " + uri.toString();
        game.evaluateJavascript(
                "window.__importLogNote && window.__importLogNote(" + JSONObject.quote(note) + ");", null);
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
                    if (API_HOST.equals(request.getUrl().getHost())) {
                        return null; // the one real network request: the official save API
                    }
                    // The two helper pages come from memory; nothing else may load.
                    String url = request.getUrl().toString();
                    String body = ONLINE_PAGE.equals(url) ? ONLINE_HTML : HOST_PAGE.equals(url) ? HOST_HTML : "";
                    return new WebResourceResponse("text/html", "utf-8",
                            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
                }
            });
            // Step 1: fetch the online save. Step 2 (onOnline) opens the offline page.
            host.loadUrl(ONLINE_PAGE);

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

        /** Result of the online page's request. Status 0 means no login cookie, -1 a network failure. */
        void onOnline(int status, String body) {
            if (closed || fetchDone) {
                return;
            }
            fetchDone = true;
            if (status == 200 && looksLikeSave(body)) {
                onlineSave = body;
            } else {
                onlineProblem = describeFailure(status, body);
            }
            host.loadUrl(HOST_PAGE);
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
        public void onOnline(final int status, final String body) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onOnline(status, body);
                }
            });
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

    private static boolean looksLikeSave(String body) {
        String text = body.trim();
        return text.startsWith("{") && text.contains("\"dexData\"") && text.contains("\"timestamp\"");
    }

    private static String describeFailure(int status, String detail) {
        switch (status) {
            case 0:
                return "You are not logged in online. Open Online mode in this app, log in, then come back.";
            case -1:
                return "Could not reach the online server (" + detail + ").";
            case 200:
                return "The server reply was not a save.";
            case 401:
                return "The server did not accept your login (401). Open Online mode, log out and back in, then try again.";
            case 404:
                return "This account has no online save yet.";
            default:
                return "The online server returned " + status + ".";
        }
    }

    // ---- Page scripts ----

    /**
     * Hidden helper page, served on the official site's origin. It makes the same
     * request the game makes for the save: the login cookie as the Authorization
     * header, and a fresh client session id.
     */
    static final String ONLINE_HTML = String.join("\n",
            "<!doctype html><meta charset=\"utf-8\"><script>",
            "(function() {",
            "  // Every value stored for the login cookie. A stale duplicate can sit next to",
            "  // the live one, so each is tried until the server accepts one.",
            "  function tokens(name) {",
            "    var found = [];",
            "    var parts = document.cookie.split(';');",
            "    for (var i = 0; i < parts.length; i++) {",
            "      var c = parts[i].trim();",
            "      if (c.indexOf(name + '=') !== 0) { continue; }",
            "      var value = c.slice(name.length + 1);",
            "      if (value && found.indexOf(value) < 0) { found.push(value); }",
            "    }",
            "    return found;",
            "  }",
            "  var list = tokens('pokerogue_sessionId');",
            "  if (!list.length) { saveSyncHost.onOnline(0, ''); return; }",
            "  var alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';",
            "  var id = '';",
            "  for (var i = 0; i < 32; i++) { id += alphabet.charAt(Math.floor(Math.random() * alphabet.length)); }",
            "  function attempt(index) {",
            "    fetch('https://api.pokerogue.net/savedata/system/get?clientSessionId=' + id, {",
            "      headers: { Authorization: list[index], 'Content-Type': 'application/json' }",
            "    }).then(function(response) {",
            "      return response.text().then(function(text) {",
            "        if (response.status === 401 && index + 1 < list.length) { attempt(index + 1); return; }",
            "        saveSyncHost.onOnline(response.status, response.ok ? text : '');",
            "      });",
            "    }).catch(function(e) {",
            "      saveSyncHost.onOnline(-1, String((e && e.message) || e).slice(0, 120));",
            "    });",
            "  }",
            "  attempt(0);",
            "})();",
            "</script>");

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
            "  window.__importLogNote = function(text) { if (active) { log(String(text).slice(0, 140)); } };",
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
