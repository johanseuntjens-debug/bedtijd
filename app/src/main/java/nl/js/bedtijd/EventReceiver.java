package nl.js.bedtijd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Receives the planned moments (bedtime start, wake-up) and phone restarts. */
public class EventReceiver extends BroadcastReceiver {

    static final String ACTION_STOP_BED = "nl.js.bedtijd.STOP_BED";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        long now = System.currentTimeMillis();
        switch (a) {
            case Planner.ACTION_START:
                Planner.plan(c);   // starts bedtime: we are inside the window now
                break;
            case Planner.ACTION_WAKE: {
                Settings s = Settings.load(c);
                boolean ring = s.alarmOn && isWakeMoment(s, now);
                Planner.stop(c, false);
                if (ring) AlarmService.ring(c);
                Planner.plan(c);
                break;
            }
            case Planner.ACTION_SNOOZE_RING:
                AlarmService.ring(c);
                break;
            case ACTION_STOP_BED:
                Planner.stop(c, true);
                Planner.plan(c);
                break;
            default:   // boot, time or time zone changed, app updated
                Planner.plan(c);
        }
    }

    /** True when a wake-up time from the week schedule is now (within two minutes). */
    private static boolean isWakeMoment(Settings s, long now) {
        for (Planner.Window w : Planner.windows(s, now - 120000)) {
            if (Math.abs(w.wake - now) < 120000) return true;
        }
        return false;
    }
}
