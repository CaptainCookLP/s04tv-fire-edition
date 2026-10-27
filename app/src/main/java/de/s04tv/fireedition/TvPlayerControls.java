package de.s04tv.fireedition;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native, D-pad focusable Fire TV controls, independent from hidden 3Q DOM buttons. */
final class TvPlayerControls {
    interface Listener { void onAction(String action); }

    private final Activity activity;
    private final FrameLayout root;
    private final Listener listener;
    private FrameLayout surface;

    TvPlayerControls(Activity activity, FrameLayout root, Listener listener) {
        this.activity = activity;
        this.root = root;
        this.listener = listener;
    }

    boolean isShowing() { return surface != null; }

    private int dp(int px) {
        return Math.round(px * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(2), Color.rgb(0, 177, 235));
        return d;
    }

    private Button add(LinearLayout bar, String title, String command) {
        Button button = new Button(activity);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setMinHeight(dp(60));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_focused}, background(Color.rgb(0, 105, 170)));
        state.addState(new int[]{android.R.attr.state_pressed}, background(Color.rgb(0, 105, 170)));
        state.addState(new int[]{}, background(Color.rgb(6, 31, 57)));
        button.setBackground(state);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(60), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(button, lp);
        button.setOnClickListener(v -> listener.onAction(command));
        return button;
    }

    void show() {
        if (surface != null) return;
        surface = new FrameLayout(activity);
        surface.setFocusable(false);
        surface.setClickable(false);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(surface, overlayParams);

        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(24), dp(12), dp(24), dp(22));
        block.setBackgroundColor(Color.argb(237, 3, 19, 37));
        FrameLayout.LayoutParams dock = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        surface.addView(block, dock);

        TextView heading = new TextView(activity);
        heading.setText("S04TV+  •  Playersteuerung     ▲ Einstellungen  •  ▼ Steuerung schließen");
        heading.setTextColor(Color.rgb(170, 212, 240));
        heading.setTextSize(16);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, dp(36));
        block.addView(heading, headingParams);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        block.addView(bar, new LinearLayout.LayoutParams(-1, dp(60)));
        add(bar, "−10 s", "back10");
        Button play = add(bar, "▶ / ❚❚", "toggle");
        add(bar, "+10 s", "next10");
        add(bar, "Vollbild", "fullscreen");
        add(bar, "Info", "diagnose");
        add(bar, "Schließen", "close");
        surface.bringToFront();
        play.requestFocus();
    }

    void hide() {
        if (surface == null) return;
        root.removeView(surface);
        surface = null;
    }
}
