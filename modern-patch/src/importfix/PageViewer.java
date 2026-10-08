package importfix;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shows one of the player's own pages ("My pages") over the game: a bar with Back and
 * Close, and the page below it. Links stay inside; the phone's Back key goes back a
 * page, and closes the viewer on the first page.
 */
final class PageViewer {
    private PageViewer() {
    }

    static void open(Activity activity, String name, String url) {
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        final WebView web = new WebView(activity);
        web.resumeTimers(); // the app may have paused every WebView's timers
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());

        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.rgb(0x1d, 0x1b, 0x20));
        int pad = Math.round(4 * density);
        bar.setPadding(pad, pad, pad, pad);

        Button back = new Button(activity);
        back.setAllCaps(false);
        back.setText("Back");
        back.setOnClickListener(view -> {
            if (web.canGoBack()) {
                web.goBack();
            } else {
                dialog.dismiss();
            }
        });
        TextView title = new TextView(activity);
        title.setText(name);
        title.setTextColor(Color.rgb(0xe6, 0xe0, 0xe9));
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER);
        Button close = new Button(activity);
        close.setAllCaps(false);
        close.setText("Close");
        close.setOnClickListener(view -> dialog.dismiss());
        bar.addView(back);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(close);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(column);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    dialog.dismiss();
                }
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_BACK;
        });
        dialog.setOnDismissListener(d -> web.destroy());
        web.loadUrl(url);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }
}
