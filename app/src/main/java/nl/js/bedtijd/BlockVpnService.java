package nl.js.bedtijd;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;

/**
 * Pauses the internet (wifi and mobile) during bedtime: a local VPN that takes all traffic
 * and sends it nowhere. Nothing leaves the phone and nothing is logged.
 */
public class BlockVpnService extends VpnService {

    static final String ACTION_ON = "nl.js.bedtijd.VPN_ON";
    static final String ACTION_OFF = "nl.js.bedtijd.VPN_OFF";

    /** True while the internet is really paused (the tunnel is up). */
    static volatile boolean running = false;
    static volatile String lastError = null;

    private ParcelFileDescriptor tun;
    private Thread drain;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_OFF.equals(a)) {
            close();
            stopSelf(startId);   // a newer "on" request keeps the service alive
            return START_NOT_STICKY;
        }
        Settings s = Settings.load(this);
        Notification n = Planner.bedNotification(this, s);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Planner.NOTIF_BED, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Planner.NOTIF_BED, n);
        }
        if (s.activeUntil <= System.currentTimeMillis()) {   // bedtime already over
            close();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        open();
        return START_STICKY;
    }

    private synchronized void open() {
        if (tun != null) return;
        try {
            Builder b = new Builder()
                    .setSession("Bedtijd")
                    .addAddress("10.111.222.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fd00:bed:7::1", 128)
                    .addRoute("::", 0)
                    .setBlocking(true);
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) { }
            tun = b.establish();
            lastError = tun == null ? "Android gaf geen toestemming voor de VPN" : null;
        } catch (Exception e) {
            tun = null;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        running = tun != null;
        if (tun == null) return;
        final ParcelFileDescriptor fd = tun;
        drain = new Thread(() -> {
            byte[] buf = new byte[32767];
            try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
                while (!Thread.currentThread().isInterrupted()) {
                    if (in.read(buf) < 0) break;   // read and drop: the traffic goes nowhere
                }
            } catch (Exception ignored) { }
        }, "bedtijd-vpn");
        drain.start();
    }

    private synchronized void close() {
        if (drain != null) drain.interrupt();
        drain = null;
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) { }
        }
        tun = null;
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override
    public void onRevoke() {   // user switched the VPN off in Android settings
        close();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        close();
        super.onDestroy();
    }
}
