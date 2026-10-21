package nl.js.bedtijd;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen wake-up page, also over the lock screen: the time, Snooze and Stop. */
public class AlarmActivity extends Activity {

    static final String ACTION_CLOSE = "nl.js.bedtijd.CLOSE_ALARM";

    private final BroadcastReceiver closer = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#141a2e"));
        root.setPadding((int) (24 * dp), (int) (24 * dp), (int) (24 * dp), (int) (24 * dp));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher_round);
        root.addView(logo, new LinearLayout.LayoutParams((int) (96 * dp), (int) (96 * dp)));

        TextView hi = new TextView(this);
        hi.setText("Goedemorgen!");
        hi.setTextColor(Color.parseColor("#f6d58e"));
        hi.setTextSize(26);
        hi.setGravity(Gravity.CENTER);
        hi.setPadding(0, (int) (20 * dp), 0, 0);
        root.addView(hi);

        TextView time = new TextView(this);
        time.setText(Planner.hhmm(System.currentTimeMillis()));
        time.setTextColor(Color.WHITE);
        time.setTextSize(88);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setGravity(Gravity.CENTER);
        root.addView(time);

        Settings s = Settings.load(this);
        Button snooze = button("Nog " + s.snoozeMin + " minuten", "#2b3558", dp);
        snooze.setOnClickListener(v -> { send(AlarmService.ACTION_SNOOZE); finish(); });
        Button stop = button("Stop", "#e8a33d", dp);
        stop.setTextColor(Color.parseColor("#141a2e"));
        stop.setOnClickListener(v -> { send(AlarmService.ACTION_DISMISS); finish(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (64 * dp));
        lp.topMargin = (int) (16 * dp);
        root.addView(snooze, lp);
        root.addView(stop, new LinearLayout.LayoutParams(lp));
        setContentView(root);

        IntentFilter f = new IntentFilter(ACTION_CLOSE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closer, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(closer, f);
    }

    private Button button(String text, String color, float dp) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(18 * dp);
        b.setBackground(bg);
        return b;
    }

    private void send(String action) {
        try { startService(new Intent(this, AlarmService.class).setAction(action)); } catch (Exception ignored) { }
    }

    @Override
    public void onBackPressed() { /* use Snooze or Stop */ }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(closer); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
