package nl.js.bedtijd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart, a clock change or an app update: plan the alarms again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Planner.plan(c);
    }
}
