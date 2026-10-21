package nl.js.bedtijd;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Works out the bedtime windows from the wake-up times and switches bedtime on and off. */
final class Planner {

    static final String ACTION_START = "nl.js.bedtijd.START";
    static final String ACTION_WAKE = "nl.js.bedtijd.WAKE";
    static final String ACTION_SNOOZE_RING = "nl.js.bedtijd.SNOOZE_RING";
    static final String ACTION_BEGIN_NOW = "nl.js.bedtijd.BEGIN_NOW";
    static final String ACTION_REMIND = "nl.js.bedtijd.REMIND";
    static final String ACTION_SKIP_TONIGHT = "nl.js.bedtijd.SKIP_TONIGHT";
    static final String CH_ASK = "vraag";
    static final String CH_BED = "bedtijd";
    static final String CH_ALARM = "wekker";
    static final int NOTIF_BED = 1, NOTIF_ALARM = 2, NOTIF_HELP = 3, NOTIF_ASK = 4;

    /** One night: bedtime starts at {@code start}, the alarm goes at {@code wake}. */
    static final class Window {
        final long start, wake;
        Window(long s, long w) { start = s; wake = w; }
    }

    static List<Window> windows(Settings s, long now) {
        List<Window> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -1);
        for (int i = 0; i < 10; i++) {
            int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday = 0
            if (s.dayOn[dow]) {
                Calendar w = (Calendar) c.clone();
                w.set(Calendar.HOUR_OF_DAY, s.wakeMin[dow] / 60);
                w.set(Calendar.MINUTE, s.wakeMin[dow] % 60);
                long wake = w.getTimeInMillis();
                if (wake > now) out.add(new Window(wake - s.sleepMin * 60000L, wake));
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }

    static String hhmm(long t) {
        return new SimpleDateFormat("HH:mm", new Locale("nl", "NL")).format(t);
    }

    static String when(long t, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(now); b.setTimeInMillis(t);
        int days = (int) Math.round((startOfDay(b) - startOfDay(a)) / 86400000.0);
        String day = days == 0 ? "vandaag" : days == 1 ? "morgen" : days == -1 ? "gisteren"
                : new SimpleDateFormat("EEEE", new Locale("nl", "NL")).format(t);
        if (days == 0 && b.get(Calendar.HOUR_OF_DAY) >= 18) day = "vanavond";
        return day + " " + hhmm(t);
    }

    private static long startOfDay(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    private static PendingIntent pi(Context c, String action, int code) {
        Intent i = new Intent(c, EventReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 10, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean canExact(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    /** Set the next start and wake-up alarms; start bedtime now if we are inside a window. */
    static void plan(Context c) {
        Settings s = Settings.load(c);
        long now = System.currentTimeMillis();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent start = pi(c, ACTION_START, 1), wake = pi(c, ACTION_WAKE, 2);
        am.cancel(start);
        am.cancel(wake);

        // a running bedtime that has passed its end (phone was off, alarm missed): end it
        if (s.activeUntil > 0 && s.activeUntil <= now) {
            stop(c, false);
            s = Settings.load(c);
        }

        List<Window> ws = windows(s, now);
        long nextWake = s.activeUntil > now ? s.activeUntil : 0;
        long nextStart = 0;
        for (Window w : ws) {
            boolean inside = w.start <= now && now < w.wake;
            if (inside && s.activeUntil <= now && s.skipUntil < w.wake) {
                if (s.askFirst) {
                    if (s.promptedFor != w.wake) ask(c, w);   // a notification with a Start button
                } else {
                    begin(c, w.wake);
                }
                s = Settings.load(c);
                if (nextWake == 0) nextWake = w.wake;
                continue;
            }
            if (inside) { if (nextWake == 0) nextWake = w.wake; continue; }
            if (nextWake == 0) nextWake = w.wake;
            if (w.start > now && nextStart == 0) nextStart = w.start;
            if (nextStart != 0) break;
        }

        if (nextWake > 0) {
            AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(nextWake, openApp(c));
            if (canExact(c)) am.setAlarmClock(info, wake);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWake, wake);
        }
        if (nextStart > 0) {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextStart, start);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextStart, start);
        }
    }

    /** The night that is going on right now (bedtime window we are in), or null. */
    static Window current(Settings s, long now) {
        for (Window w : windows(s, now)) if (w.start <= now && now < w.wake) return w;
        return null;
    }

    /** It is bedtime: ask with a notification instead of starting by itself. */
    static void ask(Context c, Window w) {
        Settings s = Settings.load(c);
        s.promptedFor = w.wake;
        s.save(c);
        channels(c);
        Notification n = new Notification.Builder(c, CH_ASK)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Het is bedtijd")
                .setContentText("Wekker om " + hhmm(w.wake) + ". Tik op Start om internet en oproepen uit te zetten.")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Start", pi(c, ACTION_BEGIN_NOW, 5)).build())
                .addAction(new Notification.Action.Builder(null, "Over 15 min", pi(c, ACTION_REMIND, 6)).build())
                .addAction(new Notification.Action.Builder(null, "Vannacht niet", pi(c, ACTION_SKIP_TONIGHT, 7)).build())
                .build();
        try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ASK, n); } catch (Exception ignored) { }
    }

    /** "Over 15 min": ask again in a quarter of an hour. */
    static void remindLater(Context c) {
        Settings s = Settings.load(c);
        s.promptedFor = 0;
        s.save(c);
        cancelAsk(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        long at = System.currentTimeMillis() + 15 * 60000L;
        PendingIntent p = pi(c, ACTION_START, 4);
        if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
    }

    static void cancelAsk(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ASK);
    }

    /** Switch bedtime on until {@code until}: calls silent, internet blocked. */
    static void begin(Context c, long until) {
        cancelAsk(c);
        Settings s = Settings.load(c);
        s.activeUntil = until;
        s.skipUntil = 0;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if ((s.blockCalls || s.blockMessages) && nm.isNotificationPolicyAccessGranted()) {
            if (s.savedFilter < 0) {
                s.savedFilter = nm.getCurrentInterruptionFilter();
                s.savedPolicy = policyToString(nm.getNotificationPolicy());
            }
            nm.setNotificationPolicy(nightPolicy(s));
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        }
        s.save(c);
        boolean vpnReady = VpnService.prepare(c) == null;
        if (s.blockNet && vpnReady) {
            try {
                Intent i = new Intent(c, BlockVpnService.class).setAction(BlockVpnService.ACTION_ON);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } catch (Exception e) {
                help(c, "Tik hier om het internet te pauzeren", "De app mocht het internet niet vanzelf uitzetten.");
            }
        } else {
            showBedNotification(c, s);
        }
    }

    /** Switch bedtime off. {@code byUser}: don't restart tonight. */
    static void stop(Context c, boolean byUser) {
        Settings s = Settings.load(c);
        if (byUser && s.activeUntil > System.currentTimeMillis()) s.skipUntil = s.activeUntil;
        s.activeUntil = 0;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (s.savedFilter >= 0 && nm.isNotificationPolicyAccessGranted()) {
            try {
                NotificationManager.Policy p = policyFromString(s.savedPolicy);
                if (p != null) nm.setNotificationPolicy(p);
                nm.setInterruptionFilter(s.savedFilter);
            } catch (Exception ignored) { }
        }
        s.savedFilter = -1;
        s.savedPolicy = null;
        s.save(c);
        try {
            c.startService(new Intent(c, BlockVpnService.class).setAction(BlockVpnService.ACTION_OFF));
        } catch (Exception ignored) { }
        nm.cancel(NOTIF_BED);
    }

    /** Do Not Disturb for the night: alarms always ring; calls and messages as chosen. */
    private static NotificationManager.Policy nightPolicy(Settings s) {
        int cats = 0, callers = NotificationManager.Policy.PRIORITY_SENDERS_STARRED;
        int texters = NotificationManager.Policy.PRIORITY_SENDERS_STARRED;
        if (Build.VERSION.SDK_INT >= 28) cats |= NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS;
        if (!s.blockCalls) {
            cats |= NotificationManager.Policy.PRIORITY_CATEGORY_CALLS;
            callers = NotificationManager.Policy.PRIORITY_SENDERS_ANY;
        } else {
            if (s.allowFavorites) cats |= NotificationManager.Policy.PRIORITY_CATEGORY_CALLS;
            if (s.allowRepeat) cats |= NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS;
        }
        if (!s.blockMessages) {
            cats |= NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES;
            texters = NotificationManager.Policy.PRIORITY_SENDERS_ANY;
            if (Build.VERSION.SDK_INT >= 30) cats |= NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS;
        }
        if (Build.VERSION.SDK_INT < 28) return new NotificationManager.Policy(cats, callers, texters);
        // messages: no sound, no pop-up, not in the status bar or the list until bedtime is over
        int hide = s.blockMessages ? (NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK
                | NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR
                | NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE
                | NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT
                | NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS
                | NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST) : 0;
        if (Build.VERSION.SDK_INT >= 30) {
            return new NotificationManager.Policy(cats, callers, texters, hide,
                    s.blockMessages ? NotificationManager.Policy.CONVERSATION_SENDERS_NONE
                            : NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE);
        }
        return new NotificationManager.Policy(cats, callers, texters, hide);
    }

    private static String policyToString(NotificationManager.Policy p) {
        if (p == null) return null;
        int fx = Build.VERSION.SDK_INT >= 28 ? p.suppressedVisualEffects : 0;
        int conv = Build.VERSION.SDK_INT >= 30 ? p.priorityConversationSenders : 0;
        return p.priorityCategories + "," + p.priorityCallSenders + "," + p.priorityMessageSenders + "," + fx + "," + conv;
    }

    private static NotificationManager.Policy policyFromString(String v) {
        if (v == null) return null;
        String[] a = v.split(",");
        int cats = Integer.parseInt(a[0]), calls = Integer.parseInt(a[1]), msgs = Integer.parseInt(a[2]);
        if (Build.VERSION.SDK_INT >= 30 && a.length > 4) {
            return new NotificationManager.Policy(cats, calls, msgs, Integer.parseInt(a[3]), Integer.parseInt(a[4]));
        }
        if (Build.VERSION.SDK_INT >= 28 && a.length > 3) {
            return new NotificationManager.Policy(cats, calls, msgs, Integer.parseInt(a[3]));
        }
        return new NotificationManager.Policy(cats, calls, msgs);
    }

    static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel bed = new NotificationChannel(CH_BED, "Bedtijd", NotificationManager.IMPORTANCE_LOW);
        bed.setDescription("Laat zien dat bedtijd aan staat");
        bed.setShowBadge(false);
        NotificationChannel ask = new NotificationChannel(CH_ASK, "Het is bedtijd", NotificationManager.IMPORTANCE_HIGH);
        ask.setDescription("Vraagt of bedtijd mag beginnen");
        nm.createNotificationChannel(ask);
        NotificationChannel alarm = new NotificationChannel(CH_ALARM, "Wekker", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("De wekker die afgaat");
        alarm.setSound(null, null);
        alarm.setBypassDnd(true);
        nm.createNotificationChannel(bed);
        nm.createNotificationChannel(alarm);
    }

    static Notification bedNotification(Context c, Settings s) {
        channels(c);
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (s.blockNet) parts.add("internet uit");
        if (s.blockCalls) parts.add("oproepen stil");
        if (s.blockMessages) parts.add("berichten stil");
        String what = parts.isEmpty() ? "Welterusten" : Character.toUpperCase(parts.get(0).charAt(0)) + String.join(", ", parts).substring(1);
        Intent stop = new Intent(c, EventReceiver.class).setAction(EventReceiver.ACTION_STOP_BED);
        PendingIntent stopPi = PendingIntent.getBroadcast(c, 11, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CH_BED)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Bedtijd tot " + hhmm(s.activeUntil))
                .setContentText(what)
                .setOngoing(true)
                .setContentIntent(openApp(c))
                .addAction(new Notification.Action.Builder(null, "Bedtijd stoppen", stopPi).build())
                .build();
    }

    static void showBedNotification(Context c, Settings s) {
        try {
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_BED, bedNotification(c, s));
        } catch (Exception ignored) { }
    }

    static void help(Context c, String title, String text) {
        channels(c);
        try {
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_HELP,
                    new Notification.Builder(c, CH_BED).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setContentTitle(title).setContentText(text).setAutoCancel(true)
                            .setContentIntent(openApp(c)).build());
        } catch (Exception ignored) { }
    }
}
