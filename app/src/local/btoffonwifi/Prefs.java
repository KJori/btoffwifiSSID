package local.btoffonwifi;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    boolean enabled() { return sp.getBoolean("enabled", false); }
    void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    String ssid() { return sp.getString("ssid", ""); }
    void setSsid(String v) { sp.edit().putString("ssid", v).apply(); }

    int stepMinutes() { return sp.getInt("step", 30); }
    void setStepMinutes(int v) { sp.edit().putInt("step", v).apply(); }

    /** End of the manual "keep Bluetooth on" timer, wall-clock ms; 0 = none. */
    long until() { return sp.getLong("until", 0); }
    void setUntil(long v) { sp.edit().putLong("until", v).apply(); }

    long lastPress() { return sp.getLong("lastPress", 0); }
    void setLastPress(long v) { sp.edit().putLong("lastPress", v).apply(); }

    String lastEvent() { return sp.getString("event", ""); }
    void setLastEvent(String v) { sp.edit().putString("event", v).apply(); }
}
