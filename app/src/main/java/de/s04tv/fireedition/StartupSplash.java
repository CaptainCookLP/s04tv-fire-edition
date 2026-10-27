package de.s04tv.fireedition;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Full-screen native loading surface shown until update check and the first page load finish. */
final class StartupSplash extends FrameLayout {
    private final TextView status;
    private final TextView percent;
    private final ProgressBar indicator;
    private final TextView updateStatus;

    StartupSplash(Activity activity) {
        super(activity);
        setBackgroundColor(Color.rgb(4, 17, 34));
        setClickable(true);
        setFocusable(true);
        int d = Math.round(activity.getResources().getDisplayMetrics().density * 1.0f);

        LinearLayout center = new LinearLayout(activity);
        center.setGravity(Gravity.CENTER);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setPadding(32*d,20*d,32*d,20*d);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        addView(center, params);

        TextView brand = new TextView(activity);
        brand.setText("S04TV+");
        brand.setTextColor(Color.rgb(0, 177, 235));
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setTextSize(54);
        brand.setGravity(Gravity.CENTER);
        center.addView(brand, new LinearLayout.LayoutParams(-1, 90*d));

        TextView subtitle = new TextView(activity);
        subtitle.setText("FIRE EDITION");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setLetterSpacing(.24f);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        center.addView(subtitle, new LinearLayout.LayoutParams(-1, 34*d));

        indicator = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        indicator.setIndeterminate(true);
        indicator.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(0, 177, 235)));
        indicator.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(0, 177, 235)));
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(350*d, 5*d);
        progressLayout.topMargin = 36*d;
        center.addView(indicator, progressLayout);

        status = new TextView(activity);
        status.setText("Prüfe gespeicherte Inhalte …");
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(18);
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, 48*d);
        statusLayout.topMargin=12*d;
        center.addView(status, statusLayout);

        percent = new TextView(activity);
        percent.setText("");
        percent.setTextColor(Color.rgb(170, 190, 210));
        percent.setGravity(Gravity.CENTER);
        percent.setTextSize(14);
        center.addView(percent, new LinearLayout.LayoutParams(-1, 24*d));
        updateStatus = new TextView(activity);
        updateStatus.setText("Updateprüfung läuft parallel …");
        updateStatus.setTextColor(Color.rgb(149, 178, 207));
        updateStatus.setGravity(Gravity.CENTER);
        updateStatus.setTextSize(14);
        LinearLayout.LayoutParams updateLayout = new LinearLayout.LayoutParams(-1, 34*d);
        updateLayout.topMargin = 10*d;
        center.addView(updateStatus, updateLayout);
    }

    void status(String message) { status.setText(message); }

    void updateStatus(String message) { updateStatus.setText(message); }

    void progress(int progress) {
        indicator.setIndeterminate(false);
        indicator.setProgress(Math.max(0, Math.min(100, progress)));
        percent.setText(progress + " %");
    }

    void dismiss() {
        if (getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(this);
    }
}
