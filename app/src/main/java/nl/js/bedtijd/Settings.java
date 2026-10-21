package nl.js.bedtijd;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Everything the user chose, plus the little state the app keeps between events. */
final class Settings {

    static final String[] DAYS = {"Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo"};

    boolean[] dayOn = {true, true, true, true, true, false, false};
    int[] wakeMin = {420, 420, 420, 420, 420, 510, 510};   // minutes after midnight, per day (Mon..Sun)
    int sleepMin = 480;                                    // how long to sleep
    boolean blockNet = true;
    boolean blockCalls = true;
    boolean allowFavorites = true;
    boolean allowRepeat = true;
    boolean alarmOn = true;
    int snoozeMin = 9;
    String sound = null;                                   // null = default alarm sound

    // state
    long activeUntil = 0;     // bedtime running until this moment (0 = not running)
    long skipUntil = 0;       // user stopped tonight's bedtime: don't restart before this
    int savedFilter = -1;     // Do Not Disturb as it was before bedtime
    String savedPolicy = null;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bedtijd", Context.MODE_PRIVATE);
    }

    static Settings load(Context c) {
        Settings s = new Settings();
        try {
            String raw = prefs(c).getString("settings", null);
            if (raw != null) s.fromJson(new JSONObject(raw), true);
        } catch (Exception ignored) { }
        return s;
    }

    void save(Context c) {
        try {
            prefs(c).edit().putString("settings", toJson(true).toString()).apply();
        } catch (Exception ignored) { }
    }

    JSONObject toJson(boolean withState) throws Exception {
        JSONObject o = new JSONObject();
        JSONArray days = new JSONArray();
        for (int i = 0; i < 7; i++) {
            days.put(new JSONObject().put("on", dayOn[i]).put("wake", wakeMin[i]));
        }
        o.put("days", days).put("sleepMin", sleepMin).put("blockNet", blockNet).put("blockCalls", blockCalls)
                .put("allowFavorites", allowFavorites).put("allowRepeat", allowRepeat).put("alarmOn", alarmOn)
                .put("snoozeMin", snoozeMin);
        if (sound != null) o.put("sound", sound);
        if (withState) {
            o.put("activeUntil", activeUntil).put("skipUntil", skipUntil).put("savedFilter", savedFilter);
            if (savedPolicy != null) o.put("savedPolicy", savedPolicy);
        }
        return o;
    }

    void fromJson(JSONObject o, boolean withState) {
        JSONArray days = o.optJSONArray("days");
        if (days != null) {
            for (int i = 0; i < 7 && i < days.length(); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d == null) continue;
                dayOn[i] = d.optBoolean("on", dayOn[i]);
                wakeMin[i] = Math.max(0, Math.min(1439, d.optInt("wake", wakeMin[i])));
            }
        }
        sleepMin = Math.max(60, Math.min(14 * 60, o.optInt("sleepMin", sleepMin)));
        blockNet = o.optBoolean("blockNet", blockNet);
        blockCalls = o.optBoolean("blockCalls", blockCalls);
        allowFavorites = o.optBoolean("allowFavorites", allowFavorites);
        allowRepeat = o.optBoolean("allowRepeat", allowRepeat);
        alarmOn = o.optBoolean("alarmOn", alarmOn);
        snoozeMin = Math.max(1, Math.min(30, o.optInt("snoozeMin", snoozeMin)));
        if (o.has("sound")) sound = o.optString("sound", null);
        if (withState) {
            activeUntil = o.optLong("activeUntil", 0);
            skipUntil = o.optLong("skipUntil", 0);
            savedFilter = o.optInt("savedFilter", -1);
            savedPolicy = o.has("savedPolicy") ? o.optString("savedPolicy", null) : null;
        }
    }
}
