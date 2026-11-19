package local.btoffonwifi;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

final class Perms {
    private Perms() {}

    static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean location(Context c) {
        return has(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    static boolean background(Context c) {
        return Build.VERSION.SDK_INT < 29 || has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    static boolean bluetooth(Context c) {
        return Build.VERSION.SDK_INT < 31 || has(c, Manifest.permission.BLUETOOTH_CONNECT);
    }

    @SuppressWarnings("deprecation")
    static boolean locationOn(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                return lm != null && lm.isLocationEnabled();
            }
            return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (RuntimeException e) {
            return true;
        }
    }

    static boolean batteryExempt(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (RuntimeException e) {
            return true;
        }
    }
}
