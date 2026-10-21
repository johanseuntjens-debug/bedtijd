package nl.js.bedtijd;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.List;

/** The settings screen (a local web page) and its bridge to the phone. */
public class MainActivity extends Activity {

    private static final int REQ_VPN = 1, REQ_SOUND = 2, REQ_NOTIF = 3;
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Planner.channels(this);
        web = new WebView(this);
        setContentView(web);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        web.setBackgroundColor(0xFF141A2E);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
        });
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html");
        Planner.plan(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (web != null) web.post(() -> web.evaluateJavascript("window.App && App.refresh()", null));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SOUND && res == RESULT_OK && data != null) {
            Uri u = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            nl.js.bedtijd.Settings st = nl.js.bedtijd.Settings.load(this);
            st.sound = u != null ? u.toString() : null;
            st.save(this);
        }
        if (req == REQ_VPN) {
            // consent given now: if bedtime is running, pause the internet right away
            nl.js.bedtijd.Settings st = nl.js.bedtijd.Settings.load(this);
            if (res == RESULT_OK && st.activeUntil > System.currentTimeMillis()) Planner.begin(this, st.activeUntil);
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        refresh();
    }

    private void open(Intent i) {
        try { startActivity(i); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception ignored) { }
        }
    }

    private class Bridge {

        @JavascriptInterface
        public String state() {
            try {
                nl.js.bedtijd.Settings s = nl.js.bedtijd.Settings.load(MainActivity.this);
                long now = System.currentTimeMillis();
                JSONObject o = s.toJson(false);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                JSONObject perms = new JSONObject()
                        .put("dnd", nm.isNotificationPolicyAccessGranted())
                        .put("vpn", VpnService.prepare(MainActivity.this) == null)
                        .put("exact", Planner.canExact(MainActivity.this))
                        .put("notif", Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                        .put("fsi", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent());
                o.put("perms", perms);
                boolean active = s.activeUntil > now;
                o.put("active", active);
                if (active) o.put("until", Planner.hhmm(s.activeUntil));
                List<Planner.Window> ws = Planner.windows(s, now);
                for (Planner.Window w : ws) {
                    if (w.start > now) { o.put("nextStart", Planner.when(w.start, now)); break; }
                }
                if (!ws.isEmpty()) o.put("nextWake", Planner.when(ws.get(0).wake, now));
                String name = "Standaard wekkergeluid";
                try {
                    Ringtone r = RingtoneManager.getRingtone(MainActivity.this, AlarmService.soundUri(s));
                    if (r != null) name = r.getTitle(MainActivity.this);
                } catch (Exception ignored) { }
                o.put("soundName", name);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void save(String json) {
            try {
                nl.js.bedtijd.Settings s = nl.js.bedtijd.Settings.load(MainActivity.this);
                boolean wasActive = s.activeUntil > System.currentTimeMillis();
                s.fromJson(new JSONObject(json), false);
                s.save(MainActivity.this);
                if (wasActive) {   // apply changed choices right away
                    long until = s.activeUntil;
                    Planner.stop(MainActivity.this, false);
                    Planner.begin(MainActivity.this, until);
                }
                Planner.plan(MainActivity.this);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void startNow() {
            long now = System.currentTimeMillis();
            nl.js.bedtijd.Settings s = nl.js.bedtijd.Settings.load(MainActivity.this);
            List<Planner.Window> ws = Planner.windows(s, now);
            long until = ws.isEmpty() ? now + s.sleepMin * 60000L : ws.get(0).wake;
            Planner.begin(MainActivity.this, until);
            Planner.plan(MainActivity.this);
        }

        @JavascriptInterface
        public void stopNow() {
            Planner.stop(MainActivity.this, true);
            Planner.plan(MainActivity.this);
        }

        @JavascriptInterface
        public void testAlarm() {
            try {
                startForegroundService(new Intent(MainActivity.this, AlarmService.class).setAction(AlarmService.ACTION_TEST));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void pickSound() {
            runOnUiThread(() -> {
                nl.js.bedtijd.Settings s = nl.js.bedtijd.Settings.load(MainActivity.this);
                Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Kies je wekkergeluid")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmService.soundUri(s));
                try { startActivityForResult(i, REQ_SOUND); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void grant(final String which) {
            runOnUiThread(() -> {
                Uri pkg = Uri.parse("package:" + getPackageName());
                switch (which) {
                    case "dnd":
                        open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                        break;
                    case "vpn": {
                        Intent i = VpnService.prepare(MainActivity.this);
                        if (i != null) { try { startActivityForResult(i, REQ_VPN); } catch (Exception ignored) { } }
                        break;
                    }
                    case "exact":
                        if (Build.VERSION.SDK_INT >= 31) open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg));
                        break;
                    case "notif":
                        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                        break;
                    case "fsi":
                        if (Build.VERSION.SDK_INT >= 34) open(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg));
                        break;
                    default:
                }
            });
        }
    }
}
