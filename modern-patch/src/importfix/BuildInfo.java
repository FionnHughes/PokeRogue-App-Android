package importfix;

/** What this build is and where newer ones are announced. */
final class BuildInfo {
    /**
     * This build's number. The workflow that builds the APK writes its run number
     * here, so later builds have higher numbers. It stays 0 in a build made any other
     * way, and such a build never offers to update itself.
     */
    static final int BUILD = 0;

    /**
     * A small JSON file describing the newest build, on the owner's own server:
     * {"build": 12, "apk": "app.apk", "sha256": "...", "size": 123, "notes": "..."}.
     * "apk" is relative to this file.
     */
    static final String UPDATE_URL = "https://fionnhughes.dev/pr/latest.json";

    /** The store that shares run history between the owner's devices (see HistoryHub), ending in "/". */
    static final String HISTORY_URL = "https://fionnhughes.dev/pr/h/";

    private BuildInfo() {
    }
}
