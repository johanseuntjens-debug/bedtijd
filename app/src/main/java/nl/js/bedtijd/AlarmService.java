package nl.js.bedtijd;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** The ringing alarm: sound that slowly gets louder, vibration, and a full-screen wake-up page. */
public class AlarmService extends Service {

    static final String ACTION_RING = "nl.js.bedtijd.RING";
    static final String ACTION_SNOOZE = "nl.js.bedtijd.SNOOZE";
    static final String ACTION_DISMISS = "nl.js.bedtijd.DISMISS";
    static final String ACTION_TEST = "nl.js.bedtijd.TEST";

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock lock;
    private final Handler h = new Handler(Looper.getMainLooper());
    private float vol = 0.08f;

    static void ring(Context c) {
        Intent i = new Intent(c, AlarmService.class).setAction(ACTION_RING);
        try { c.startForegroundService(i); } catch (Exception e) {
            Planner.help(c, "Wekker", "Goedemorgen! Tik om de app te openen.");
        }
    }

    static Uri soundUri(Settings s) {
        if (s.sound != null) {
            try { return Uri.parse(s.sound); } catch (Exception ignored) { }
        }
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return u != null ? u : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : ACTION_RING;
        if (ACTION_SNOOZE.equals(a)) { snooze(); return START_NOT_STICKY; }
        if (ACTION_DISMISS.equals(a)) { quiet(); stopSelf(); return START_NOT_STICKY; }

        boolean test = ACTION_TEST.equals(a);
        Notification n = notification(test);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Planner.NOTIF_ALARM, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(Planner.NOTIF_ALARM, n);
        }
        start(test);
        return START_NOT_STICKY;
    }

    private Notification notification(boolean test) {
        Planner.channels(this);
        Intent full = new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(this, 20, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent snoozePi = PendingIntent.getService(this, 21, new Intent(this, AlarmService.class).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPi = PendingIntent.getService(this, 22, new Intent(this, AlarmService.class).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Settings s = Settings.load(this);
        return new Notification.Builder(this, Planner.CH_ALARM)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(test ? "Proef: wekker" : "Goedemorgen!")
                .setContentText("Het is " + Planner.hhmm(System.currentTimeMillis()))
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .addAction(new Notification.Action.Builder(null, "Nog " + s.snoozeMin + " min", snoozePi).build())
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    private void start(boolean test) {
        release();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bedtijd:wekker");
        lock.acquire(11 * 60 * 1000L);
        Settings s = Settings.load(this);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, soundUri(s));
            player.setLooping(true);
            vol = 0.08f;
            player.setVolume(vol, vol);
            player.prepare();
            player.start();
            h.post(fade);
        } catch (Exception e) {
            player = null;
        }
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 900}, 0),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            }
        } catch (Exception ignored) { }
        // stop by itself after 10 minutes (5 seconds for a test)
        h.postDelayed(() -> { quiet(); stopSelf(); }, test ? 5000 : 10 * 60 * 1000);
        try { startActivity(new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    private final Runnable fade = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            vol = Math.min(1f, vol + 0.03f);   // full volume after about 30 seconds
            try { player.setVolume(vol, vol); } catch (Exception ignored) { }
            if (vol < 1f) h.postDelayed(this, 1000);
        }
    };

    private void snooze() {
        Settings s = Settings.load(this);
        quiet();
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        long at = System.currentTimeMillis() + s.snoozeMin * 60000L;
        PendingIntent pi = PendingIntent.getBroadcast(this, 3,
                new Intent(this, EventReceiver.class).setAction(Planner.ACTION_SNOOZE_RING),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Planner.canExact(this)) am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, Planner.openApp(this)), pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        stopSelf();
    }

    private void quiet() {
        release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        sendBroadcast(new Intent(AlarmActivity.ACTION_CLOSE).setPackage(getPackageName()));
    }

    private void release() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
        if (vibrator != null) vibrator.cancel();
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
    }

    @Override
    public void onDestroy() {
        quiet();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
