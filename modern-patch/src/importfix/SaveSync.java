package importfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
 * Save tools for the Modern app, opened from two entries in the drawer's Tools list.
 *
 * "Sync saves" compares the player's online save with the offline one (when each was
 * last saved, species caught, play time) and can copy either one over the other.
 * Copying offline to online is the same request the game makes when it saves, so the
 * official server applies its own checks and may refuse it.
 *
 * "Copy Pokémon caught" puts a text list of the Pokémon usable as starters on the
 * clipboard.
 *
 * Both saves are reached through a hidden WebView, one small page per origin:
 * a page on the official site's origin talks to the official server exactly as the
 * game does (same login cookie, same requests), and a page on the offline game's
 * origin reads and writes that game's localStorage.
 *
 * The Modern app has no public source, so this class is compiled separately and
 * added to the APK; a few one-line hooks in the app's own code call into it.
 */
public final class SaveSync {
    /** Drawer entries, as the title ids the patch script gives them. */
    private static final int ENTRY_SYNC = 0;
    private static final int ENTRY_COPY_STARTERS = 1;

    private static final String API_HOST = "api.pokerogue.net";
    private static final String ONLINE_HOST = "pokerogue.net";
    private static final String OFFLINE_HOST = "localhost";
    private static final String ONLINE_PAGE = "https://pokerogue.net/__savesync/online.html";
    private static final String UPLOAD_PAGE = ONLINE_PAGE + "?upload";
    private static final String HOST_PAGE = "https://localhost:8080/__savesync/index.html";
    /** Scripts shipped in the APK's assets/savesync folder, served to the offline helper page. */
    private static final String ASSET_URL_PREFIX = "https://localhost:8080/__savesync/assets/";
    private static final String[] ASSETS = {"tables.js", "starters.js"};
    private static final long CHECK_TIMEOUT_MS = 50000;
    private static final long APPLY_TIMEOUT_MS = 10000;
    private static final long UPLOAD_TIMEOUT_MS = 60000;

    private static final String NEWER_OFFLINE_WARNING =
            "Your offline save is newer.\n\n"
            + "This replaces it with the older online save. The progress made offline since then is lost.";
    private static final String UPLOAD_WARNING =
            "Not recommended.\n\n"
            + "This replaces your ONLINE save with the offline one. Anything done online since your last sync is lost,"
            + " and it cannot be undone.\n\n"
            + "PokéRogue does not support moving offline progress online, so this is at your own account's risk.\n\n"
            + "The server only accepts it if the offline save came from this account and has at least as much play time.";

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
        if (hostOf(url).equals(OFFLINE_HOST)) {
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

    /** Hook: one of this class's drawer entries was tapped. Runs on the main thread. */
    public static void onDrawerEntry(int entry) {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || current != null) {
            return;
        }
        if (entry != ENTRY_SYNC && entry != ENTRY_COPY_STARTERS) {
            return;
        }
        current = new Session(activity, entry == ENTRY_COPY_STARTERS);
        current.start();
    }

    // ---- One run of a dialog ----

    private static final class Session {
        private final Activity activity;
        private final boolean copyStarters;
        private final String title;
        private WebView host;
        private AlertDialog dialog;
        private boolean hostReady;
        private boolean fetchDone;
        private boolean compared;
        private boolean uploading;
        private boolean closed;
        private String onlineSave;
        private String onlineProblem;
        private String uploadText;

        Session(Activity activity, boolean copyStarters) {
            this.activity = activity;
            this.copyStarters = copyStarters;
            this.title = copyStarters ? "Copy Pokémon caught" : "Sync saves";
        }

        void start() {
            showDialog("Checking your saves...", null, null, null, null);

            host = new WebView(activity);
            host.getSettings().setJavaScriptEnabled(true);
            host.getSettings().setDomStorageEnabled(true);
            host.addJavascriptInterface(new HostBridge(this), "saveSyncHost");
            host.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (API_HOST.equals(request.getUrl().getHost())) {
                        return null; // the only real network traffic: the official save API
                    }
                    // Everything else comes from memory or the APK; nothing else may load.
                    String url = request.getUrl().toString();
                    if (url.startsWith(ASSET_URL_PREFIX)) {
                        return asset(url.substring(ASSET_URL_PREFIX.length()));
                    }
                    String body = ONLINE_PAGE.equals(url) || UPLOAD_PAGE.equals(url) ? ONLINE_HTML
                            : HOST_PAGE.equals(url) ? HOST_HTML : "";
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
                        showDialog("Could not read the saves. Nothing was changed.", null, null, null, null);
                    }
                }
            }, CHECK_TIMEOUT_MS);
        }

        private WebResourceResponse asset(String name) {
            for (String known : ASSETS) {
                if (known.equals(name)) {
                    try {
                        return new WebResourceResponse("application/javascript", "utf-8",
                                activity.getAssets().open("savesync/" + name));
                    } catch (IOException e) {
                        break;
                    }
                }
            }
            return new WebResourceResponse("application/javascript", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
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
            if (closed || !fetchDone || compared) {
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
                showDialog("Could not read the saves. Nothing was changed.", null, null, null, null);
                return;
            }
            boolean onlineOk = onlineProblem == null && usable(online);
            boolean localOk = usable(local);
            if (copyStarters) {
                offerStarterLists(local, online, localOk, onlineOk);
            } else {
                offerSync(local, online, localOk, onlineOk);
            }
        }

        // ---- Sync saves ----

        private void offerSync(JSONObject local, JSONObject online, boolean localOk, boolean onlineOk) {
            StringBuilder message = new StringBuilder();
            message.append("ONLINE SAVE\n");
            message.append(onlineProblem != null ? onlineProblem : describe(online));
            message.append("\n\nOFFLINE SAVE\n").append(describe(local));
            if (onlineOk) {
                message.append("\n\n").append(verdict(local, online, localOk));
            }

            final boolean offlineNewer = onlineOk && localOk
                    && local.optLong("timestamp") > online.optLong("timestamp");
            final Runnable toOffline = new Runnable() {
                @Override
                public void run() {
                    host.evaluateJavascript("window.__sync.apply();", null);
                    closeAfter(APPLY_TIMEOUT_MS);
                }
            };
            Runnable download = !offlineNewer ? toOffline : new Runnable() {
                @Override
                public void run() {
                    showDialog(NEWER_OFFLINE_WARNING, "Replace offline save", toOffline, null, null);
                }
            };
            Runnable upload = new Runnable() {
                @Override
                public void run() {
                    showDialog(UPLOAD_WARNING, "Replace online save", new Runnable() {
                        @Override
                        public void run() {
                            startUpload();
                        }
                    }, null, null);
                }
            };
            showDialog(message.toString(),
                    onlineOk ? "Copy online to offline" : null, download,
                    onlineOk && localOk ? "Copy offline to online" : null, upload);
        }

        void onApplied(boolean ok, String problem) {
            if (closed) {
                return;
            }
            if (!ok) {
                showDialog("Could not store the save. Nothing was changed.\n" + problem, null, null, null, null);
                return;
            }
            Toast.makeText(activity, "Offline save replaced with your online save", Toast.LENGTH_LONG).show();
            reloadGameOn(OFFLINE_HOST); // a running offline game still holds the old save in memory
            close();
        }

        /** Upload, step 1: read the offline save. Steps 2 and 3 follow in onLocalRaw and onUploadReady. */
        private void startUpload() {
            uploading = true;
            showDialog("Uploading your offline save...", null, null, null, null);
            host.evaluateJavascript("window.__sync.readLocal();", null);
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!closed && uploading) {
                        uploading = false;
                        showDialog("The server did not answer. Check your online save before trying again.",
                                null, null, null, null);
                    }
                }
            }, UPLOAD_TIMEOUT_MS);
        }

        void onLocalRaw(String text) {
            if (closed || !uploading) {
                return;
            }
            if (text.isEmpty()) {
                uploading = false;
                showDialog("The offline save could not be read. Nothing was changed.", null, null, null, null);
                return;
            }
            uploadText = text;
            host.loadUrl(UPLOAD_PAGE);
        }

        void onUploadReady() {
            if (closed || !uploading || uploadText == null) {
                return;
            }
            host.evaluateJavascript("window.__online.upload(" + JSONObject.quote(uploadText) + ");", null);
        }

        void onUploaded(int status, String body) {
            if (closed || !uploading) {
                return;
            }
            uploading = false;
            if (status >= 200 && status < 300) {
                Toast.makeText(activity, "Online save replaced with your offline save", Toast.LENGTH_LONG).show();
                reloadGameOn(ONLINE_HOST); // a running online game still holds the old save in memory
                close();
                return;
            }
            showDialog(describeUploadFailure(status, body), null, null, null, null);
        }

        // ---- Copy Pokémon caught ----

        private void offerStarterLists(JSONObject local, JSONObject online, boolean localOk, boolean onlineOk) {
            String message = "ONLINE SAVE\n"
                    + (onlineProblem != null ? onlineProblem : describeStarters(online))
                    + "\n\nOFFLINE SAVE\n" + describeStarters(local)
                    + "\n\nThe list goes to the clipboard as text: natures, IVs, abilities, starting moves and egg moves"
                    + " for each Pokémon.";
            showDialog(message,
                    onlineOk ? "Copy online list" : null, copyList("online"),
                    localOk ? "Copy offline list" : null, copyList("local"));
        }

        private Runnable copyList(final String which) {
            return new Runnable() {
                @Override
                public void run() {
                    host.evaluateJavascript("window.__sync.starters('" + which + "');", null);
                    closeAfter(APPLY_TIMEOUT_MS);
                }
            };
        }

        void onStarters(int count, String text) {
            if (closed) {
                return;
            }
            if (count < 0) {
                showDialog("Could not build the list: " + text, null, null, null, null);
                return;
            }
            try {
                ClipboardManager clipboard =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("PokéRogue starters", text));
            } catch (RuntimeException e) {
                showDialog("The list could not be copied to the clipboard.", null, null, null, null);
                return;
            }
            Toast.makeText(activity, "Copied " + count + " Pokémon to the clipboard", Toast.LENGTH_LONG).show();
            close();
        }

        // ---- Dialog plumbing ----

        /**
         * Shows one dialog at a time, replacing the previous one. A dialog can have up to
         * two actions. Choosing one keeps the session open while it runs; closing the
         * dialog any other way ends the session.
         */
        private void showDialog(String message, String firstLabel, final Runnable first,
                String secondLabel, final Runnable second) {
            if (closed || activity.isFinishing()) {
                close();
                return;
            }
            final AlertDialog previous = dialog;
            boolean hasAction = firstLabel != null || secondLabel != null;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setNegativeButton(hasAction ? "Cancel" : "Close", null);
            final boolean[] acted = {false};
            if (firstLabel != null) {
                builder.setPositiveButton(firstLabel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        acted[0] = true;
                        first.run();
                    }
                });
            }
            if (secondLabel != null) {
                builder.setNeutralButton(secondLabel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        acted[0] = true;
                        second.run();
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

        /** Never leave the session open if the helper page does not answer. */
        private void closeAfter(long delayMs) {
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    close();
                }
            }, delayMs);
        }

        private void reloadGameOn(String host) {
            WebView game = gameRef.get();
            if (game != null && hostOf(game.getUrl()).equals(host)) {
                game.reload();
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

    /** Receives results from the hidden helper pages. Calls arrive on a WebView thread. */
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

        @JavascriptInterface
        public void onLocalRaw(final String text) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onLocalRaw(text);
                }
            });
        }

        @JavascriptInterface
        public void onUploadReady() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onUploadReady();
                }
            });
        }

        @JavascriptInterface
        public void onUploaded(final int status, final String body) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onUploaded(status, body);
                }
            });
        }

        @JavascriptInterface
        public void onStarters(final int count, final String text) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onStarters(count, text);
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

    private static String describeStarters(JSONObject summary) {
        if (!summary.optBoolean("exists")) {
            return "None yet.";
        }
        int starters = summary.optInt("starters", -1);
        if (summary.optBoolean("broken") || starters < 0) {
            return "Present, but could not be read.";
        }
        return starters + " Pokémon usable as starters";
    }

    private static String verdict(JSONObject local, JSONObject online, boolean localOk) {
        if (!localOk) {
            return "There is no offline save to lose.";
        }
        long localSaved = local.optLong("timestamp");
        long onlineSaved = online.optLong("timestamp");
        if (onlineSaved > localSaved) {
            return "The online save is newer. Recommended: copy online to offline.";
        }
        if (onlineSaved < localSaved) {
            return "The OFFLINE save is newer. Copying online to offline would lose that progress.";
        }
        return "Both were saved at the same time.";
    }

    /** The host name of a URL, or "" if there is none. */
    private static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String host = new URL(url).getHost();
            return host == null ? "" : host;
        } catch (IOException e) {
            return "";
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

    private static String describeUploadFailure(int status, String detail) {
        if (status == -1) {
            return "The connection failed during the upload (" + detail
                    + "). Check your online save before trying again.";
        }
        String reason;
        if (status == 0) {
            reason = "You are not logged in online. Open Online mode in this app, log in, then come back.";
        } else if (status == 401) {
            reason = "The server did not accept your login. Open Online mode, log out and back in, then try again.";
        } else if (detail.contains("trainer or secret ID")) {
            reason = "The offline save did not come from this account. Copy online to offline first, then play offline.";
        } else if (detail.contains("existing playtime is greater")) {
            reason = "The online save has more play time than the offline one, so the server keeps the online save.";
        } else if (detail.contains("version")) {
            reason = "The offline game and the online save are on different game versions.";
        } else {
            reason = "The server answered " + status + ".";
        }
        return "The server refused the upload. Your online save was not changed.\n\n" + reason
                + (detail.isEmpty() ? "" : "\n\nServer message: " + detail);
    }

    // ---- Page scripts ----

    /**
     * Hidden helper page, served on the official site's origin. It makes the same
     * requests the game makes: the login cookie as the Authorization header and a
     * fresh client session id. Loaded plain it fetches the save; loaded with
     * "?upload" it waits for a save to send, fetches first (the server only takes
     * an update from the session that last fetched), then posts the update.
     */
    static final String ONLINE_HTML = String.join("\n",
            "<!doctype html><meta charset=\"utf-8\"><script>",
            "(function() {",
            "  var API = 'https://api.pokerogue.net/savedata/system/';",
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
            "  var alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';",
            "  var id = '';",
            "  for (var i = 0; i < 32; i++) { id += alphabet.charAt(Math.floor(Math.random() * alphabet.length)); }",
            "  function headers(index) { return { Authorization: list[index], 'Content-Type': 'application/json' }; }",
            "  function problem(e) { return String((e && e.message) || e).slice(0, 120); }",
            "  function fetchSave(index) {",
            "    fetch(API + 'get?clientSessionId=' + id, { headers: headers(index) }).then(function(response) {",
            "      return response.text().then(function(text) {",
            "        if (response.status === 401 && index + 1 < list.length) { fetchSave(index + 1); return; }",
            "        saveSyncHost.onOnline(response.status, response.ok ? text : '');",
            "      });",
            "    }).catch(function(e) { saveSyncHost.onOnline(-1, problem(e)); });",
            "  }",
            "  function sendSave(index, save) {",
            "    fetch(API + 'get?clientSessionId=' + id, { headers: headers(index) }).then(function(got) {",
            "      if (got.status === 401 && index + 1 < list.length) { sendSave(index + 1, save); return; }",
            "      if (!got.ok) {",
            "        return got.text().then(function(text) { saveSyncHost.onUploaded(got.status, text.trim().slice(0, 200)); });",
            "      }",
            "      return fetch(API + 'update?clientSessionId=' + id, {",
            "        method: 'POST', headers: headers(index), body: save",
            "      }).then(function(put) {",
            "        return put.text().then(function(text) { saveSyncHost.onUploaded(put.status, text.trim().slice(0, 200)); });",
            "      });",
            "    }).catch(function(e) { saveSyncHost.onUploaded(-1, problem(e)); });",
            "  }",
            "  if (location.search === '?upload') {",
            "    window.__online = {",
            "      upload: function(save) {",
            "        if (!list.length) { saveSyncHost.onUploaded(0, ''); return; }",
            "        sendSave(0, save);",
            "      }",
            "    };",
            "    saveSyncHost.onUploadReady();",
            "    return;",
            "  }",
            "  if (!list.length) { saveSyncHost.onOnline(0, ''); return; }",
            "  fetchSave(0);",
            "})();",
            "</script>");

    /**
     * Hidden helper page, served on the offline game's origin so it shares that
     * game's localStorage. The offline game keeps its save under "data_Guest" as
     * btoa(encodeURIComponent(json)). The two asset scripts add the starter list.
     */
    static final String HOST_HTML = String.join("\n",
            "<!doctype html><meta charset=\"utf-8\">",
            "<script src=\"assets/tables.js\"></script>",
            "<script src=\"assets/starters.js\"></script>",
            "<script>",
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
            "        playTime: Number(d.gameStats && d.gameStats.playTime) || 0,",
            "        starters: window.__starterTools ? window.__starterTools.count(text) : -1",
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
            "    },",
            "    readLocal: function() {",
            "      var text = readLocal();",
            "      try { JSON.parse(text); } catch (e) { text = ''; }",
            "      saveSyncHost.onLocalRaw(text || '');",
            "    },",
            "    starters: function(which) {",
            "      try {",
            "        if (!window.__starterTools) { throw new Error('the name tables did not load'); }",
            "        var text = which === 'online' ? online : readLocal();",
            "        if (!text) { throw new Error('there is no save to read'); }",
            "        var list = window.__starterTools.format(text, which === 'online' ? 'online save' : 'offline save');",
            "        saveSyncHost.onStarters(window.__starterTools.count(text), list);",
            "      } catch (e) { saveSyncHost.onStarters(-1, String((e && e.message) || e)); }",
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
