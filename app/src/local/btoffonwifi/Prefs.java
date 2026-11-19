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

    /** Turn Bluetooth back on after leaving the chosen Wi-Fi, if this app switched it off there. */
    boolean restoreOnLeave() { return sp.getBoolean("restoreOnLeave", true); }
    void setRestoreOnLeave(boolean v) { sp.edit().putBoolean("restoreOnLeave", v).apply(); }

    /** True while Bluetooth is off because this app switched it off; cleared whenever it comes on. */
    boolean offByApp() { return sp.getBoolean("offByApp", false); }
    void setOffByApp(boolean v) { sp.edit().putBoolean("offByApp", v).apply(); }

    /** When the phone was first seen away from the chosen Wi-Fi, wall-clock ms; 0 = not away. */
    long awaySince() { return sp.getLong("awaySince", 0); }
    void setAwaySince(long v) { sp.edit().putLong("awaySince", v).apply(); }

    String lastEvent() { return sp.getString("event", ""); }
    void setLastEvent(String v) { sp.edit().putString("event", v).apply(); }
}
