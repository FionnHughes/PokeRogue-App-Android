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
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Save tools for the Modern app, opened from three entries in the drawer's Tools list.
 *
 * "Sync saves" compares the player's online and offline saves and run histories and
 * can copy either way: both, the save data only, or the run history only.
 * Copying save data offline to online is the same request the game makes when it
 * saves, so the official server applies its own checks and may refuse it.
 * Run history never touches the server: the game keeps it in the browser only, so
 * "online run history" is what Online mode in this app has recorded. Copying it
 * merges the two lists and keeps the newest runs.
 *
 * "Copy Pokémon caught" puts a text list of the Pokémon usable as starters on the
 * clipboard.
 *
 * "Restore backup" lists the backups. Every copy and every restore first stores
 * what it is about to replace (see {@link Backups}), and any backup can be put
 * back into the offline game or sent to the online one.
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
    private static final int ENTRY_RESTORE = 2;

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
    /** The passphrase the game itself uses for what it stores in the browser (its src/constants.ts). */
    private static final String GAME_STORAGE_KEY = "x0i2O7WRiANTqPmZ";

    /** What a copy includes. The values are passed to the helper page as they are. */
    private static final String SCOPE_BOTH = "both";
    private static final String SCOPE_SAVE = "save";
    private static final String SCOPE_HISTORY = "history";

    private static final String NEWER_OFFLINE_WARNING =
            "Your offline save is newer.\n\n"
            + "This replaces it with the older online save. The progress made offline since then is lost.";
    private static final String UPLOAD_WARNING =
            "Not recommended.\n\n"
            + "This replaces your ONLINE save with the offline one. Anything done online since your last sync is lost,"
            + " and it cannot be undone.\n\n"
            + "PokéRogue does not support moving offline progress online, so this is at your own account's risk.\n\n"
            + "The server only accepts it if the offline save came from this account and has at least as much play time.";

    private static final String RESTORE_ONLINE_WARNING =
            "This replaces your ONLINE save with this backup, at your own account's risk.\n\n"
            + "The server refuses a save with less play time than the one it has, so putting an older"
            + " save back online usually fails. If it refuses, nothing changes.";
    private static final String NOT_LOGGED_IN =
            "You are not logged in online. Open Online mode in this app, log in, then come back.";

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
        if (entry != ENTRY_SYNC && entry != ENTRY_COPY_STARTERS && entry != ENTRY_RESTORE) {
            return;
        }
        current = new Session(activity, entry);
        current.start();
    }

    // ---- One run of a dialog ----

    private static final class Session {
        private final Activity activity;
        private final int entry;
        private final String title;
        /** Where backups are kept. Also read from the helper page's thread, through HostBridge. */
        final File backupDir;
        private WebView host;
        private AlertDialog dialog;
        private boolean fetchDone;
        private boolean compared;
        private boolean uploading;
        private boolean closed;
        private String onlineSave;
        private String onlineProblem;
        /** The account logged in online, "" if unknown. Its name is part of the run history's storage key. */
        private String onlineUser = "";
        /** The online run history as JSON text, "" if there is none. */
        private String onlineHistory = "";
        /** What the comparison said about the online side; goes into a backup's description. */
        private JSONObject onlineSummary;
        private int onlineRuns;
        private String uploadSave;
        private String uploadHistory;
        /** The backup being restored, or null when this session is not a restore. */
        private String restoreId;
        private boolean restoreToOnline;
        /** Shown when the operation in progress finishes. */
        private String doneMessage = "";

        Session(Activity activity, int entry) {
            this.activity = activity;
            this.entry = entry;
            this.title = entry == ENTRY_COPY_STARTERS ? "Copy Pokémon caught"
                    : entry == ENTRY_RESTORE ? "Restore backup" : "Sync saves";
            this.backupDir = new File(activity.getFilesDir(), "savesync-backups");
        }

        void start() {
            if (entry == ENTRY_RESTORE) {
                offerBackups();
                return;
            }
            showDialog("Checking your saves...", null, null, null, null);
            // Step 1: fetch the online save. Step 2 (onOnline) opens the offline page.
            openHost(ONLINE_PAGE);
        }

        /** Creates the hidden WebView and loads one of the two helper pages in it. */
        private void openHost(String firstPage) {
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
            host.loadUrl(firstPage);

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

        /**
         * Result of the online page's request. Status 0 means no login cookie, -1 a network
         * failure. The run history arrives as the game stored it, encrypted.
         */
        void onOnline(int status, String body, String username, String storedHistory) {
            if (closed || fetchDone) {
                return;
            }
            fetchDone = true;
            if (status == 200 && looksLikeSave(body)) {
                onlineSave = body;
            } else {
                onlineProblem = describeFailure(status, body);
            }
            onlineUser = username;
            if (!storedHistory.isEmpty()) {
                try {
                    onlineHistory = CryptoJsAes.decrypt(storedHistory, GAME_STORAGE_KEY);
                } catch (GeneralSecurityException e) {
                    onlineHistory = ""; // unreadable: treated as no history
                }
            }
            host.loadUrl(HOST_PAGE);
        }

        void onHostReady() {
            if (closed || !fetchDone || compared) {
                return;
            }
            compared = true;
            if (restoreId != null && !restoreToOnline) {
                // An empty text means the backup does not include that part.
                host.evaluateJavascript("window.__sync.restore("
                        + JSONObject.quote(Backups.read(backupDir, restoreId, Backups.SAVE)) + ","
                        + JSONObject.quote(Backups.read(backupDir, restoreId, Backups.HISTORY)) + ");", null);
                closeAfter(APPLY_TIMEOUT_MS);
                return;
            }
            String argument = onlineSave == null ? "null" : JSONObject.quote(onlineSave);
            host.evaluateJavascript(
                    "window.__sync.compare(" + argument + "," + JSONObject.quote(onlineHistory) + ");", null);
        }

        void onCompared(String json) {
            if (closed) {
                return;
            }
            JSONObject local;
            JSONObject online;
            JSONObject localHistory;
            JSONObject onlineHistorySummary;
            try {
                JSONObject both = new JSONObject(json);
                local = both.getJSONObject("local");
                online = both.getJSONObject("online");
                localHistory = both.getJSONObject("localHistory");
                onlineHistorySummary = both.getJSONObject("onlineHistory");
            } catch (JSONException e) {
                showDialog("Could not read the saves. Nothing was changed.", null, null, null, null);
                return;
            }
            boolean onlineOk = onlineProblem == null && usable(online);
            boolean localOk = usable(local);
            onlineSummary = onlineOk ? online : null;
            onlineRuns = onlineHistorySummary.optInt("runs");
            if (restoreId != null) {
                sendBackupOnline();
            } else if (entry == ENTRY_COPY_STARTERS) {
                offerStarterLists(local, online, localOk, onlineOk);
            } else {
                offerSync(local, online, localOk, onlineOk, localHistory, onlineHistorySummary);
            }
        }

        // ---- Sync saves ----

        private void offerSync(JSONObject local, JSONObject online, boolean localOk, boolean onlineOk,
                JSONObject localHistory, JSONObject onlineHistorySummary) {
            // Online run history needs the account name, so it is only known when logged in.
            boolean historyKnown = !onlineUser.isEmpty();
            int localRuns = localHistory.optInt("runs");
            int onlineRuns = onlineHistorySummary.optInt("runs");

            StringBuilder message = new StringBuilder();
            message.append("ONLINE\n");
            message.append(onlineProblem != null ? onlineProblem : describe(online));
            if (historyKnown) {
                message.append("\n").append(describeHistory(onlineHistorySummary, "offline"));
            }
            message.append("\n\nOFFLINE\n").append(describe(local));
            message.append("\n").append(describeHistory(localHistory, historyKnown ? "online" : null));
            if (onlineOk) {
                message.append("\n\n").append(verdict(local, online, localOk));
            }
            if (historyKnown) {
                message.append("\n\nOnline run history is only what Online mode in this app has recorded.");
            }
            message.append("\n\nEvery copy first backs up what it replaces. See Restore backup in the drawer.");

            final boolean offlineNewer = onlineOk && localOk
                    && local.optLong("timestamp") > online.optLong("timestamp");
            // Each direction lists only what it can actually copy.
            final String[] downloadScopes = scopes(onlineOk, historyKnown && onlineRuns > 0);
            final String[] uploadScopes = scopes(onlineOk && localOk, historyKnown && localRuns > 0);

            Runnable download = new Runnable() {
                @Override
                public void run() {
                    chooseScope("Copy online to offline", downloadScopes, new ScopeAction() {
                        @Override
                        public void run(final String scope) {
                            final Runnable copy = new Runnable() {
                                @Override
                                public void run() {
                                    doneMessage = "Copied " + scopeName(scope) + " from online to offline";
                                    host.evaluateJavascript("window.__sync.apply('" + scope + "');", null);
                                    closeAfter(APPLY_TIMEOUT_MS);
                                }
                            };
                            if (offlineNewer && !SCOPE_HISTORY.equals(scope)) {
                                showDialog(NEWER_OFFLINE_WARNING, "Replace offline save", copy, null, null);
                            } else {
                                copy.run();
                            }
                        }
                    });
                }
            };
            Runnable upload = new Runnable() {
                @Override
                public void run() {
                    chooseScope("Copy offline to online", uploadScopes, new ScopeAction() {
                        @Override
                        public void run(final String scope) {
                            if (SCOPE_HISTORY.equals(scope)) {
                                startUpload(scope); // stays in this app's browser storage: nothing to warn about
                                return;
                            }
                            showDialog(UPLOAD_WARNING, "Replace online save", new Runnable() {
                                @Override
                                public void run() {
                                    startUpload(scope);
                                }
                            }, null, null);
                        }
                    });
                }
            };
            showDialog(message.toString(),
                    downloadScopes.length > 0 ? "Copy online to offline" : null, download,
                    uploadScopes.length > 0 ? "Copy offline to online" : null, upload);
        }

        void onApplied(boolean ok, String problem) {
            if (closed) {
                return;
            }
            if (!ok) {
                showDialog("Could not store the copy. Nothing was changed.\n" + problem, null, null, null, null);
                return;
            }
            Toast.makeText(activity, doneMessage, Toast.LENGTH_LONG).show();
            reloadGameOn(OFFLINE_HOST); // a running offline game still holds the old data in memory
            close();
        }

        /** Upload, step 1: read the offline side. Steps 2 and 3 follow in onLocalRaw and onUploadReady. */
        private void startUpload(String scope) {
            beginUpload("Copying offline to online...",
                    "Copied " + scopeName(scope) + " from offline to online");
            host.evaluateJavascript("window.__sync.prepareUpload('" + scope + "');", null);
        }

        private void beginUpload(String progress, String whenDone) {
            uploading = true;
            doneMessage = whenDone;
            showDialog(progress, null, null, null, null);
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

        /**
         * Upload, step 2. Either text is "" when the chosen scope leaves it out. The run
         * history arrives already merged with the online one.
         */
        void onLocalRaw(boolean ok, String save, String mergedHistory) {
            if (closed || !uploading) {
                return;
            }
            if (!ok) {
                uploading = false;
                showDialog("The offline data could not be read. Nothing was changed.", null, null, null, null);
                return;
            }
            sendOnline(save, mergedHistory, "before copying offline to online");
        }

        /** Restore to online: the backup takes the place of the offline data in an upload. */
        private void sendBackupOnline() {
            String save = Backups.read(backupDir, restoreId, Backups.SAVE);
            String history = Backups.read(backupDir, restoreId, Backups.HISTORY);
            if (onlineUser.isEmpty() || (!save.isEmpty() && onlineProblem != null)) {
                showDialog(onlineProblem != null ? onlineProblem : NOT_LOGGED_IN, null, null, null, null);
                return;
            }
            beginUpload("Restoring the backup to online...", "Backup restored to online");
            sendOnline(save, history, "before restoring a backup to online");
        }

        /**
         * Backs up the online side, then opens the upload page. Either text is "" for
         * "leave that alone". The run history is stored encrypted, the way the online
         * game keeps it.
         */
        private void sendOnline(String save, String history, String backupReason) {
            String storedHistory = "";
            boolean ok = backUpOnline(!save.isEmpty(), !history.isEmpty(), backupReason);
            if (ok && !history.isEmpty()) {
                try {
                    storedHistory = CryptoJsAes.encrypt(history, GAME_STORAGE_KEY);
                } catch (GeneralSecurityException e) {
                    ok = false;
                }
            }
            if (!ok) {
                uploading = false;
                showDialog("A backup of the online side could not be stored first. Nothing was changed.",
                        null, null, null, null);
                return;
            }
            uploadSave = save;
            uploadHistory = storedHistory;
            host.loadUrl(UPLOAD_PAGE);
        }

        /** Stores the online save and/or run history as they are now. True if there was nothing to keep. */
        private boolean backUpOnline(boolean save, boolean history, String reason) {
            String keptSave = save && onlineSave != null ? onlineSave : "";
            String keptHistory = history && onlineRuns > 0 ? onlineHistory : "";
            if (keptSave.isEmpty() && keptHistory.isEmpty()) {
                return true;
            }
            JSONObject meta = new JSONObject();
            try {
                meta.put("created", System.currentTimeMillis());
                meta.put("origin", "online");
                meta.put("reason", reason);
                if (!keptSave.isEmpty() && onlineSummary != null) {
                    meta.put("save", onlineSummary);
                }
                meta.put("runs", keptHistory.isEmpty() ? 0 : onlineRuns);
            } catch (JSONException e) {
                return false;
            }
            return Backups.write(backupDir, "online", meta.toString(), keptSave, keptHistory) != null;
        }

        void onUploadReady() {
            if (closed || !uploading || uploadSave == null) {
                return;
            }
            host.evaluateJavascript("window.__online.upload(" + JSONObject.quote(uploadSave) + ","
                    + JSONObject.quote(onlineUser) + "," + JSONObject.quote(uploadHistory) + ");", null);
        }

        void onUploaded(int status, String body) {
            if (closed || !uploading) {
                return;
            }
            uploading = false;
            if (status >= 200 && status < 300) {
                Toast.makeText(activity, doneMessage, Toast.LENGTH_LONG).show();
                reloadGameOn(ONLINE_HOST); // a running online game still holds the old data in memory
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

        // ---- Restore backup ----

        private void offerBackups() {
            final List<String> ids = Backups.ids(backupDir);
            if (ids.isEmpty()) {
                showDialog("No backups yet.\n\nOne is made automatically before a copy or a restore replaces"
                        + " anything.", null, null, null, null);
                return;
            }
            String[] labels = new String[ids.size()];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = backupLabel(ids.get(i), metaOf(ids.get(i)));
            }
            chooseItem("Restore backup", labels, new ItemAction() {
                @Override
                public void run(int index) {
                    offerBackup(ids.get(index));
                }
            });
        }

        private void offerBackup(final String id) {
            JSONObject meta = metaOf(id);
            JSONObject save = meta.optJSONObject("save");
            int runs = meta.optInt("runs");
            String message = "Taken: " + formatTime(Backups.timeOf(id))
                    + "\nFrom: " + meta.optString("origin", "unknown") + ", " + meta.optString("reason", "")
                    + "\n\nSAVE DATA\n" + (save != null ? describe(save) : "Not in this backup.")
                    + "\n\nRUN HISTORY\n" + (runs > 0 ? runs + (runs == 1 ? " run" : " runs") : "Not in this backup.")
                    + "\n\nRestoring replaces only the parts this backup has, and backs those up first.";
            final boolean hasSave = save != null;
            Runnable toOffline = new Runnable() {
                @Override
                public void run() {
                    restoreId = id;
                    restoreToOnline = false;
                    doneMessage = "Backup restored to offline";
                    showDialog("Restoring the backup to offline...", null, null, null, null);
                    fetchDone = true; // nothing to fetch from online for this
                    openHost(HOST_PAGE);
                }
            };
            final Runnable startOnline = new Runnable() {
                @Override
                public void run() {
                    restoreId = id;
                    restoreToOnline = true;
                    showDialog("Checking your saves...", null, null, null, null);
                    openHost(ONLINE_PAGE);
                }
            };
            Runnable toOnline = !hasSave ? startOnline : new Runnable() {
                @Override
                public void run() {
                    showDialog(RESTORE_ONLINE_WARNING, "Replace online save", startOnline, null, null);
                }
            };
            showDialog(message, "Restore to offline", toOffline, "Restore to online", toOnline);
        }

        private JSONObject metaOf(String id) {
            try {
                return new JSONObject(Backups.read(backupDir, id, Backups.META));
            } catch (JSONException e) {
                return new JSONObject();
            }
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

        /** Asks what a copy should include. */
        private void chooseScope(String heading, final String[] scopes, final ScopeAction action) {
            String[] labels = new String[scopes.length];
            for (int i = 0; i < scopes.length; i++) {
                String name = scopeName(scopes[i]);
                labels[i] = Character.toUpperCase(name.charAt(0)) + name.substring(1)
                        + (SCOPE_BOTH.equals(scopes[i]) ? "" : " only");
            }
            chooseItem(heading, labels, new ItemAction() {
                @Override
                public void run(int index) {
                    action.run(scopes[index]);
                }
            });
        }

        /**
         * Shows a list to pick from. Follows the same rule as showDialog: picking an entry
         * keeps the session open, closing the list any other way ends it.
         */
        private void chooseItem(String heading, String[] labels, final ItemAction action) {
            if (closed || activity.isFinishing()) {
                close();
                return;
            }
            final AlertDialog previous = dialog;
            final boolean[] acted = {false};
            final AlertDialog next = new AlertDialog.Builder(activity)
                    .setTitle(heading)
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            acted[0] = true;
                            action.run(which);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .create();
            next.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
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

    private interface ScopeAction {
        void run(String scope);
    }

    private interface ItemAction {
        void run(int index);
    }

    private static String scopeName(String scope) {
        return SCOPE_BOTH.equals(scope) ? "save data and run history"
                : SCOPE_SAVE.equals(scope) ? "save data" : "run history";
    }

    /** The scopes a direction can offer, given whether it has save data and run history to copy. */
    private static String[] scopes(boolean save, boolean history) {
        if (save && history) {
            return new String[] {SCOPE_BOTH, SCOPE_SAVE, SCOPE_HISTORY};
        }
        if (save) {
            return new String[] {SCOPE_SAVE};
        }
        return history ? new String[] {SCOPE_HISTORY} : new String[0];
    }

    /** Receives results from the hidden helper pages. Calls arrive on a WebView thread. */
    private static final class HostBridge {
        private final Session session;

        HostBridge(Session session) {
            this.session = session;
        }

        /**
         * Stores a backup of the offline side and answers whether it worked. Unlike the
         * other calls this one runs to completion on the page's thread: the page waits
         * for the answer and only then replaces anything.
         */
        @JavascriptInterface
        public boolean backup(String meta, String save, String history) {
            return Backups.write(session.backupDir, "offline", meta, save, history) != null;
        }

        @JavascriptInterface
        public void onOnline(final int status, final String body, final String username,
                final String storedHistory) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onOnline(status, body, username, storedHistory);
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
        public void onLocalRaw(final boolean ok, final String save, final String mergedHistory) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    session.onLocalRaw(ok, save, mergedHistory);
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
        String when = saved > 0 ? formatTime(saved) : "unknown";
        long hours = Math.round(summary.optDouble("playTime", 0) / 3600.0);
        return "Last saved: " + when
                + "\nSpecies caught: " + summary.optInt("caught")
                + "\nPlay time: about " + hours + " h";
    }

    private static String formatTime(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(millis));
    }

    /** One line for the restore list: when, which side, and what the backup holds. */
    private static String backupLabel(String id, JSONObject meta) {
        int runs = meta.optInt("runs");
        String holds = meta.optJSONObject("save") != null ? "save data" : "";
        if (runs > 0) {
            holds += (holds.isEmpty() ? "" : " + ") + runs + (runs == 1 ? " run" : " runs");
        }
        return formatTime(Backups.timeOf(id)) + "\n" + meta.optString("origin", "unknown")
                + (holds.isEmpty() ? "" : ": " + holds);
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

    /** One line about a run history. `other` names the side it is compared with, or null for no comparison. */
    private static String describeHistory(JSONObject summary, String other) {
        int runs = summary.optInt("runs");
        if (runs == 0) {
            return "Run history: none";
        }
        String line = "Run history: " + runs + (runs == 1 ? " run" : " runs");
        int extra = summary.optInt("notInOther");
        if (other != null && extra > 0) {
            line += " (" + extra + " not in " + other + ")";
        }
        return line;
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
                return NOT_LOGGED_IN;
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
        if (status == -2) {
            return "The run history could not be stored for the online game (" + detail
                    + "). Save data, if it was part of the copy, did go through.";
        }
        String reason;
        if (status == 0) {
            reason = NOT_LOGGED_IN;
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
     * fresh client session id. Loaded plain it fetches the save, the account name and
     * that account's stored run history. Loaded with "?upload" it waits for data to
     * send: a save is posted to the server (after a fetch, because the server only
     * takes an update from the session that last fetched), and a run history is
     * written to this origin's localStorage, where the online game keeps it.
     */
    static final String ONLINE_HTML = String.join("\n",
            "<!doctype html><meta charset=\"utf-8\"><script>",
            "(function() {",
            "  var ROOT = 'https://api.pokerogue.net/';",
            "  var API = ROOT + 'savedata/system/';",
            "  var HISTORY_PREFIX = 'runHistoryData_';",
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
            "  // The account name is part of the run history's storage key. If the server",
            "  // does not say who is logged in, a single stored history is taken to be theirs.",
            "  function onlyStoredUser() {",
            "    var names = [];",
            "    for (var i = 0; i < localStorage.length; i++) {",
            "      var key = localStorage.key(i);",
            "      if (key.indexOf(HISTORY_PREFIX) === 0 && key !== HISTORY_PREFIX + 'Guest') { names.push(key.slice(HISTORY_PREFIX.length)); }",
            "    }",
            "    return names.length === 1 ? names[0] : '';",
            "  }",
            "  function whoAmI(index) {",
            "    return fetch(ROOT + 'account/info', { headers: headers(index) })",
            "      .then(function(response) { return response.ok ? response.json() : null; })",
            "      .then(function(info) { return (info && info.username) || onlyStoredUser(); })",
            "      .catch(function() { return onlyStoredUser(); });",
            "  }",
            "  function report(status, text, username) {",
            "    var history = '';",
            "    try { if (username) { history = localStorage.getItem(HISTORY_PREFIX + username) || ''; } } catch (e) {}",
            "    saveSyncHost.onOnline(status, text, username || '', history);",
            "  }",
            "  function fetchSave(index) {",
            "    fetch(API + 'get?clientSessionId=' + id, { headers: headers(index) }).then(function(response) {",
            "      return response.text().then(function(text) {",
            "        if (response.status === 401 && index + 1 < list.length) { fetchSave(index + 1); return; }",
            "        if (response.status === 401) { report(401, '', ''); return; }",
            "        return whoAmI(index).then(function(username) { report(response.status, response.ok ? text : '', username); });",
            "      });",
            "    }).catch(function(e) { report(-1, problem(e), ''); });",
            "  }",
            "  function sendSave(index, save, afterwards) {",
            "    fetch(API + 'get?clientSessionId=' + id, { headers: headers(index) }).then(function(got) {",
            "      if (got.status === 401 && index + 1 < list.length) { sendSave(index + 1, save, afterwards); return; }",
            "      if (!got.ok) {",
            "        return got.text().then(function(text) { saveSyncHost.onUploaded(got.status, text.trim().slice(0, 200)); });",
            "      }",
            "      return fetch(API + 'update?clientSessionId=' + id, {",
            "        method: 'POST', headers: headers(index), body: save",
            "      }).then(function(put) {",
            "        return put.text().then(function(text) {",
            "          if (put.ok) {",
            "            try { afterwards(); } catch (e) { saveSyncHost.onUploaded(-2, problem(e)); return; }",
            "          }",
            "          saveSyncHost.onUploaded(put.status, text.trim().slice(0, 200));",
            "        });",
            "      });",
            "    }).catch(function(e) { saveSyncHost.onUploaded(-1, problem(e)); });",
            "  }",
            "  if (location.search === '?upload') {",
            "    window.__online = {",
            "      // save: text to post, or '' for none. storedHistory: run history as the game",
            "      // stores it, or '' for none; it is only written once the save has gone through.",
            "      upload: function(save, username, storedHistory) {",
            "        function writeHistory() {",
            "          if (storedHistory && username) { localStorage.setItem(HISTORY_PREFIX + username, storedHistory); }",
            "        }",
            "        if (!save) {",
            "          try { writeHistory(); saveSyncHost.onUploaded(204, ''); }",
            "          catch (e) { saveSyncHost.onUploaded(-2, problem(e)); }",
            "          return;",
            "        }",
            "        if (!list.length) { saveSyncHost.onUploaded(0, ''); return; }",
            "        sendSave(0, save, writeHistory);",
            "      }",
            "    };",
            "    saveSyncHost.onUploadReady();",
            "    return;",
            "  }",
            "  if (!list.length) { report(0, '', ''); return; }",
            "  fetchSave(0);",
            "})();",
            "</script>");

    /**
     * Hidden helper page, served on the offline game's origin so it shares that
     * game's localStorage. The offline game keeps its save under "data_Guest" and its
     * run history under "runHistoryData_Guest", both as btoa(encodeURIComponent(json)).
     * The two asset scripts add the starter list.
     */
    static final String HOST_HTML = String.join("\n",
            "<!doctype html><meta charset=\"utf-8\">",
            "<script src=\"assets/tables.js\"></script>",
            "<script src=\"assets/starters.js\"></script>",
            "<script>",
            "(function() {",
            "  var KEY = 'data_Guest';",
            "  var HISTORY_KEY = 'runHistoryData_Guest';",
            "  var HISTORY_LIMIT = 25; // the game's own cap on stored runs",
            "  var online = null;",
            "  var onlineHistory = '';",
            "  function read(key) {",
            "    var v = localStorage.getItem(key);",
            "    return v ? decodeURIComponent(atob(v)) : null;",
            "  }",
            "  function store(key, text) { localStorage.setItem(key, btoa(encodeURIComponent(text))); }",
            "  function readLocal() {",
            "    try { return read(KEY); } catch (e) { return '{broken'; }",
            "  }",
            "  function readLocalHistory() {",
            "    try { return read(HISTORY_KEY) || ''; } catch (e) { return ''; }",
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
            "  // A run history is an object of runs keyed by the time each run ended.",
            "  function runs(text) {",
            "    try {",
            "      var parsed = text ? JSON.parse(text) : {};",
            "      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};",
            "    } catch (e) { return {}; }",
            "  }",
            "  function summarizeHistory(text, otherText) {",
            "    var mine = runs(text);",
            "    var other = runs(otherText);",
            "    var keys = Object.keys(mine);",
            "    return {",
            "      runs: keys.length,",
            "      notInOther: keys.filter(function(k) { return !(k in other); }).length",
            "    };",
            "  }",
            "  // Adds the source's runs to the target's and keeps the newest ones.",
            "  function mergeHistory(sourceText, targetText) {",
            "    var merged = runs(targetText);",
            "    var source = runs(sourceText);",
            "    Object.keys(source).forEach(function(k) { merged[k] = source[k]; });",
            "    var kept = {};",
            "    Object.keys(merged)",
            "      .sort(function(a, b) { return Number(b) - Number(a); })",
            "      .slice(0, HISTORY_LIMIT)",
            "      .forEach(function(k) { kept[k] = merged[k]; });",
            "    return JSON.stringify(kept);",
            "  }",
            "  // Keeps what is about to be replaced. Answers false if it could not be stored,",
            "  // in which case nothing may be replaced. Nothing to keep counts as success.",
            "  function backUp(reason, save, history) {",
            "    var runCount = Object.keys(runs(history)).length;",
            "    var keptSave = save && save !== '{broken' ? save : '';",
            "    var keptHistory = runCount ? history : '';",
            "    if (!keptSave && !keptHistory) { return true; }",
            "    var meta = JSON.stringify({",
            "      created: Date.now(), origin: 'offline', reason: reason,",
            "      save: keptSave ? summarize(keptSave) : null, runs: runCount",
            "    });",
            "    return saveSyncHost.backup(meta, keptSave, keptHistory) === true;",
            "  }",
            "  window.__sync = {",
            "    compare: function(onlineText, onlineHistoryText) {",
            "      online = onlineText;",
            "      onlineHistory = onlineHistoryText || '';",
            "      var localHistory = readLocalHistory();",
            "      saveSyncHost.onCompared(JSON.stringify({",
            "        local: summarize(readLocal()),",
            "        online: summarize(onlineText),",
            "        localHistory: summarizeHistory(localHistory, onlineHistory),",
            "        onlineHistory: summarizeHistory(onlineHistory, localHistory)",
            "      }));",
            "    },",
            "    // scope: 'both', 'save' or 'history'. Online to offline.",
            "    apply: function(scope) {",
            "      try {",
            "        var withSave = scope !== 'history';",
            "        var withHistory = scope !== 'save';",
            "        if (withSave) {",
            "          if (!online) { throw new Error('no online save loaded'); }",
            "          JSON.parse(online);",
            "        }",
            "        if (!backUp('before copying online to offline', withSave ? readLocal() : '', withHistory ? readLocalHistory() : '')) {",
            "          throw new Error('a backup could not be stored first');",
            "        }",
            "        if (withSave) { store(KEY, online); }",
            "        if (withHistory) { store(HISTORY_KEY, mergeHistory(onlineHistory, readLocalHistory())); }",
            "        saveSyncHost.onApplied(true, '');",
            "      } catch (e) { saveSyncHost.onApplied(false, String((e && e.message) || e)); }",
            "    },",
            "    // Puts a backup back exactly as it was. '' means the backup has no such part.",
            "    restore: function(save, history) {",
            "      try {",
            "        if (save) { JSON.parse(save); }",
            "        if (history) { JSON.parse(history); }",
            "        if (!save && !history) { throw new Error('the backup is empty'); }",
            "        if (!backUp('before restoring a backup', save ? readLocal() : '', history ? readLocalHistory() : '')) {",
            "          throw new Error('a backup could not be stored first');",
            "        }",
            "        if (save) { store(KEY, save); }",
            "        if (history) { store(HISTORY_KEY, history); }",
            "        saveSyncHost.onApplied(true, '');",
            "      } catch (e) { saveSyncHost.onApplied(false, String((e && e.message) || e)); }",
            "    },",
            "    // Offline to online: hands over what the scope includes, '' for what it leaves out.",
            "    prepareUpload: function(scope) {",
            "      try {",
            "        var save = '';",
            "        if (scope !== 'history') {",
            "          save = readLocal();",
            "          if (!save) { throw new Error('there is no offline save'); }",
            "          JSON.parse(save);",
            "        }",
            "        var history = scope !== 'save' ? mergeHistory(readLocalHistory(), onlineHistory) : '';",
            "        saveSyncHost.onLocalRaw(true, save, history);",
            "      } catch (e) { saveSyncHost.onLocalRaw(false, '', ''); }",
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
