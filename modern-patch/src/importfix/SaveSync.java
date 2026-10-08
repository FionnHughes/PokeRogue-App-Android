package importfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.RoundedCorner;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Save tools for the Modern app, opened from three entries in the drawer's Tools list.
 *
 * "Sync saves" compares the online and offline sides part by part: the save data, the
 * run history, and the five slots for runs in progress. It offers a recommended sync,
 * which moves each part from the side that is ahead, and manual copies in either
 * direction. Sending save data or a run in progress online is the same request the
 * game makes when it saves, so the official server applies its own checks and may
 * refuse. Run history never touches the server: the game keeps it in the browser
 * only, so "online run history" is what Online mode in this app has recorded.
 *
 * The same comparison runs by itself, without showing anything, each time a game
 * starts. If the other side is ahead of the game being started, it says so and
 * offers to sync first.
 *
 * The first time the app opens, and until told otherwise, a few pages of tips
 * explain when to sync, how to get the offline game, and what the drawer's tools are.
 *
 * "Screen layout" opens an editor on top of the running game: a box that says where
 * on the screen the game is drawn, one for the phone held upright and one for
 * sideways (assets/savesync/layout.js). Until one is chosen, the upright game starts
 * a little below the top, clear of rounded screen corners.
 *
 * Run history is kept by each browser and never reaches the game's server. With a
 * code set under "Shared run history", every comparison first exchanges finished
 * runs with a small store on the owner's server ({@link HistoryHub}), which a script
 * in the desktop browser does too. A run finished on another device then shows up
 * here, and its leftover copy on this phone is recognised as ended.
 *
 * "Copy Pokémon caught" puts a text list of the Pokémon usable as starters on the
 * clipboard.
 *
 * "Restore backup" lists the backups. Every copy and every restore first stores
 * what it is about to replace (see {@link Backups}), and any backup can be put
 * back into the offline game or sent to the online one.
 *
 * Both sides are reached through a hidden WebView, one small page per origin:
 * a page on the official site's origin talks to the official server exactly as the
 * game does (same login cookie, same requests), and a page on the offline game's
 * origin reads and writes that game's localStorage. The pages' scripts are the
 * files in assets/savesync. Each step is written to a log that the dialogs show.
 *
 * The Modern app has no public source, so this class is compiled separately and
 * added to the APK; a few one-line hooks in the app's own code call into it.
 */
public final class SaveSync {
    /** Drawer entries, as the title ids the patch script gives them. */
    private static final int ENTRY_SYNC = 0;
    private static final int ENTRY_COPY_STARTERS = 1;
    private static final int ENTRY_RESTORE = 2;
    private static final int ENTRY_LAYOUT = 3;
    /** Not a drawer entry: the tips shown when the app opens. */
    private static final int ENTRY_TIPS = 100;

    /** The game has five slots for runs in progress. */
    private static final int SLOTS = 5;

    private static final String API_HOST = "api.pokerogue.net";
    private static final String ONLINE_HOST = "pokerogue.net";
    private static final String OFFLINE_HOST = "localhost";
    private static final String ONLINE_PAGE = "https://pokerogue.net/__savesync/online.html";
    private static final String UPLOAD_PAGE = ONLINE_PAGE + "?upload";
    private static final String OFFLINE_PAGE = "https://localhost:8080/__savesync/index.html";
    /** Scripts shipped in the APK's assets/savesync folder, served to the helper pages under this path. */
    private static final String ASSET_PATH = "/__savesync/assets/";
    private static final String[] ASSETS = {"tables.js", "starters.js", "offline.js", "online.js"};
    private static final String ONLINE_HTML = "<!doctype html><meta charset=\"utf-8\">"
            + "<script src=\"" + ASSET_PATH + "online.js\"></script>";
    private static final String OFFLINE_HTML = "<!doctype html><meta charset=\"utf-8\">"
            + "<script src=\"" + ASSET_PATH + "tables.js\"></script>"
            + "<script src=\"" + ASSET_PATH + "starters.js\"></script>"
            + "<script src=\"" + ASSET_PATH + "offline.js\"></script>";

    /** How long each step may take before the session stops waiting and shows the log. */
    private static final long FETCH_TIMEOUT_MS = 45000;
    private static final long PAGE_TIMEOUT_MS = 15000;
    private static final long UPLOAD_TIMEOUT_MS = 90000;
    /** The shared run history is an extra: if its store does not answer in this time, the sync goes on without it. */
    private static final long SHARING_TIMEOUT_MS = 20000;
    private static final int LOG_LIMIT = 6000;

    /** The passphrase the game itself uses for what it stores in the browser (its src/constants.ts). */
    private static final String GAME_STORAGE_KEY = "x0i2O7WRiANTqPmZ";

    /** Directions a part can move in a plan. The helper page uses the same words. */
    private static final String DOWN = "down"; // online to offline
    private static final String UP = "up"; // offline to online
    private static final String BOTH = "both"; // run history only: merge into both sides
    /** A slot only: remove the run there, because it has already ended on the other side. */
    private static final String END_OFFLINE = "endDown";
    private static final String END_ONLINE = "endUp";

    /** What a session is waiting for. Answers that arrive in any other step are ignored. */
    private static final int IDLE = 0;
    private static final int FETCHING = 1;
    private static final int LOADING_OFFLINE = 2;
    private static final int COMPARING = 3;
    private static final int EXECUTING = 4;
    private static final int LOADING_UPLOAD = 5;
    private static final int UPLOADING = 6;
    private static final int LISTING = 7;
    private static final int SHARING = 8;

    private static final String UPLOAD_NOTE =
            "Sending save data or a run in progress ONLINE is not something PokéRogue supports, so it is at"
            + " your own account's risk. The server applies its own checks and may refuse.";
    private static final String RESTORE_ONLINE_WARNING =
            "This replaces what your ONLINE account has with this backup, at your own account's risk.\n\n"
            + "The server refuses save data with less play time than it already has, so putting an older"
            + " save back online usually fails. If it refuses, nothing changes.";
    private static final String NOT_LOGGED_IN =
            "You are not logged in online. Open Online mode in this app, log in, then come back.";
    private static final String BACKUP_NOTE =
            "Whatever is replaced is backed up first. See Restore backup in the drawer.";

    private static final String PREFERENCES = "savesync";
    private static final String TIPS_HIDDEN = "tips_hidden";
    /** Lets the app's first screen appear before the tips do. */
    private static final long TIPS_DELAY_MS = 1200;
    /** The gap above the game when the screen does not report its corner radius, in dp. */
    private static final int DEFAULT_TOP_GAP_DP = 24;

    /** The tips, one page each. The names are the ones the app itself shows. */
    private static final String[] TIPS = {
        "Online and offline keep separate saves.\n\n"
                + "Before you switch from one to the other, sync first: open the side menu, tap Sync saves,"
                + " then Recommended sync.\n\n"
                + "If you skip it, the game you switch to carries on from its older data. The app also checks"
                + " when a game starts and warns you if the other side is ahead.",
        "Offline needs the game files on your phone.\n\n"
                + "On the home screen, open the three-dot menu and tap Update offline files. Then turn on"
                + " Enable offline and launch the game.\n\n"
                + "Do that again whenever PokéRogue updates. If online is on a newer version than your offline"
                + " files, syncing can fail.",
        "The side menu has tools that open without leaving the app:\n\n"
                + "Type Chart: which types beat which.\n"
                + "Type Calculator: weaknesses and resistances of a type combination.\n"
                + "Pokedex: PokéRogue's Pokémon with their moves, abilities and egg moves.\n"
                + "Team Builder: a team's type coverage.\n"
                + "Smogon: competitive sets and write-ups.\n"
                + "PokéRogue Wiki: how the game's systems work.\n\n"
                + "Added by this build:\n"
                + "Copy Pokémon caught: your starters as text on the clipboard.\n"
                + "Sync saves: moves progress between online and offline.\n"
                + "Restore backup: puts back what a sync replaced.\n"
                + "Screen layout: where the game sits on the screen, upright and sideways. Open it while a game is running.",
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> activityRef = new WeakReference<Activity>(null);
    private static WeakReference<WebView> gameRef = new WeakReference<WebView>(null);
    private static Session current;
    /** The client session id the online game last used with the server, "" if none was seen. */
    private static volatile String gameClientId = "";
    /** Whether the check at a game's start has run for the current game WebView. Main thread only. */
    private static boolean checkedOffline;
    private static boolean checkedOnline;
    /** The tips are offered once per run of the app. Main thread only. */
    private static boolean tipsOffered;
    /** The text of assets/savesync/layout.js, read once. */
    private static String layoutScript;
    private static final String LAYOUT = "layout";
    private static final String HISTORY_CODE = "history_code";
    /** The account last read online: the store's backups are listed under it when nothing else is known. */
    private static final String LAST_USER = "last_online_user";
    /** Per game side and account, the last settings exchange: {"updated", "hash"}. */
    private static final String SETTINGS_LAST = "settings_last_";
    /** Per game side and account, a fingerprint of what was last backed up to the store. */
    private static final String STORE_BACKUP = "store_backup_";
    private static final int MIN_HISTORY_CODE_CHARS = 12;
    private static final int MAX_LAYOUT_CHARS = 1000;

    private SaveSync() {
    }

    // ---- Hooks called from the app's own code ----

    /** Hook: MainActivity.onCreate. */
    public static void setActivity(Activity activity) {
        activityRef = new WeakReference<Activity>(activity);
        if (!tipsOffered) {
            tipsOffered = true;
            MAIN.postDelayed(SaveSync::offerTips, TIPS_DELAY_MS);
            Updater.check(activity); // in the background; it only speaks up if there is a newer build
        }
    }

    private static void offerTips() {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || current != null) {
            return;
        }
        if (preferences(activity).getBoolean(TIPS_HIDDEN, false)) {
            return;
        }
        current = new Session(activity, ENTRY_TIPS);
        current.start();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Hook: where the app configures the game WebView, before it loads anything. */
    public static void attach(WebView gameWebView) {
        gameRef = new WeakReference<WebView>(gameWebView);
        gameWebView.addJavascriptInterface(
                new GameBridge(gameWebView.getContext().getApplicationContext()), "saveSyncGame");
        checkedOffline = false;
        checkedOnline = false;
    }

    /** Hook: the game WebView's onPageFinished. */
    public static void onPageFinished(WebView view, String url) {
        String host = hostOf(url);
        if (host.equals(OFFLINE_HOST)) {
            view.evaluateJavascript(IMPORT_LOG_JS, null);
            applyLayout(view);
            checkAtStart(OFFLINE_HOST);
        } else if (host.equals(ONLINE_HOST)) {
            view.evaluateJavascript(CLIENT_ID_JS, null);
            applyLayout(view);
        }
    }

    /**
     * Runs layout.js in the game's page with the stored layout, if there is one, and
     * the top gap to use until there is: sized from the screen's corner radius.
     */
    private static void applyLayout(WebView view) {
        try {
            if (layoutScript == null) {
                layoutScript = readAsset(view.getContext(), "savesync/layout.js");
            }
            int[] position = new int[2];
            view.getLocationInWindow(position);
            float radius = 0;
            WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) {
                if (Build.VERSION.SDK_INT >= 31) {
                    RoundedCorner left = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
                    RoundedCorner right = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
                    radius = Math.max(left == null ? 0 : left.getRadius(), right == null ? 0 : right.getRadius());
                }
            }
            // Something right at the game's edge is clear of a round corner a little over
            // half the corner's radius down. The part of the corner above the view does not count.
            int gap = Math.round(Math.max(0, radius - position[1]) * 0.6f);
            if (gap <= 0) {
                gap = Math.round(DEFAULT_TOP_GAP_DP * view.getResources().getDisplayMetrics().density);
            }
            String stored = "null";
            try {
                // Only ever a JSON object goes into the page's script.
                stored = new JSONObject(preferences(view.getContext()).getString(LAYOUT, "")).toString();
            } catch (JSONException e) {
                // nothing stored yet
            }
            view.evaluateJavascript("(" + layoutScript + ")(" + gap + "," + stored + ");", null);
        } catch (IOException | RuntimeException e) {
            // the game simply stays where it was
        }
    }

    private static String readAsset(Context context, String name) throws IOException {
        InputStream in = context.getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
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
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (entry == ENTRY_LAYOUT) {
            editLayout(activity);
            return;
        }
        if (entry != ENTRY_SYNC && entry != ENTRY_COPY_STARTERS && entry != ENTRY_RESTORE) {
            return;
        }
        if (current != null) {
            if (current.isOnScreen(activity)) {
                return;
            }
            current.close(); // left behind, for example by a screen that was rebuilt
        }
        current = new Session(activity, entry);
        current.start();
    }

    /** Opens the layout editor in the running game's page. Without a game on screen there is nothing to lay out. */
    private static void editLayout(final Activity activity) {
        final Runnable noGame = () -> Toast.makeText(activity,
                "Start a game first, then open Screen layout from the side menu.", Toast.LENGTH_LONG).show();
        WebView game = gameRef.get();
        String host = game == null ? "" : hostOf(game.getUrl());
        if (game == null || !game.isAttachedToWindow() || !game.isShown()
                || !(host.equals(OFFLINE_HOST) || host.equals(ONLINE_HOST))) {
            noGame.run();
            return;
        }
        game.evaluateJavascript("(function() { if (!window.__saveSyncLayout) { return false; }"
                + " window.__saveSyncLayout.edit(); return true; })()", opened -> {
                    if (!"true".equals(opened)) {
                        noGame.run();
                    }
                });
    }

    /**
     * A game has started: compare both sides without showing anything, and speak up
     * only if the other side is ahead. Once per game WebView and game.
     *
     * The offline game is checked when its page has loaded. The online game is checked
     * when it first talks to the server, because only then is its client session id
     * known, and a check under any other id would invalidate the game's session.
     */
    private static void checkAtStart(String gameHost) {
        boolean online = ONLINE_HOST.equals(gameHost);
        if (online ? checkedOnline : checkedOffline) {
            return;
        }
        if (online) {
            checkedOnline = true;
        } else {
            checkedOffline = true;
        }
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || current != null) {
            return;
        }
        current = new Session(activity, ENTRY_SYNC);
        current.startQuietly(gameHost);
    }

    /**
     * What the game's page can tell the app: the online game's client session id, from
     * the script in CLIENT_ID_JS, and the screen layout chosen in layout.js's editor.
     */
    private static final class GameBridge {
        private final Context context;

        GameBridge(Context context) {
            this.context = context;
        }

        /** Keeps the layout in the app, so the online and the offline game share it. */
        @JavascriptInterface
        public void saveLayout(String json) {
            if (json == null || json.length() > MAX_LAYOUT_CHARS) {
                return;
            }
            try {
                preferences(context).edit().putString(LAYOUT, new JSONObject(json).toString()).apply();
            } catch (JSONException e) {
                // not a layout: keep what was stored
            }
        }

        @JavascriptInterface
        public void clientId(String id) {
            if (id != null && id.matches("[A-Za-z0-9]{32}") && !id.equals(gameClientId)) {
                gameClientId = id;
                MAIN.post(() -> checkAtStart(ONLINE_HOST));
            }
        }
    }

    // ---- One run of a drawer entry ----

    private static final class Session {
        private final Activity activity;
        private final int entry;
        private final String title;
        /** Where backups are kept. Also used from the helper page's thread, through HostBridge. */
        final File backupDir;
        /** The online game's client session id to reuse, "" to make a new one. Read from the page's thread. */
        final String clientId;
        private final long startedAt = SystemClock.elapsedRealtime();
        private final StringBuilder log = new StringBuilder();

        private WebView host;
        private AlertDialog dialog;
        /** The text of the progress dialog while one is showing; the log is written into it. */
        private TextView liveText;
        private String liveHeading = "";
        private int step = IDLE;
        /** Counts answers and timeouts, so a timeout knows whether its answer arrived. */
        private int watch;
        private boolean closed;

        /** Why the online side cannot be used, or null if it was read. */
        private String onlineProblem;
        /** The account logged in online, "" if unknown. Its name is part of the browser storage keys. */
        private String onlineUser = "";
        /** The online side as JSON texts, "" for a part that is missing. */
        private String onlineSave = "";
        private String onlineHistory = "";
        private final String[] onlineSessions = {"", "", "", "", ""};
        /** False if the server failed for a slot, so an empty online slot may not be empty. */
        private boolean sessionsKnown = true;
        /** What the offline page said about both sides. Null until the comparison has run. */
        private JSONObject compared;

        /** What is being sent online, as the upload page takes it. */
        private JSONObject pendingUpload;
        /** The backup being restored, or null when this session is not a restore. */
        private String restoreId;
        private boolean restoreToOnline;
        /** Shown when the operation in progress finishes. */
        private String doneMessage = "";
        /** Whether the operation in progress writes to the offline side, and whether it has. */
        private boolean writesOffline;
        private boolean changedOffline;
        /** True once this session has talked to the server under an id of its own. */
        private boolean tookOverSession;
        private boolean reloadedOnline;
        /** The online game's settings, key to text, as the online page read them. */
        private JSONObject onlineSettings;
        /** What happened to each side's settings in this session, for the menu. */
        private final java.util.Map<String, String> settingsNotes = new java.util.LinkedHashMap<String, String>();
        /** Whether the shared run history was exchanged in this session, and if not, why it failed. */
        private boolean shared;
        private String sharingProblem = "";
        /** Set before a dialog that needs a box to type in; holds what the box starts with. */
        private String pendingInput;
        private EditText inputField;
        /** The game whose start began this session, or null if a drawer entry did. */
        private String startedGame;
        /** While true the session shows nothing, and ends without a word if anything fails. */
        private boolean quiet;

        Session(Activity activity, int entry) {
            this.activity = activity;
            this.entry = entry;
            this.title = entry == ENTRY_COPY_STARTERS ? "Copy Pokémon caught"
                    : entry == ENTRY_RESTORE ? "Restore backup" : entry == ENTRY_TIPS ? "Before you play" : "Sync saves";
            this.backupDir = new File(activity.getFilesDir(), "savesync-backups");
            this.clientId = gameClientId;
        }

        /** Whether this session still has a dialog up in the given activity. */
        boolean isOnScreen(Activity now) {
            return !closed && activity == now && !activity.isDestroyed() && dialog != null && dialog.isShowing();
        }

        void start() {
            if (entry == ENTRY_TIPS) {
                showTip(0);
            } else if (entry == ENTRY_RESTORE) {
                offerBackups();
            } else {
                beginCheck();
            }
        }

        // ---- Tips ----

        /**
         * One page of the tips. When the app opens, any page can switch them off for good;
         * opened from the Sync saves menu, that button leads back to the menu instead.
         */
        private void showTip(final int page) {
            boolean last = page == TIPS.length - 1;
            boolean fromMenu = entry != ENTRY_TIPS;
            present("Tip " + (page + 1) + " of " + TIPS.length + "\n\n" + TIPS[page],
                    new String[] {
                        last ? null : "Next",
                        page > 0 ? "Back" : null,
                        fromMenu ? "Back to the menu" : "Don't show again"},
                    new Runnable[] {
                        () -> showTip(page + 1),
                        () -> showTip(page - 1),
                        fromMenu ? this::showMenu : this::hideTips},
                    last ? "Done" : "Close");
        }

        private void hideTips() {
            preferences(activity).edit().putBoolean(TIPS_HIDDEN, true).apply();
            Toast.makeText(activity, "The tips stay under Sync saves, Show tips", Toast.LENGTH_LONG).show();
            close();
        }

        /** The check at a game's start. See checkAtStart. */
        void startQuietly(String gameHost) {
            startedGame = gameHost;
            quiet = true;
            beginCheck();
        }

        // ---- Reading both sides ----

        /** Step 1: fetch the online side. The offline page and the comparison follow. */
        private void beginCheck() {
            showProgress("Checking your saves...", true);
            openHost();
            tookOverSession = clientId.isEmpty();
            log(clientId.isEmpty() ? "using a new client session" : "using the online game's client session");
            load(ONLINE_PAGE, FETCHING, FETCH_TIMEOUT_MS,
                    "The online check did not finish. Nothing was changed.");
        }

        /** Creates the hidden WebView that both helper pages load in. */
        private void openHost() {
            if (host != null) {
                return;
            }
            host = new WebView(activity);
            // The app pauses every WebView's timers while no web screen is open. A paused
            // WebView never runs a page's script, so the helper pages would wait forever.
            host.resumeTimers();
            host.getSettings().setJavaScriptEnabled(true);
            host.getSettings().setDomStorageEnabled(true);
            host.addJavascriptInterface(new HostBridge(this), "saveSyncHost");
            host.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage message) {
                    // An empty slot is a 404, which the browser reports as an error. It is not one.
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                            && !message.message().startsWith("Failed to load resource")) {
                        logLater("page error: " + message.message());
                    }
                    return true;
                }
            });
            host.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    String requestHost = uri.getHost();
                    if (API_HOST.equals(requestHost)) {
                        return null; // the only real network traffic: the official save API
                    }
                    // Everything else comes from memory or the APK; nothing else may load.
                    String path = uri.getPath();
                    if (path != null && path.startsWith(ASSET_PATH)
                            && (ONLINE_HOST.equals(requestHost) || OFFLINE_HOST.equals(requestHost))) {
                        return asset(path.substring(ASSET_PATH.length()));
                    }
                    String url = uri.toString();
                    String body = ONLINE_PAGE.equals(url) || UPLOAD_PAGE.equals(url) ? ONLINE_HTML
                            : OFFLINE_PAGE.equals(url) ? OFFLINE_HTML : "";
                    return new WebResourceResponse("text/html", "utf-8",
                            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    log("page loaded: " + pageName(url));
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    log("load error: " + error.getDescription() + " for " + request.getUrl().getHost()
                            + request.getUrl().getPath());
                }
            });
        }

        /** Loads a helper page and waits for it to report back. */
        private void load(String page, int nextStep, long timeoutMs, String failure) {
            step = nextStep;
            expect(failure, timeoutMs);
            log("loading the " + pageName(page));
            host.loadUrl(page);
        }

        /** Runs on a WebView thread. */
        private WebResourceResponse asset(String name) {
            for (String known : ASSETS) {
                if (known.equals(name)) {
                    try {
                        return new WebResourceResponse("application/javascript", "utf-8",
                                activity.getAssets().open("savesync/" + name));
                    } catch (IOException e) {
                        logLater("script missing from the app: " + name);
                        break;
                    }
                }
            }
            return new WebResourceResponse("application/javascript", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
        }

        /**
         * The online page's report: what the server has, plus the online game's own
         * stored copies. Status 0 means no login cookie, -1 a network failure.
         */
        void onOnline(String json) {
            if (closed || step != FETCHING) {
                return;
            }
            arrived();
            try {
                JSONObject report = new JSONObject(json);
                int status = report.optInt("status");
                String save = report.optString("save");
                if (status == 200 && looksLikeSave(save)) {
                    readOnline(report, save);
                } else {
                    onlineProblem = describeFailure(status, report.optString("problem"));
                    log("online side not available (status " + status + ")");
                }
            } catch (JSONException e) {
                onlineProblem = "The online check gave an answer that could not be read.";
                log("unreadable answer from the online page");
            }
            if (knowsHistory() && !historyCode().isEmpty()) {
                shareHistory();
            } else {
                loadOffline();
            }
        }

        private void loadOffline() {
            load(OFFLINE_PAGE, LOADING_OFFLINE, PAGE_TIMEOUT_MS,
                    "The offline helper page did not load. Nothing was changed.");
        }

        // ---- Shared run history ----

        private String historyCode() {
            return preferences(activity).getString(HISTORY_CODE, "");
        }

        /**
         * Where the store keeps this account's runs. Each account has its own, so two
         * people sharing one store and code never get each other's runs. A test can
         * point this at a store of its own.
         */
        private String historyStore() {
            return System.getProperty("importfix.history", BuildInfo.HISTORY_URL) + Uri.encode(onlineUser) + "/";
        }

        /**
         * Exchanges finished runs with the store, so this phone's online run history
         * also holds the runs finished on other devices before the two sides are
         * compared. The store is an extra: if it fails, the sync goes on without it.
         */
        private void shareHistory() {
            step = SHARING;
            final int turn = ++watch;
            final String store = historyStore();
            final String code = historyCode();
            final String mine = onlineHistory;
            log("exchanging run history with your other devices");
            MAIN.postDelayed(() -> {
                if (!closed && watch == turn) {
                    log("the shared run history did not answer in time: skipped");
                    loadOffline();
                }
            }, SHARING_TIMEOUT_MS);
            new Thread(() -> {
                HistoryHub.Result result = null;
                String problem = "";
                try {
                    result = HistoryHub.exchange(store, code, mine);
                } catch (Exception e) {
                    // no connection, a wrong code, or an answer that is not what the store sends
                    problem = String.valueOf(e.getMessage());
                }
                final HistoryHub.Result exchanged = result;
                final String why = problem;
                MAIN.post(() -> onShared(turn, exchanged, why));
            }, "history-exchange").start();
        }

        private void onShared(int turn, HistoryHub.Result exchanged, String problem) {
            if (closed || watch != turn) {
                return; // given up on in the meantime
            }
            if (exchanged == null) {
                sharingProblem = problem;
                log("shared run history failed: " + problem + ". Carrying on without it.");
            } else {
                shared = true;
                log("shared run history: " + runs(exchanged.received) + " received, " + runs(exchanged.sent) + " sent");
                if (exchanged.received > 0) {
                    onlineHistory = exchanged.merged;
                    try {
                        // Kept where the online game keeps it, so its Run History screen shows them too.
                        run("window.__online.storeHistory("
                                + JSONObject.quote(CryptoJsAes.encrypt(exchanged.merged, GAME_STORAGE_KEY)) + ","
                                + JSONObject.quote(onlineUser) + ");");
                    } catch (GeneralSecurityException e) {
                        log("the received runs could not be stored for the online game");
                    }
                }
            }
            // The online page is still loaded, so shared settings can be written for the online game now.
            shareSettings("online", onlineSettings, this::loadOffline);
        }

        /** Sends the store the finished runs only the offline game has. Nothing waits for it. */
        private void shareOfflineRuns(final String runsOnlyOffline) {
            if (!shared || runsOnlyOffline.isEmpty()) {
                return;
            }
            final String store = historyStore();
            final String code = historyCode();
            new Thread(() -> {
                try {
                    int sent = HistoryHub.send(store, code, runsOnlyOffline);
                    logLater("shared run history: " + runs(sent) + " from the offline game sent");
                } catch (Exception e) {
                    logLater("the offline game's runs could not be sent to the shared run history: " + e.getMessage());
                }
            }, "history-send").start();
        }

        /** Asks for the code of the run history store. An empty code turns sharing off. */
        private void askHistoryCode() {
            pendingInput = historyCode();
            present("Shared run history\n\n"
                    + "The game keeps run history on each device only. With a code set here, this app exchanges"
                    + " finished runs, and the games' settings, with your own store each time it compares your saves, and"
                    + " keeps a copy of both games' save data there. The desktop app does the same with the same code.\n\n"
                    + "A run finished on another device then appears in both games here, and a copy of it left"
                    + " on this phone is recognised as ended.\n\n"
                    + "Leave the box empty to turn sharing off.",
                    new String[] {"Save", "Back"},
                    new Runnable[] {
                        () -> {
                            String typed = inputField == null ? "" : inputField.getText().toString().trim();
                            if (!typed.isEmpty() && typed.length() < MIN_HISTORY_CODE_CHARS) {
                                Toast.makeText(activity, "The code has at least " + MIN_HISTORY_CODE_CHARS
                                        + " characters", Toast.LENGTH_LONG).show();
                                return;
                            }
                            preferences(activity).edit().putString(HISTORY_CODE, typed).apply();
                            Toast.makeText(activity, typed.isEmpty() ? "Shared run history is off"
                                    : "Code saved. It is used from the next sync on.", Toast.LENGTH_LONG).show();
                            showMenu();
                        },
                        this::showMenu},
                    "Close");
        }

        private void readOnline(JSONObject report, String save) {
            onlineUser = report.optString("username");
            onlineSettings = report.optJSONObject("settings");
            if (!onlineUser.isEmpty()) {
                preferences(activity).edit().putString(LAST_USER, onlineUser).apply();
            }
            JSONObject cache = report.optJSONObject("cache");
            JSONArray sessions = report.optJSONArray("sessions");
            JSONArray cached = cache == null ? null : cache.optJSONArray("sessions");
            // The online game sends its data to the server only every few minutes and keeps
            // its own copies in between. When it loads, it keeps them only if its copy of
            // the save data is newer than the server's; otherwise it drops them all, because
            // the account was played somewhere else since. The same rule is followed here,
            // so a run that ended on another device does not live on in this phone's copy.
            String cachedSave = cache == null ? "" : decrypt(cache.optString("save"));
            boolean outdated = !cachedSave.isEmpty() && timestampOf(cachedSave) <= timestampOf(save);
            if (outdated && count(cached) > 0) {
                log("the online game's stored runs are older than the server's save data: ignored");
            }
            onlineSave = outdated ? save : newer("save data", save, cachedSave);
            for (int slot = 0; slot < SLOTS; slot++) {
                String fromServer = textAt(sessions, slot);
                onlineSessions[slot] = outdated ? fromServer
                        : newer("slot " + (slot + 1), fromServer, decrypt(textAt(cached, slot)));
            }
            sessionsKnown = report.optBoolean("sessionsKnown", true);
            if (!sessionsKnown) {
                log("the server did not answer for every slot, so runs in progress are left alone");
            }
            onlineHistory = decrypt(report.optString("history"));
        }

        /** Of the server's copy and this app's stored copy, the one saved later. */
        private String newer(String what, String fromServer, String stored) {
            if (stored.isEmpty() || timestampOf(stored) <= timestampOf(fromServer)) {
                return fromServer;
            }
            log(what + ": the online game's stored copy is newer than the server's, using it");
            return stored;
        }

        void onOfflineReady() {
            if (closed || step != LOADING_OFFLINE) {
                return;
            }
            arrived();
            if (restoreId != null && !restoreToOnline) {
                step = EXECUTING;
                expect("The restore did not finish. Check the offline game before trying again.", PAGE_TIMEOUT_MS);
                log("restoring the backup");
                run("window.__sync.restore(" + backupSnapshot(restoreId) + ");");
                return;
            }
            JSONObject flags = new JSONObject();
            try {
                flags.put("canUpload", canUpload());
                flags.put("knowsHistory", knowsHistory());
                flags.put("sessionsUnknown", !sessionsKnown);
            } catch (JSONException e) {
                // both stay unset, which the page reads as false
            }
            step = COMPARING;
            expect("The comparison did not finish. Nothing was changed.", PAGE_TIMEOUT_MS);
            log("comparing the two sides");
            run("window.__sync.compare(" + onlineSnapshot() + "," + flags + ");");
        }

        void onCompared(String json) {
            if (closed || step != COMPARING) {
                return;
            }
            arrived();
            step = IDLE;
            try {
                compared = new JSONObject(json);
            } catch (JSONException e) {
                fail("The comparison could not be read. Nothing was changed.");
                return;
            }
            log("comparison done");
            shareOfflineRuns(compared.optString("localOnlyHistory"));
            backUpToStore();
            shareSettings("offline", compared.optJSONObject("localSettings"), () -> {
                if (quiet) {
                    warnIfBehind();
                } else if (restoreId != null) {
                    sendBackupOnline();
                } else if (entry == ENTRY_COPY_STARTERS) {
                    offerStarterLists();
                } else {
                    showMenu();
                }
            });
        }

        // ---- Shared settings and backups in the player's store ----

        private String storeFor(String user) {
            return System.getProperty("importfix.history", BuildInfo.HISTORY_URL) + Uri.encode(user) + "/";
        }

        private String settingsKey(String side) {
            return SETTINGS_LAST + side + "_" + onlineUser.toLowerCase(Locale.ROOT);
        }

        private JSONObject lastSettings(String side) {
            try {
                String kept = preferences(activity).getString(settingsKey(side), "");
                return kept.isEmpty() ? null : new JSONObject(kept);
            } catch (JSONException e) {
                return null;
            }
        }

        /** Settings are shared during Sync saves only: the games read them when they start. */
        private boolean sharesSettings() {
            return !quiet && restoreId == null && entry == ENTRY_SYNC && knowsHistory() && !historyCode().isEmpty();
        }

        /**
         * Exchanges one game's settings with the store, then carries on with next. When the
         * shared settings win, they are written by the helper page of that game's origin,
         * which must be the page loaded at the time. The store is an extra: if it fails,
         * the sync goes on without it.
         */
        private void shareSettings(final String side, final JSONObject local, final Runnable next) {
            if (!sharesSettings() || local == null) {
                next.run();
                return;
            }
            final int before = step;
            step = SHARING;
            final int turn = ++watch;
            final String store = historyStore();
            final String code = historyCode();
            final JSONObject last = lastSettings(side);
            log("exchanging the " + side + " game's settings with your other devices");
            MAIN.postDelayed(() -> {
                if (!closed && watch == turn) {
                    watch++;
                    step = before;
                    log("the shared settings did not answer in time: skipped");
                    next.run();
                }
            }, SHARING_TIMEOUT_MS);
            new Thread(() -> {
                SharedStore.Result result = null;
                String problem = "";
                try {
                    result = SharedStore.exchange(store, code, "phone-" + side, "Phone (" + side + ")", local, last, 0,
                            System.currentTimeMillis());
                } catch (Exception e) {
                    problem = String.valueOf(e.getMessage());
                }
                final SharedStore.Result exchanged = result;
                final String why = problem;
                MAIN.post(() -> {
                    if (closed || watch != turn) {
                        return; // given up on in the meantime
                    }
                    watch++;
                    step = before;
                    onSettingsShared(side, exchanged, why);
                    next.run();
                });
            }, "settings-exchange").start();
        }

        private void onSettingsShared(String side, SharedStore.Result result, String problem) {
            if (result == null) {
                settingsNotes.put(side, "failed (" + problem + ")");
                log("shared settings failed: " + problem + ". Carrying on without them.");
                return;
            }
            if (result.last != null) {
                preferences(activity).edit().putString(settingsKey(side), result.last.toString()).apply();
            }
            if (SharedStore.CHOOSE.equals(result.action)) {
                settingsNotes.put(side, "not chosen yet. Tap Shared settings to pick whose to use");
                log("shared settings: none chosen yet; the " + side + " game's are offered as a choice");
            } else if (SharedStore.APPLY.equals(result.action)) {
                run(("online".equals(side) ? "window.__online" : "window.__sync") + ".applySettings(" + result.write + ");");
                settingsNotes.put(side, "updated from " + result.from + ", used from the game's next start");
                log("shared settings from " + result.from + " written for the " + side + " game");
            } else if (SharedStore.PUSHED.equals(result.action)) {
                settingsNotes.put(side, "this game's change was sent to your other devices");
                log("shared settings: the " + side + " game's change was sent");
            } else {
                settingsNotes.put(side, "in use (from " + result.from + ")");
                log("shared settings: the " + side + " game already uses them");
            }
        }

        /** Lets the player pick whose settings every device uses. */
        private void chooseSettings() {
            if (historyCode().isEmpty() || !knowsHistory()) {
                present("Shared settings\n\nSet the code under Shared run history first, and be logged in online.",
                        new String[] {"Back"}, new Runnable[] {this::showMenu}, "Close");
                return;
            }
            final String store = historyStore();
            final String code = historyCode();
            showProgress("Asking your server whose settings it has...", false);
            new Thread(() -> {
                JSONArray found = null;
                String problem = "";
                try {
                    found = SharedStore.candidates(store, code);
                } catch (Exception e) {
                    problem = String.valueOf(e.getMessage());
                }
                final JSONArray list = found;
                final String why = problem;
                MAIN.post(() -> {
                    if (closed) {
                        return;
                    }
                    if (list == null) {
                        present("Shared settings\n\nYour server could not be asked: " + why,
                                new String[] {"Back"}, new Runnable[] {this::showMenu}, "Close");
                        return;
                    }
                    String[] labels = new String[list.length() + 1];
                    Runnable[] actions = new Runnable[list.length() + 1];
                    for (int i = 0; i < list.length(); i++) {
                        final JSONObject candidate = list.optJSONObject(i);
                        labels[i] = "Use: " + candidate.optString("from") + " (shared " + formatTime(candidate.optLong("updated")) + ")";
                        actions[i] = () -> useSettings(candidate.optString("device"), candidate.optString("from"));
                    }
                    labels[list.length()] = "Back";
                    actions[list.length()] = this::showMenu;
                    present("Shared settings\n\nPick whose settings every device should use. Touch controls and"
                            + " vibration stay as each device has them. The games use new settings the next time they start.\n\n"
                            + "A device is listed once it has shared: the desktop app when it starts, this phone during Sync saves."
                            + (list.length() == 0 ? "\n\nNo device has shared yet." : ""),
                            labels, actions, "Close");
                });
            }, "settings-candidates").start();
        }

        private void useSettings(final String device, final String from) {
            final String store = historyStore();
            final String code = historyCode();
            showProgress("Choosing " + from + "'s settings...", false);
            new Thread(() -> {
                String problem = "";
                try {
                    SharedStore.choose(store, code, device, System.currentTimeMillis());
                } catch (Exception e) {
                    problem = String.valueOf(e.getMessage());
                }
                final String why = problem;
                MAIN.post(() -> {
                    if (closed) {
                        return;
                    }
                    if (!why.isEmpty()) {
                        present("Shared settings\n\nThe choice could not be stored: " + why,
                                new String[] {"Back"}, new Runnable[] {this::showMenu}, "Close");
                        return;
                    }
                    // Both games here take the chosen settings in a fresh check.
                    preferences(activity).edit().remove(settingsKey("online")).remove(settingsKey("offline")).apply();
                    Toast.makeText(activity, from + "'s settings are now shared. Writing them here...", Toast.LENGTH_LONG).show();
                    close();
                    MAIN.post(() -> onDrawerEntry(ENTRY_SYNC));
                });
            }, "settings-choose").start();
        }

        /**
         * Sends the store a copy of each side as it is now, when it changed since the last
         * copy: the offline game's data exists nowhere else, and the store keeps it safe.
         */
        private void backUpToStore() {
            JSONObject local = compared.optJSONObject("localSnapshot");
            compared.remove("localSnapshot");
            if (historyCode().isEmpty() || !knowsHistory()) {
                return;
            }
            if (local != null) {
                JSONArray sessions = local.optJSONArray("sessions");
                snapshotToStore("offline", local.optString("save"), local.optString("history"),
                        sessions == null ? "" : sessions.toString());
            }
            if (canUpload()) {
                JSONArray sessions = new JSONArray();
                for (String text : onlineSessions) {
                    sessions.put(text);
                }
                snapshotToStore("online", onlineSave, onlineHistory, sessions.toString());
            }
        }

        private void snapshotToStore(final String side, final String save, final String history, final String sessions) {
            if (save.isEmpty()) {
                return;
            }
            final String key = STORE_BACKUP + side + "_" + onlineUser.toLowerCase(Locale.ROOT);
            final String fingerprint = SharedStore.sha256(save + "\n" + history + "\n" + sessions);
            if (fingerprint.equals(preferences(activity).getString(key, ""))) {
                return;
            }
            final String store = historyStore();
            final String code = historyCode();
            final String user = onlineUser;
            new Thread(() -> {
                try {
                    SharedStore.putBackup(store, code, "phone-" + side, "Phone (" + side + ")", side, user,
                            save, history, sessions, System.currentTimeMillis());
                    preferences(activity).edit().putString(key, fingerprint).apply();
                    logLater("the " + side + " side was backed up to your server");
                } catch (Exception e) {
                    logLater("the " + side + " side could not be backed up to your server: " + e.getMessage());
                }
            }, "store-backup").start();
        }

        /** The backups in the store, for the account last read online. */
        private void offerStoreBackups() {
            final String user = onlineUser.isEmpty() ? preferences(activity).getString(LAST_USER, "") : onlineUser;
            final String code = historyCode();
            if (code.isEmpty() || user.isEmpty()) {
                present("Backups on your server\n\nSet the code under Sync saves, Shared run history, and open Sync saves"
                        + " once while logged in online, so this app knows your account.",
                        new String[] {"Back"}, new Runnable[] {this::offerBackups}, "Close");
                return;
            }
            final String store = storeFor(user);
            showProgress("Asking your server for backups...", false);
            new Thread(() -> {
                JSONArray found = null;
                String problem = "";
                try {
                    found = SharedStore.backups(store, code);
                } catch (Exception e) {
                    problem = String.valueOf(e.getMessage());
                }
                final JSONArray list = found;
                final String why = problem;
                MAIN.post(() -> {
                    if (closed) {
                        return;
                    }
                    if (list == null || list.length() == 0) {
                        present("Backups on your server\n\n" + (list == null ? "Your server could not be asked: " + why
                                : "None yet for " + user + "."), new String[] {"Back"}, new Runnable[] {this::offerBackups}, "Close");
                        return;
                    }
                    String[] labels = new String[list.length() + 1];
                    Runnable[] actions = new Runnable[list.length() + 1];
                    for (int i = 0; i < list.length(); i++) {
                        final JSONObject backup = list.optJSONObject(i);
                        labels[i] = formatTime(backup.optLong("made")) + ", " + deviceName(backup.optString("from"))
                                + " (" + Math.max(1, backup.optLong("size") / 1024) + " KB)";
                        actions[i] = () -> takeStoreBackup(store, code, backup.optString("id"));
                    }
                    labels[list.length()] = "Back";
                    actions[list.length()] = this::offerBackups;
                    present("Backups on your server for " + user + ", newest first. Picking one copies it to this phone,"
                            + " where you can restore it.", labels, actions, "Close");
                });
            }, "store-backups").start();
        }

        /** Copies one of the store's backups into this phone's backups and offers it for restoring. */
        private void takeStoreBackup(final String store, final String code, final String id) {
            showProgress("Fetching the backup from your server...", false);
            new Thread(() -> {
                JSONObject found = null;
                String problem = "";
                try {
                    found = SharedStore.backup(store, code, id);
                } catch (Exception e) {
                    problem = String.valueOf(e.getMessage());
                }
                final JSONObject backup = found;
                final String why = problem;
                MAIN.post(() -> {
                    if (closed) {
                        return;
                    }
                    String copied = backup == null ? null : copyStoreBackup(backup);
                    if (copied == null) {
                        present("Backups on your server\n\n" + (backup == null ? "The backup could not be fetched: " + why
                                : "The backup could not be kept on this phone."),
                                new String[] {"Back"}, new Runnable[] {this::offerStoreBackups}, "Close");
                        return;
                    }
                    offerBackup(copied);
                });
            }, "store-backup-fetch").start();
        }

        private String copyStoreBackup(JSONObject backup) {
            try {
                String save = backup.optString("save");
                String history = backup.optString("history");
                JSONArray stored = backup.optJSONArray("sessions");
                JSONArray texts = new JSONArray();
                JSONArray summaries = new JSONArray();
                boolean anySession = false;
                for (int slot = 0; slot < SLOTS; slot++) {
                    String text = stored == null ? "" : stored.optString(slot, "");
                    texts.put(text);
                    JSONObject summary = summarizeSession(text);
                    summaries.put(summary == null ? JSONObject.NULL : summary);
                    anySession |= summary != null;
                }
                int runCount = HistoryHub.runs(history).length();
                JSONObject meta = new JSONObject();
                meta.put("created", backup.optLong("made"));
                meta.put("origin", "online".equals(backup.optString("origin")) ? "online" : "offline");
                meta.put("reason", "from your server (" + backup.optString("from") + ")");
                if (!save.isEmpty()) {
                    meta.put("save", summarizeSave(save));
                }
                meta.put("runs", runCount);
                meta.put("sessions", summaries);
                return Backups.write(backupDir, meta.optString("origin"), meta.toString(), save,
                        runCount > 0 ? history : "", anySession ? texts.toString() : "");
            } catch (JSONException e) {
                return null;
            }
        }

        /** The online side can be written: logged in, and its save data was read. */
        private boolean canUpload() {
            return onlineProblem == null && !onlineSave.isEmpty();
        }

        /** Online run history is stored under the account name, so it is only known with one. */
        private boolean knowsHistory() {
            return onlineProblem == null && !onlineUser.isEmpty();
        }

        private JSONObject onlineSnapshot() {
            return snapshot(onlineSave, onlineHistory, onlineSessions);
        }

        // ---- The check at a game's start ----

        /**
         * Speaks up if save data or a run in progress is ahead on the other side from
         * the game being started. Otherwise the session ends without showing anything.
         *
         * A slot that holds a different run on each side is left out: neither run is
         * behind the other, and replacing one belongs in the menu, not in a prompt.
         */
        private void warnIfBehind() {
            boolean offline = OFFLINE_HOST.equals(startedGame);
            String direction = offline ? DOWN : UP;
            JSONObject recommended = compared.optJSONObject("recommended");
            final JSONObject plan = towards(recommended == null ? new JSONObject() : recommended, direction);
            // The start of the recommendation's line for each slot left out, as offline.js writes it.
            List<String> skipped = new ArrayList<String>();
            JSONArray moves = plan.optJSONArray("sessions");
            for (int slot = 0; slot < SLOTS; slot++) {
                JSONObject mine = objectAt(compared.optJSONArray("localSessions"), slot);
                JSONObject theirs = objectAt(compared.optJSONArray("onlineSessions"), slot);
                if (mine != null && theirs != null && !mine.optString("seed").equals(theirs.optString("seed"))
                        && mine.optJSONObject("ended") == null && theirs.optJSONObject("ended") == null) {
                    try {
                        moves.put(slot, "");
                    } catch (JSONException e) {
                        // cannot happen: the slot exists
                    }
                    skipped.add("Run in progress, slot " + (slot + 1) + ":");
                }
            }
            if (onlineProblem != null || (plan.optString("save").isEmpty() && firstMoved(plan) < 0)) {
                close();
                return;
            }
            quiet = false;
            StringBuilder message = new StringBuilder(offline
                    ? "Before you play offline: the online side is ahead.\n"
                    : "Before you play online: the offline side is ahead.\n");
            String phrase = offline ? "online to offline" : "offline to online";
            String removal = offline ? "remove from offline" : "remove from online";
            JSONArray lines = compared.optJSONArray("recommendedLines");
            for (int i = 0; lines != null && i < lines.length(); i++) {
                String line = lines.optString(i);
                boolean left = false;
                for (String start : skipped) {
                    left |= line.startsWith(start);
                }
                if ((line.contains(phrase) || line.contains(removal)) && !left) {
                    message.append("\n").append(line);
                }
            }
            if (!plan.optString("history").isEmpty()) {
                int added = side(offline ? "onlineHistory" : "localHistory").optInt("notInOther");
                message.append("\nRun history: ").append(runs(added)).append(offline ? " to offline" : " to online");
            }
            message.append(offline
                    ? "\n\nPlay anyway carries on from the older offline data."
                    : "\n\nPlay anyway carries on from the older online data.");
            message.append("\n\n").append(sendsToServer(plan) ? UPLOAD_NOTE + "\n\n" : "").append(BACKUP_NOTE);
            present(message.toString(),
                    new String[] {"Sync now", "Open Sync saves"},
                    new Runnable[] {() -> execute(plan, "Sync finished"), this::showMenu},
                    "Play anyway");
        }

        // ---- Sync saves: the menu ----

        private void showMenu() {
            JSONObject local = side("local");
            JSONObject online = side("online");
            JSONObject plan = compared.optJSONObject("recommended");
            final JSONObject recommended = plan == null ? new JSONObject() : plan;
            boolean anything = hasWork(recommended);

            StringBuilder message = new StringBuilder();
            message.append("ONLINE");
            if (!onlineUser.isEmpty()) {
                message.append(" (").append(onlineUser).append(")");
            }
            message.append("\n");
            if (onlineProblem != null) {
                message.append(onlineProblem);
            } else {
                message.append(describeSave(online));
                if (knowsHistory()) {
                    message.append("\n").append(describeHistory(side("onlineHistory"), "offline"));
                }
                message.append("\n").append(describeSessions(compared.optJSONArray("onlineSessions"), "offline"));
            }
            message.append("\n\nOFFLINE\n").append(describeSave(local));
            message.append("\n").append(describeHistory(side("localHistory"), knowsHistory() ? "online" : null));
            message.append("\n").append(describeSessions(compared.optJSONArray("localSessions"), "online"));
            message.append("\n\nRECOMMENDED\n");
            String lines = joinLines(compared.optJSONArray("recommendedLines"));
            if (onlineProblem != null) {
                message.append("Nothing, because the online side could not be read.");
            } else if (anything) {
                message.append(lines);
            } else {
                message.append(lines.isEmpty() ? "Nothing to do." : lines + "\nNothing to do.");
            }
            if (knowsHistory()) {
                message.append("\n\nOnline run history is only what Online mode in this app has recorded.");
            }
            message.append("\n\nShared run history: ").append(historyCode().isEmpty() ? "off"
                    : shared ? "on" : sharingProblem.isEmpty() ? "on, not used this time"
                    : "on, but it failed this time (" + sharingProblem + ")").append(".");
            for (java.util.Map.Entry<String, String> note : settingsNotes.entrySet()) {
                message.append("\nShared settings, ").append(note.getKey()).append(": ").append(note.getValue()).append(".");
            }
            message.append("\nThis app: build ").append(BuildInfo.BUILD).append(".");

            present(message.toString(),
                    new String[] {
                        anything ? "Recommended sync" : null,
                        "Copy online to offline...",
                        "Copy offline to online...",
                        "Show log",
                        "Show tips",
                        "Shared run history",
                        historyCode().isEmpty() || !knowsHistory() ? null : "Shared settings",
                        "Check for updates"},
                    new Runnable[] {
                        () -> confirmRecommended(recommended),
                        () -> chooseParts(DOWN),
                        () -> chooseParts(UP),
                        this::showLog,
                        () -> showTip(0),
                        this::askHistoryCode,
                        this::chooseSettings,
                        () -> {
                            close();
                            Updater.checkNow(activity);
                        }},
                    "Close");
        }

        private void confirmRecommended(final JSONObject plan) {
            String message = "The recommended sync moves each part from the side that is ahead:\n\n"
                    + joinLines(compared.optJSONArray("recommendedLines"))
                    + "\n\n" + (sendsToServer(plan) ? UPLOAD_NOTE + "\n\n" : "") + BACKUP_NOTE;
            present(message,
                    new String[] {"Sync", "Back"},
                    new Runnable[] {() -> execute(plan, "Sync finished"), this::showMenu},
                    "Close");
        }

        /** Asks what a manual copy in one direction should include. */
        private void chooseParts(final String direction) {
            final boolean down = DOWN.equals(direction);
            final boolean save = down ? onlineProblem == null && usable(side("online"))
                    : canUpload() && usable(side("local"));
            final boolean history = knowsHistory()
                    && side(down ? "onlineHistory" : "localHistory").optInt("runs") > 0;
            final boolean sessions = sessionsKnown && (down ? onlineProblem == null : canUpload())
                    && firstMoved(planFor(direction, false, false, true)) >= 0;
            String heading = down ? "Copy online to offline" : "Copy offline to online";
            int parts = (save ? 1 : 0) + (history ? 1 : 0) + (sessions ? 1 : 0);
            if (parts == 0) {
                String why = onlineProblem != null ? onlineProblem
                        : down ? "The online side has nothing to copy." : "The offline side has nothing to copy.";
                present(heading + "\n\n" + why, new String[] {"Back"}, new Runnable[] {this::showMenu}, "Close");
                return;
            }
            present(heading + "\n\nWhat should be copied? The next screen shows exactly what changes.",
                    new String[] {
                        parts > 1 ? "Everything" : null,
                        save ? "Save data only" : null,
                        history ? "Run history only" : null,
                        sessions ? "Runs in progress only" : null,
                        "Back"},
                    new Runnable[] {
                        () -> confirmCopy(down, planFor(direction, save, history, sessions), sessions),
                        () -> confirmCopy(down, planFor(direction, true, false, false), false),
                        () -> confirmCopy(down, planFor(direction, false, true, false), false),
                        () -> confirmCopy(down, planFor(direction, false, false, true), true),
                        this::showMenu},
                    "Close");
        }

        /**
         * A plan that moves the chosen parts one way. Only slots the source side has are
         * moved, and not a run that has already ended on the side it would go to.
         */
        private JSONObject planFor(String direction, boolean save, boolean history, boolean sessions) {
            JSONArray source = compared.optJSONArray(DOWN.equals(direction) ? "onlineSessions" : "localSessions");
            JSONObject plan = new JSONObject();
            JSONArray slots = new JSONArray();
            for (int slot = 0; slot < SLOTS; slot++) {
                JSONObject run = objectAt(source, slot);
                slots.put(sessions && run != null && run.optJSONObject("ended") == null ? direction : "");
            }
            try {
                plan.put("save", save ? direction : "");
                plan.put("history", history ? direction : "");
                plan.put("sessions", slots);
            } catch (JSONException e) {
                // cannot happen: the keys are not null
            }
            return plan;
        }

        private void confirmCopy(final boolean down, final JSONObject plan, boolean withRuns) {
            StringBuilder leftOut = new StringBuilder();
            JSONArray source = compared.optJSONArray(down ? "onlineSessions" : "localSessions");
            for (int slot = 0; withRuns && slot < SLOTS; slot++) {
                JSONObject run = objectAt(source, slot);
                if (run != null && run.optJSONObject("ended") != null) {
                    leftOut.append("Slot ").append(slot + 1).append(" is left out: that run already ended ")
                            .append(down ? "offline" : "online").append(". The recommended sync removes it.\n");
                }
            }
            String message = (down ? "Copy online to offline" : "Copy offline to online") + "\n\n"
                    + describePlan(plan) + leftOut
                    + "\n" + (sendsToServer(plan) ? UPLOAD_NOTE + "\n\n" : "") + BACKUP_NOTE;
            present(message,
                    new String[] {"Copy", "Back"},
                    new Runnable[] {
                        () -> execute(plan, down ? "Copied online to offline" : "Copied offline to online"),
                        this::showMenu},
                    "Close");
        }

        /** Says, part by part, what a manual copy replaces, and warns where that loses progress. */
        private String describePlan(JSONObject plan) {
            StringBuilder out = new StringBuilder();
            JSONObject local = side("local");
            JSONObject online = side("online");
            String save = plan.optString("save");
            if (DOWN.equals(save)) {
                out.append("Save data: the offline save is replaced by the online one.\n");
                String ahead = usable(local) ? aheadBy(local, online) : null;
                if (ahead != null) {
                    out.append("WARNING: the offline save ").append(ahead).append(". That progress is lost.\n");
                }
            } else if (UP.equals(save)) {
                out.append("Save data: the ONLINE save is replaced by the offline one.\n");
                String ahead = aheadBy(online, local);
                if (ahead != null) {
                    out.append("WARNING: the online save ").append(ahead)
                            .append(". The server refuses a save with less play time.\n");
                }
            }

            String history = plan.optString("history");
            int toOffline = side("onlineHistory").optInt("notInOther");
            int toOnline = side("localHistory").optInt("notInOther");
            if (DOWN.equals(history) || BOTH.equals(history)) {
                out.append("Run history: ").append(runs(toOffline)).append(" added to offline.\n");
            }
            if (UP.equals(history) || BOTH.equals(history)) {
                out.append("Run history: ").append(runs(toOnline)).append(" added to online.\n");
            }
            if (!history.isEmpty()) {
                out.append("The game keeps 25 runs, so older ones can drop off.\n");
            }

            JSONArray moves = plan.optJSONArray("sessions");
            JSONArray localSessions = compared.optJSONArray("localSessions");
            JSONArray onlineSessions = compared.optJSONArray("onlineSessions");
            for (int slot = 0; slot < SLOTS; slot++) {
                String move = textAt(moves, slot);
                if (move.isEmpty()) {
                    continue;
                }
                boolean down = DOWN.equals(move);
                JSONObject source = objectAt(down ? onlineSessions : localSessions, slot);
                JSONObject target = objectAt(down ? localSessions : onlineSessions, slot);
                if (source == null) {
                    continue;
                }
                out.append("Slot ").append(slot + 1).append(": ").append(describeSession(source))
                        .append(down ? " goes to offline" : " goes to ONLINE");
                if (target == null) {
                    out.append(".\n");
                    if (!down && source.optBoolean("maybeEnded")) {
                        out.append("WARNING: online was played after this run was last saved here. If it ended")
                                .append(" or was deleted online, this brings it back.\n");
                    }
                    continue;
                }
                out.append(", replacing ").append(describeSession(target)).append(".\n");
                boolean sameRun = source.optString("seed").equals(target.optString("seed"));
                if (sameRun && target.optInt("wave") > source.optInt("wave")) {
                    out.append("WARNING: that goes back from wave ").append(target.optInt("wave"))
                            .append(" to wave ").append(source.optInt("wave"))
                            .append(down ? ".\n" : ". The server refuses that.\n");
                } else if (!sameRun && target.optJSONObject("ended") == null
                        && target.optLong("timestamp") > source.optLong("timestamp")) {
                    out.append("WARNING: the run it replaces is a different, newer run.\n");
                }
            }
            return out.toString();
        }

        private void showLog() {
            present("LOG\n" + log,
                    new String[] {"Copy log", "Back"},
                    new Runnable[] {this::copyLog, this::showMenu},
                    "Close");
        }

        private void copyLog() {
            if (copyText("Sync log", title + "\n" + log)) {
                Toast.makeText(activity, "Log copied to the clipboard", Toast.LENGTH_SHORT).show();
            }
        }

        // ---- Carrying out a plan ----

        /** Hands the plan to the offline page, which backs up and writes the offline side. */
        private void execute(JSONObject plan, String whenDone) {
            doneMessage = whenDone;
            writesOffline = writesOffline(plan);
            showProgress("Syncing...", false);
            step = EXECUTING;
            expect("The offline side did not answer. Check the offline game before trying again.", PAGE_TIMEOUT_MS);
            log("plan: " + plan);
            run("window.__sync.execute(" + plan + ");");
        }

        /**
         * The offline side is done. `uploadJson` holds what has to go online, as a
         * snapshot with "" for parts that stay; it is "" when nothing does.
         */
        void onExecuted(boolean ok, String problem, String uploadJson) {
            if (closed || step != EXECUTING) {
                return;
            }
            arrived();
            step = IDLE;
            if (!ok) {
                fail("Could not make the copy: " + problem + ".\n\nNothing was changed.");
                return;
            }
            if (writesOffline) {
                log("offline side written");
                changedOffline = true;
                reloadGameOn(OFFLINE_HOST); // a running offline game still holds the old data in memory
            }
            if (uploadJson.isEmpty()) {
                finish();
                return;
            }
            try {
                startUpload(new JSONObject(uploadJson), "before a sync");
            } catch (JSONException e) {
                fail("The data for the online side could not be read. Online was not changed.");
            }
        }

        /**
         * Backs up the parts of the online side that are about to be replaced, then opens
         * the upload page. `parts` is a snapshot: save, history and sessions as JSON texts.
         */
        private void startUpload(JSONObject parts, String backupReason) {
            String save = parts.optString("save");
            String history = parts.optString("history");
            JSONArray sessions = parts.optJSONArray("sessions");
            JSONArray remove = parts.optJSONArray("remove");
            JSONObject payload = new JSONObject();
            try {
                if (!backUpOnline(save, history, sessions, remove, backupReason)) {
                    fail("A backup of the online side could not be stored first. Online was not changed.");
                    return;
                }
                payload.put("save", save);
                payload.put("sessions", sessions == null ? new JSONArray() : sessions);
                payload.put("remove", remove == null ? new JSONArray() : remove);
                payload.put("username", onlineUser);
                // Stored the way the online game keeps it.
                payload.put("history", history.isEmpty() ? "" : CryptoJsAes.encrypt(history, GAME_STORAGE_KEY));
            } catch (JSONException | GeneralSecurityException e) {
                fail("The data for the online side could not be prepared. Online was not changed.");
                return;
            }
            pendingUpload = payload;
            load(UPLOAD_PAGE, LOADING_UPLOAD, PAGE_TIMEOUT_MS,
                    "The upload page did not load. Online was not changed.");
        }

        /** Stores what the upload replaces on the online side. True if there was nothing to keep. */
        private boolean backUpOnline(String save, String history, JSONArray sessions, JSONArray remove, String reason)
                throws JSONException {
            String keptSave = save.isEmpty() ? "" : onlineSave;
            int runs = side("onlineHistory").optInt("runs");
            String keptHistory = history.isEmpty() || runs == 0 ? "" : onlineHistory;
            JSONArray keptSessions = new JSONArray();
            JSONArray summaries = new JSONArray();
            boolean anySession = false;
            JSONArray known = compared.optJSONArray("onlineSessions");
            for (int slot = 0; slot < SLOTS; slot++) {
                boolean changes = !textAt(sessions, slot).isEmpty() || (remove != null && remove.optBoolean(slot));
                boolean kept = changes && !onlineSessions[slot].isEmpty();
                keptSessions.put(kept ? onlineSessions[slot] : "");
                JSONObject summary = kept ? objectAt(known, slot) : null;
                summaries.put(summary == null ? JSONObject.NULL : summary);
                anySession |= kept;
            }
            if (keptSave.isEmpty() && keptHistory.isEmpty() && !anySession) {
                return true;
            }
            JSONObject meta = new JSONObject();
            meta.put("created", System.currentTimeMillis());
            meta.put("origin", "online");
            meta.put("reason", reason);
            if (!keptSave.isEmpty()) {
                meta.put("save", side("online"));
            }
            meta.put("runs", keptHistory.isEmpty() ? 0 : runs);
            meta.put("sessions", summaries);
            boolean stored = Backups.write(backupDir, "online", meta.toString(), keptSave, keptHistory,
                    anySession ? keptSessions.toString() : "") != null;
            log(stored ? "online side backed up" : "the online backup could not be stored");
            return stored;
        }

        void onUploadReady() {
            if (closed || step != LOADING_UPLOAD || pendingUpload == null) {
                return;
            }
            arrived();
            step = UPLOADING;
            expect("The server did not answer. Part of the upload may have gone through, so check your"
                    + " online save before trying again.", UPLOAD_TIMEOUT_MS);
            run("window.__online.upload(" + pendingUpload + ");");
        }

        /** The upload page's result: one status per part, null for a part it did not send. */
        void onUploaded(String json) {
            if (closed || step != UPLOADING) {
                return;
            }
            arrived();
            step = IDLE;
            reloadedOnline = true;
            reloadGameOn(ONLINE_HOST); // a running online game still holds the old data in memory
            List<String> problems = new ArrayList<String>();
            boolean partly = false;
            try {
                JSONObject result = new JSONObject(json);
                int status = result.optInt("status");
                if (status != 200) {
                    problems.add("Nothing was sent: " + refusal(status, result.optString("problem")));
                } else {
                    partly = listRefusals(result, problems);
                }
            } catch (JSONException e) {
                problems.add("The upload's result could not be read. Check your online save.");
            }
            if (problems.isEmpty()) {
                finish();
                return;
            }
            StringBuilder message = new StringBuilder(
                    partly ? "Not everything went through.\n" : "Nothing was changed online.\n");
            for (String problem : problems) {
                message.append("\n").append(problem);
            }
            if (partly) {
                message.append("\n\nThe other parts of the copy went through.");
            }
            if (changedOffline) {
                message.append("\n\nThe offline side was updated as planned.");
            }
            log("finished with " + problems.size() + " problem(s)");
            fail(message.toString());
        }

        /** Adds a line per part the server turned down. Answers whether anything else did go through. */
        private boolean listRefusals(JSONObject result, List<String> problems) {
            boolean wantsHistory = !pendingUpload.optString("history").isEmpty();
            JSONArray wanted = pendingUpload.optJSONArray("sessions");
            JSONObject save = result.optJSONObject("save");
            if (save != null && !succeeded(save)) {
                problems.add("Save data: " + refusal(save.optInt("status"), save.optString("body")));
                if (wantsHistory || count(wanted) > 0 || removes(pendingUpload)) {
                    problems.add("The run history and runs in progress go with the save data, so they were not sent.");
                }
                return false;
            }
            int sent = save != null ? 1 : 0;
            JSONArray sessions = result.optJSONArray("sessions");
            for (int slot = 0; slot < SLOTS; slot++) {
                JSONObject answer = objectAt(sessions, slot);
                if (answer != null && !succeeded(answer)) {
                    problems.add("Slot " + (slot + 1) + ": "
                            + refusal(answer.optInt("status"), answer.optString("body")));
                } else if (answer != null) {
                    sent++;
                }
            }
            if (wantsHistory && result.optBoolean("history")) {
                sent++;
            }
            if (wantsHistory && !result.optBoolean("history")) {
                problems.add("Run history: could not be stored for the online game ("
                        + (onlineUser.isEmpty() ? "the account name could not be read"
                                : result.optString("historyProblem", "unknown reason")) + ").");
            }
            return sent > 0;
        }

        private void finish() {
            log("done");
            Toast.makeText(activity, doneMessage, Toast.LENGTH_LONG).show();
            close();
        }

        // ---- Copy Pokémon caught ----

        private void offerStarterLists() {
            JSONObject local = side("local");
            JSONObject online = side("online");
            String message = "ONLINE SAVE\n"
                    + (onlineProblem != null ? onlineProblem : describeStarters(online))
                    + "\n\nOFFLINE SAVE\n" + describeStarters(local)
                    + "\n\nThe list goes to the clipboard as text: natures, IVs, abilities, starting moves and egg moves"
                    + " for each Pokémon.";
            present(message,
                    new String[] {
                        onlineProblem == null && usable(online) ? "Copy online list" : null,
                        usable(local) ? "Copy offline list" : null},
                    new Runnable[] {() -> copyList("online"), () -> copyList("local")},
                    "Close");
        }

        private void copyList(String which) {
            showProgress("Building the list...", true);
            step = LISTING;
            expect("The list was not built.", PAGE_TIMEOUT_MS);
            run("window.__sync.starters('" + which + "');");
        }

        void onStarters(int count, String text) {
            if (closed || step != LISTING) {
                return;
            }
            arrived();
            step = IDLE;
            if (count < 0) {
                fail("Could not build the list: " + text);
                return;
            }
            if (!copyText("PokéRogue starters", text)) {
                fail("The list could not be copied to the clipboard.");
                return;
            }
            doneMessage = "Copied " + count + " Pokémon to the clipboard";
            finish();
        }

        // ---- Restore backup ----

        private void offerBackups() {
            final List<String> ids = Backups.ids(backupDir);
            // With a store, its backups come first: they hold copies from every device.
            boolean store = !historyCode().isEmpty();
            int first = store ? 1 : 0;
            String[] labels = new String[ids.size() + first];
            Runnable[] actions = new Runnable[ids.size() + first];
            if (store) {
                labels[0] = "Backups on your server...";
                actions[0] = this::offerStoreBackups;
            }
            for (int i = 0; i < ids.size(); i++) {
                final String id = ids.get(i);
                labels[i + first] = backupLabel(id, metaOf(id));
                actions[i + first] = () -> offerBackup(id);
            }
            present(ids.isEmpty()
                    ? "No backups on this phone yet.\n\nOne is made automatically before a sync or a restore replaces anything."
                    : "Each backup on this phone holds what a sync or a restore replaced. Newest first.",
                    labels, actions, "Close");
        }

        private void offerBackup(final String id) {
            JSONObject meta = metaOf(id);
            JSONObject save = meta.optJSONObject("save");
            JSONArray sessions = meta.optJSONArray("sessions");
            int runs = meta.optInt("runs");
            String message = "Taken: " + formatTime(meta.optLong("created", Backups.timeOf(id)))
                    + "\nFrom: " + meta.optString("origin", "unknown") + ", " + meta.optString("reason", "")
                    + "\n\nSAVE DATA\n" + (save != null ? describeSave(save) : "Not in this backup.")
                    + "\n\nRUN HISTORY\n" + (runs > 0 ? runs(runs) : "Not in this backup.")
                    + "\n\nRUNS IN PROGRESS\n" + (count(sessions) > 0 ? slotLines(sessions, null) : "Not in this backup.")
                    + "\n\nRestoring replaces only the parts this backup has, and backs those up first.";
            final boolean touchesServer = save != null || count(sessions) > 0;
            Runnable toOffline = () -> {
                restoreId = id;
                restoreToOnline = false;
                doneMessage = "Backup restored to offline";
                writesOffline = true;
                showProgress("Restoring the backup to offline...", false);
                openHost();
                load(OFFLINE_PAGE, LOADING_OFFLINE, PAGE_TIMEOUT_MS,
                        "The offline helper page did not load. Nothing was changed.");
            };
            final Runnable startOnline = () -> {
                restoreId = id;
                restoreToOnline = true;
                doneMessage = "Backup restored to online";
                beginCheck();
            };
            Runnable toOnline = !touchesServer ? startOnline : () -> present(RESTORE_ONLINE_WARNING,
                    new String[] {"Replace online", "Back"},
                    new Runnable[] {startOnline, () -> offerBackup(id)},
                    "Close");
            present(message,
                    new String[] {"Restore to offline", "Restore to online", "Back"},
                    new Runnable[] {toOffline, toOnline, this::offerBackups},
                    "Close");
        }

        /** Restore to online: the backup takes the place of the offline data in an upload. */
        private void sendBackupOnline() {
            if (!canUpload()) {
                fail((onlineProblem != null ? onlineProblem : NOT_LOGGED_IN) + "\n\nNothing was changed.");
                return;
            }
            showProgress("Restoring the backup to online...", false);
            startUpload(backupSnapshot(restoreId), "before restoring a backup to online");
        }

        private JSONObject metaOf(String id) {
            try {
                return new JSONObject(Backups.read(backupDir, id, Backups.META));
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        /** A stored backup as a snapshot, with "" for the parts it does not hold. */
        private JSONObject backupSnapshot(String id) {
            String[] sessions = {"", "", "", "", ""};
            try {
                JSONArray stored = new JSONArray(Backups.read(backupDir, id, Backups.SESSIONS));
                for (int slot = 0; slot < SLOTS; slot++) {
                    sessions[slot] = textAt(stored, slot);
                }
            } catch (JSONException e) {
                // no runs in progress in this backup
            }
            return snapshot(Backups.read(backupDir, id, Backups.SAVE),
                    Backups.read(backupDir, id, Backups.HISTORY), sessions);
        }

        // ---- Log and timeouts ----

        /** Adds a line to the session's log and to the progress dialog, if one is showing. */
        void log(String line) {
            if (closed) {
                return;
            }
            String text = line.length() > 240 ? line.substring(0, 240) + "..." : line;
            float seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f;
            log.append(String.format(Locale.US, "%.1fs %s\n", seconds, text));
            if (log.length() > LOG_LIMIT) {
                log.delete(0, log.indexOf("\n", log.length() - LOG_LIMIT) + 1);
            }
            if (liveText != null) {
                liveText.setText(liveHeading + "\n\n" + log);
            }
        }

        /** The same, from any thread. */
        void logLater(final String line) {
            MAIN.post(() -> log(line));
        }

        /** Starts waiting for an answer. If none arrives in time, the session stops and says so. */
        private void expect(final String failure, final long timeoutMs) {
            final int token = ++watch;
            MAIN.postDelayed(() -> {
                if (!closed && watch == token) {
                    log("no answer after " + timeoutMs / 1000 + " s");
                    fail(failure);
                }
            }, timeoutMs);
        }

        private void arrived() {
            watch++;
        }

        /** Ends the operation in progress and shows what happened, with the log. */
        private void fail(String message) {
            arrived();
            step = IDLE;
            if (quiet) {
                close();
                return;
            }
            present(message + "\n\nLOG\n" + log, new String[] {"Copy log"}, new Runnable[] {this::copyLog}, "Close");
        }

        void onPageError(String problem) {
            if (closed) {
                return;
            }
            log(problem);
            if (step != IDLE) {
                fail("A helper page stopped with an error. Check both games before trying again.");
            }
        }

        // ---- Dialog plumbing ----

        /** A dialog with no actions that shows the log as it grows. */
        private void showProgress(String heading, boolean cancellable) {
            if (quiet) {
                return;
            }
            TextView text = present(heading + "\n\n" + log, new String[0], new Runnable[0],
                    cancellable ? "Cancel" : null);
            liveHeading = heading;
            liveText = text;
        }

        /**
         * Shows one dialog at a time, replacing the previous one: a text, then one
         * full-width button per action (a null label leaves that action out). The
         * session ends when the dialog on screen is closed; an action that carries on
         * shows the next dialog first. Without a close label the dialog cannot be closed.
         *
         * @return the dialog's text view, or null if the session ended instead
         */
        private TextView present(String message, String[] labels, final Runnable[] actions, String closeLabel) {
            if (closed || activity.isFinishing()) {
                close();
                return null;
            }
            liveText = null;
            final AlertDialog previous = dialog;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(title);
            // The builder's context carries the dialog's colours; the activity's would not.
            Context themed = builder.getContext();
            int pad = Math.round(22 * themed.getResources().getDisplayMetrics().density);
            LinearLayout column = new LinearLayout(themed);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(pad, pad / 2, pad, 0);
            TextView text = new TextView(themed);
            text.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
            text.setText(message);
            column.addView(text);
            inputField = null;
            if (pendingInput != null) {
                inputField = new EditText(themed);
                inputField.setSingleLine(true);
                inputField.setText(pendingInput);
                column.addView(inputField, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                pendingInput = null;
            }
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] == null) {
                    continue;
                }
                final Runnable action = actions[i];
                Button button = new Button(themed);
                button.setAllCaps(false);
                button.setText(labels[i]);
                button.setOnClickListener(view -> {
                    if (!closed) {
                        action.run();
                    }
                });
                column.addView(button, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            ScrollView scroll = new ScrollView(themed);
            scroll.addView(column);
            builder.setView(scroll);
            if (closeLabel != null) {
                builder.setNegativeButton(closeLabel, null);
            } else {
                builder.setCancelable(false);
            }
            final AlertDialog next = builder.create();
            next.setCanceledOnTouchOutside(false);
            next.setOnDismissListener(d -> {
                // Ends the session unless a newer dialog has replaced this one.
                if (dialog == next) {
                    close();
                }
            });
            dialog = next;
            try {
                next.show();
            } catch (RuntimeException e) {
                close(); // the activity has no window to show a dialog in
                return null;
            }
            if (previous != null) {
                previous.dismiss();
            }
            return text;
        }

        private boolean copyText(String label, String text) {
            try {
                ClipboardManager clipboard =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        private void run(String script) {
            if (host != null) {
                host.evaluateJavascript(script, null);
            }
        }

        private void reloadGameOn(String gameHost) {
            WebView game = gameRef.get();
            if (game != null && hostOf(game.getUrl()).equals(gameHost)) {
                game.reload();
            }
        }

        /** One part of the comparison, never null. */
        private JSONObject side(String name) {
            JSONObject part = compared == null ? null : compared.optJSONObject(name);
            return part == null ? new JSONObject() : part;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
            } catch (RuntimeException e) {
                // the dialog's window is already gone
            }
            if (host != null) {
                host.destroy();
                host = null;
            }
            // The server now takes this session for the active one, so a running online game
            // would be turned away at its next save. Reloading lets it start a session again.
            if (tookOverSession && !reloadedOnline) {
                reloadGameOn(ONLINE_HOST);
            }
            if (current == this) {
                current = null;
            }
        }
    }

    /** Receives calls from the hidden helper pages. They arrive on a WebView thread. */
    private static final class HostBridge {
        private final Session session;

        HostBridge(Session session) {
            this.session = session;
        }

        /** The client session id the online page should use, "" for a new one. */
        @JavascriptInterface
        public String clientId() {
            return session.clientId;
        }

        /**
         * Stores a backup of the offline side and answers whether it worked. Unlike the
         * other calls this one runs to completion on the page's thread: the page waits
         * for the answer and only then replaces anything.
         */
        @JavascriptInterface
        public boolean backup(String meta, String save, String history, String sessions) {
            boolean stored = Backups.write(session.backupDir, "offline", meta, save, history, sessions) != null;
            session.logLater(stored ? "offline side backed up" : "the offline backup could not be stored");
            return stored;
        }

        @JavascriptInterface
        public void log(String line) {
            session.logLater(String.valueOf(line));
        }

        @JavascriptInterface
        public void onPageError(final String problem) {
            MAIN.post(() -> session.onPageError(String.valueOf(problem)));
        }

        @JavascriptInterface
        public void onOnline(final String json) {
            MAIN.post(() -> session.onOnline(json));
        }

        @JavascriptInterface
        public void onReady() {
            MAIN.post(session::onOfflineReady);
        }

        @JavascriptInterface
        public void onCompared(final String json) {
            MAIN.post(() -> session.onCompared(json));
        }

        @JavascriptInterface
        public void onExecuted(final boolean ok, final String problem, final String uploadJson) {
            MAIN.post(() -> session.onExecuted(ok, String.valueOf(problem), uploadJson == null ? "" : uploadJson));
        }

        @JavascriptInterface
        public void onUploadReady() {
            MAIN.post(session::onUploadReady);
        }

        @JavascriptInterface
        public void onUploaded(final String json) {
            MAIN.post(() -> session.onUploaded(json));
        }

        @JavascriptInterface
        public void onStarters(final int count, final String text) {
            MAIN.post(() -> session.onStarters(count, String.valueOf(text)));
        }
    }

    // ---- Helpers ----

    private static JSONObject snapshot(String save, String history, String[] sessions) {
        JSONObject out = new JSONObject();
        JSONArray slots = new JSONArray();
        for (String session : sessions) {
            slots.put(session);
        }
        try {
            out.put("save", save);
            out.put("history", history);
            out.put("sessions", slots);
        } catch (JSONException e) {
            // cannot happen: the keys are not null
        }
        return out;
    }

    /** The text at an index, "" if the array or the entry is missing. */
    private static String textAt(JSONArray array, int index) {
        return array == null || index < 0 || array.isNull(index) ? "" : array.optString(index, "");
    }

    private static JSONObject objectAt(JSONArray array, int index) {
        return array == null ? null : array.optJSONObject(index);
    }

    /** How many entries of an array of texts or summaries are filled. */
    private static int count(JSONArray array) {
        int filled = 0;
        for (int i = 0; array != null && i < array.length(); i++) {
            if (!array.isNull(i) && !"".equals(array.opt(i))) {
                filled++;
            }
        }
        return filled;
    }

    private static String joinLines(JSONArray lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; lines != null && i < lines.length(); i++) {
            out.append(i == 0 ? "" : "\n").append(lines.optString(i));
        }
        return out.toString();
    }

    private static boolean hasWork(JSONObject plan) {
        return !plan.optString("save").isEmpty() || !plan.optString("history").isEmpty()
                || firstMoved(plan) >= 0;
    }

    /** The first slot a plan moves, or -1 if it moves none. */
    private static int firstMoved(JSONObject plan) {
        JSONArray moves = plan.optJSONArray("sessions");
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!textAt(moves, slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /** Whether an upload empties any online slot. */
    private static boolean removes(JSONObject payload) {
        JSONArray remove = payload.optJSONArray("remove");
        for (int slot = 0; remove != null && slot < remove.length(); slot++) {
            if (remove.optBoolean(slot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parts of a plan that change one side: what moves in that direction, and runs
     * to remove from the side it leads to. Merging run history counts for both.
     */
    private static JSONObject towards(JSONObject plan, String direction) {
        String history = plan.optString("history");
        JSONArray moves = plan.optJSONArray("sessions");
        JSONObject out = new JSONObject();
        JSONArray slots = new JSONArray();
        for (int slot = 0; slot < SLOTS; slot++) {
            String move = textAt(moves, slot);
            boolean removal = (DOWN.equals(direction) ? END_OFFLINE : END_ONLINE).equals(move);
            slots.put(direction.equals(move) || removal ? move : "");
        }
        try {
            out.put("save", direction.equals(plan.optString("save")) ? direction : "");
            out.put("history", direction.equals(history) || BOTH.equals(history) ? direction : "");
            out.put("sessions", slots);
        } catch (JSONException e) {
            // cannot happen: the keys are not null
        }
        return out;
    }

    /** Whether a plan replaces anything on the offline side. */
    private static boolean writesOffline(JSONObject plan) {
        String history = plan.optString("history");
        if (DOWN.equals(plan.optString("save")) || DOWN.equals(history) || BOTH.equals(history)) {
            return true;
        }
        JSONArray moves = plan.optJSONArray("sessions");
        for (int slot = 0; slot < SLOTS; slot++) {
            if (DOWN.equals(textAt(moves, slot)) || END_OFFLINE.equals(textAt(moves, slot))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a plan sends save data or a run in progress to the server. Removing an ended run does not count. */
    private static boolean sendsToServer(JSONObject plan) {
        if (UP.equals(plan.optString("save"))) {
            return true;
        }
        JSONArray moves = plan.optJSONArray("sessions");
        for (int slot = 0; slot < SLOTS; slot++) {
            if (UP.equals(textAt(moves, slot))) {
                return true;
            }
        }
        return false;
    }

    private static boolean succeeded(JSONObject sent) {
        int status = sent.optInt("status");
        return status >= 200 && status < 300;
    }

    private static boolean usable(JSONObject summary) {
        return summary.optBoolean("exists") && !summary.optBoolean("broken");
    }

    /** Why save `a` is ahead of save `b`, or null if it is not. */
    private static String aheadBy(JSONObject a, JSONObject b) {
        if (a.optDouble("playTime", 0) > b.optDouble("playTime", 0)) {
            return "has more play time";
        }
        return a.optLong("timestamp") > b.optLong("timestamp") ? "was saved later" : null;
    }

    private static String decrypt(String stored) {
        if (stored.isEmpty()) {
            return "";
        }
        try {
            return CryptoJsAes.decrypt(stored, GAME_STORAGE_KEY);
        } catch (GeneralSecurityException e) {
            return ""; // unreadable: treated as not there
        }
    }

    /** When a save or a run was last saved, 0 if the text has no such time. */
    private static long timestampOf(String json) {
        if (json.isEmpty()) {
            return 0;
        }
        try {
            return new JSONObject(json).optLong("timestamp");
        } catch (JSONException e) {
            return 0;
        }
    }

    /** A save data text described as offline.js does (summarizeSave), for backups copied from the store. */
    private static JSONObject summarizeSave(String text) {
        JSONObject out = new JSONObject();
        try {
            out.put("exists", true);
            JSONObject d;
            try {
                d = new JSONObject(text);
            } catch (JSONException e) {
                out.put("broken", true);
                return out;
            }
            JSONObject dex = d.optJSONObject("dexData");
            int caught = 0;
            for (Iterator<String> it = dex == null ? null : dex.keys(); it != null && it.hasNext();) {
                JSONObject entry = dex.optJSONObject(it.next());
                Object attr = entry == null ? null : entry.opt("caughtAttr");
                if (attr != null && !"0".equals(String.valueOf(attr)) && !JSONObject.NULL.equals(attr)) {
                    caught++;
                }
            }
            JSONObject stats = d.optJSONObject("gameStats");
            out.put("timestamp", d.optLong("timestamp"));
            out.put("caught", caught);
            out.put("playTime", stats == null ? 0 : stats.optLong("playTime"));
            out.put("profile", d.opt("trainerId") + "/" + d.opt("secretId"));
            out.put("starters", -1);
        } catch (JSONException e) {
            // the summary stays partial
        }
        return out;
    }

    private static final String[] MODES = {"Classic", "Endless", "Spliced Endless", "Daily Run", "Challenge"};

    /** A run in progress described as offline.js does (summarizeSession), or null if there is none. */
    private static JSONObject summarizeSession(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            JSONObject d = new JSONObject(text);
            JSONObject out = new JSONObject();
            int mode = d.optInt("gameMode", -1);
            out.put("timestamp", d.optLong("timestamp"));
            out.put("wave", d.optInt("waveIndex"));
            out.put("seed", d.optString("seed"));
            JSONArray party = d.optJSONArray("party");
            out.put("party", party == null ? 0 : party.length());
            out.put("mode", mode >= 0 && mode < MODES.length ? MODES[mode] : "Run");
            return out;
        } catch (JSONException e) {
            return null;
        }
    }

    /** "phone-offline" -> "phone offline", "pc-fionn-arch" -> "PC fionn-arch". */
    private static String deviceName(String slug) {
        if (slug.startsWith("pc-")) {
            return "PC " + slug.substring(3);
        }
        return slug.replace('-', ' ');
    }

    private static String describeSave(JSONObject summary) {
        if (!summary.optBoolean("exists")) {
            return "Save data: none yet";
        }
        if (summary.optBoolean("broken")) {
            return "Save data: present, but could not be read";
        }
        long saved = summary.optLong("timestamp");
        long minutes = Math.round(summary.optDouble("playTime", 0) / 60.0);
        return "Last saved: " + (saved > 0 ? formatTime(saved) : "unknown")
                + "\nSpecies caught: " + summary.optInt("caught")
                + "\nPlay time: " + minutes / 60 + " h " + minutes % 60 + " min";
    }

    /** One line about a run history. `other` names the side it is compared with, or null for no comparison. */
    private static String describeHistory(JSONObject summary, String other) {
        int runs = summary.optInt("runs");
        if (runs == 0) {
            return "Run history: none";
        }
        String line = "Run history: " + runs(runs);
        int extra = summary.optInt("notInOther");
        if (other != null && extra > 0) {
            line += " (" + extra + " not in " + other + ")";
        }
        return line;
    }

    /** `other` names the other side, where a run may already have ended; null for no such check. */
    private static String describeSessions(JSONArray sessions, String other) {
        return count(sessions) == 0 ? "Runs in progress: none" : "Runs in progress:\n" + slotLines(sessions, other);
    }

    private static String slotLines(JSONArray sessions, String other) {
        StringBuilder out = new StringBuilder();
        for (int slot = 0; slot < SLOTS; slot++) {
            JSONObject session = objectAt(sessions, slot);
            if (session != null) {
                out.append(out.length() == 0 ? "" : "\n").append("  Slot ").append(slot + 1).append(": ")
                        .append(describeSession(session)).append(", saved ")
                        .append(formatTime(session.optLong("timestamp")));
                JSONObject ended = other == null ? null : session.optJSONObject("ended");
                if (ended != null) {
                    out.append("\n    This run already ended ").append(other).append(", at wave ")
                            .append(ended.optInt("wave")).append(".");
                }
                if (other != null && session.optBoolean("maybeEnded")) {
                    out.append("\n    Online was played after this was saved. It may already have ended there.");
                }
            }
        }
        return out.toString();
    }

    private static String describeSession(JSONObject session) {
        return session.optString("mode", "Run") + " wave " + session.optInt("wave");
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

    private static String runs(int count) {
        return count + (count == 1 ? " run" : " runs");
    }

    private static String formatTime(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(millis));
    }

    /** One line for the restore list: when, which side, and what the backup holds. */
    private static String backupLabel(String id, JSONObject meta) {
        List<String> holds = new ArrayList<String>();
        if (meta.optJSONObject("save") != null) {
            holds.add("save data");
        }
        if (meta.optInt("runs") > 0) {
            holds.add("history of " + runs(meta.optInt("runs")));
        }
        int inProgress = count(meta.optJSONArray("sessions"));
        if (inProgress > 0) {
            holds.add(inProgress + " in progress");
        }
        return formatTime(Backups.timeOf(id)) + "\n" + meta.optString("origin", "unknown")
                + (holds.isEmpty() ? "" : ": " + String.join(", ", holds));
    }

    private static String pageName(String url) {
        return url == null ? "page" : url.startsWith(UPLOAD_PAGE) ? "upload page"
                : url.startsWith(ONLINE_PAGE) ? "online helper page"
                : url.startsWith(OFFLINE_PAGE) ? "offline helper page" : "page";
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

    /** Why the server turned something down, in plain words, with its own message when there is one. */
    private static String refusal(int status, String detail) {
        String reason;
        if (status == -1) {
            return "the connection failed (" + detail + "). Check your online save before trying again.";
        } else if (status == 0) {
            return NOT_LOGGED_IN;
        } else if (status == 401) {
            reason = "the server did not accept your login. Open Online mode, log out and back in, then try again.";
        } else if (detail.contains("trainer or secret ID")) {
            reason = "the offline save did not come from this account. Copy online to offline first, then play offline.";
        } else if (detail.contains("existing playtime is greater")) {
            reason = "the online save has more play time, so the server keeps it.";
        } else if (detail.contains("existing wave index is greater")) {
            reason = "the online run is further along, so the server keeps it.";
        } else if (detail.contains("version")) {
            reason = "the offline game and the online save are on different game versions.";
        } else {
            reason = "the server answered " + status + ".";
        }
        return "refused, " + reason + (detail.isEmpty() ? "" : " (Server message: " + detail + ")");
    }

    // ---- Scripts for the game's own pages ----

    /**
     * Runs in the online game's page. The game gives itself a client session id and
     * the server takes saves only from the id that asked last. This notes the id from
     * the game's own requests, so a sync can make its requests under the same id and
     * leave the running game's session valid. It changes nothing about the requests.
     */
    static final String CLIENT_ID_JS = String.join("\n",
            "(function() {",
            "  if (window.__saveSyncFetch || !window.fetch || !window.saveSyncGame) { return; }",
            "  window.__saveSyncFetch = true;",
            "  var original = window.fetch;",
            "  var last = '';",
            "  window.fetch = function(input) {",
            "    try {",
            "      var url = typeof input === 'string' ? input : String((input && input.url) || '');",
            "      var found = /[?&]clientSessionId=([A-Za-z0-9]{32})(?:&|$)/.exec(url);",
            "      if (found && found[1] !== last) { last = found[1]; window.saveSyncGame.clientId(last); }",
            "    } catch (e) {}",
            "    return original.apply(this, arguments);",
            "  };",
            "})();");

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
