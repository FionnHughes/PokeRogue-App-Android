package importfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Offers a newer build of this patched app when one is announced at
 * {@link BuildInfo#UPDATE_URL}, then downloads it, checks it and hands it to
 * Android's installer, which asks the user before replacing the app.
 *
 * Android only accepts the update if it is signed with the same key as the
 * installed app, which every build from the workflow is.
 *
 * If anything on the way fails, the dialog says what and offers to download the
 * file in the browser instead.
 */
final class Updater {
    private static final String RESULT_ACTION = "importfix.UPDATE_RESULT";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Activity activity;
    private final UpdateFiles.Latest latest;
    private AlertDialog dialog;
    private BroadcastReceiver receiver;
    private volatile boolean cancelled;

    private Updater(Activity activity, UpdateFiles.Latest latest) {
        this.activity = activity;
        this.latest = latest;
    }

    /** Looks for a newer build in the background. Says nothing unless there is one. */
    static void check(final Activity activity) {
        if (BuildInfo.BUILD <= 0 || BuildInfo.UPDATE_URL.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                new File(activity.getCacheDir(), "update.apk").delete(); // left over from an earlier update
                final UpdateFiles.Latest latest = UpdateFiles.latest(BuildInfo.UPDATE_URL);
                if (latest.build > BuildInfo.BUILD) {
                    MAIN.post(() -> new Updater(activity, latest).offer());
                }
            } catch (IOException | JSONException | RuntimeException e) {
                // no connection, no file, or not a description of a build: nothing to offer
            }
        }, "update-check").start();
    }

    // ---- The dialogs, on the main thread ----

    private void offer() {
        String notes = latest.notes.trim();
        show(new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("Build " + latest.build + " of this app is ready. You have build " + BuildInfo.BUILD + "."
                        + (notes.isEmpty() ? "" : "\n\n" + notes)
                        + "\n\nYour saves and backups stay as they are.")
                .setPositiveButton("Update now", (d, which) -> download())
                .setNegativeButton("Later", null));
    }

    private void download() {
        cancelled = false;
        show(new AlertDialog.Builder(activity)
                .setTitle("Updating")
                .setMessage("Downloading the update...")
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, which) -> cancelled = true));
        new Thread(() -> {
            try {
                final File apk = new File(activity.getCacheDir(), "update.apk");
                UpdateFiles.download(latest, apk, new UpdateFiles.Watcher() {
                    @Override
                    public void progress(final int percent) {
                        MAIN.post(() -> Updater.this.progress(percent));
                    }

                    @Override
                    public boolean cancelled() {
                        return cancelled;
                    }
                });
                MAIN.post(() -> install(apk));
            } catch (final IOException | RuntimeException e) {
                MAIN.post(() -> failed("The download did not work: " + e.getMessage() + "."));
            }
        }, "update-download").start();
    }

    private void progress(int percent) {
        if (dialog != null && dialog.isShowing() && percent >= 0 && !cancelled) {
            dialog.setMessage("Downloading the update... " + percent + "%");
        }
    }

    /** Hands the checked APK to Android's installer, which then asks the user. */
    private void install(File apk) {
        if (cancelled) {
            close();
            return;
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.setMessage("Handing the update to Android...");
        }
        try {
            final Context app = activity.getApplicationContext();
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onResult(intent);
                }
            };
            IntentFilter filter = new IntentFilter(RESULT_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }

            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.getPackageName());
            int id = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(id);
            boolean committed = false;
            try {
                OutputStream out = session.openWrite("update", 0, apk.length());
                InputStream in = new FileInputStream(apk);
                try {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                    }
                    session.fsync(out);
                } finally {
                    in.close();
                    out.close();
                }
                // Android reports back through this: first that it wants to ask the user, later the outcome.
                Intent result = new Intent(RESULT_ACTION).setPackage(app.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                session.commit(PendingIntent.getBroadcast(app, id, result, flags).getIntentSender());
                committed = true;
            } finally {
                if (!committed) {
                    session.abandon();
                }
                session.close();
            }
        } catch (IOException | RuntimeException e) {
            failed("Android did not take the update: " + e.getMessage() + ".");
        }
    }

    private void onResult(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                dismiss();
                activity.startActivity(confirm);
            } catch (RuntimeException e) {
                failed("Android's install screen could not be opened: " + e.getMessage() + ".");
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            close(); // normally never seen: Android stops the app to replace it
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            close(); // the user said no
        } else {
            String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            failed("Android did not install the update (" + (detail == null ? "status " + status : detail) + ").");
        }
    }

    private void failed(String message) {
        if (cancelled) {
            close();
            return;
        }
        release();
        show(new AlertDialog.Builder(activity)
                .setTitle("Update")
                .setMessage(message + "\n\nNothing was changed. You can download the file in your browser"
                        + " and open it from there instead.")
                .setPositiveButton("Download in browser", (d, which) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(latest.apkUrl)));
                    } catch (RuntimeException e) {
                        // no browser to open it with
                    }
                })
                .setNegativeButton("Close", null));
    }

    /** Shows one dialog at a time. If the activity can no longer show one, the update is dropped. */
    private void show(AlertDialog.Builder builder) {
        dismiss();
        if (activity.isFinishing() || activity.isDestroyed()) {
            release();
            return;
        }
        try {
            dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        } catch (RuntimeException e) {
            dialog = null;
            release();
        }
    }

    private void dismiss() {
        try {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (RuntimeException e) {
            // the dialog's window is already gone
        }
        dialog = null;
    }

    private void close() {
        dismiss();
        release();
    }

    private void release() {
        if (receiver != null) {
            try {
                activity.getApplicationContext().unregisterReceiver(receiver);
            } catch (RuntimeException e) {
                // not registered any more
            }
            receiver = null;
        }
    }
}
