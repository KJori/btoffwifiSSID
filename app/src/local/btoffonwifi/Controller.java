package local.btoffonwifi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.format.DateFormat;

import java.util.Date;

/** All decisions live here; the service, tile, job and receivers only deliver events. */
final class Controller {
    private Controller() {}

    static String clock(Context c, long t) {
        return DateFormat.getTimeFormat(c).format(new Date(t));
    }

    private static void log(Context c, Prefs p, String msg) {
        p.setLastEvent(clock(c, System.currentTimeMillis()) + "  " + msg);
    }

    /**
     * The rule: if watching is enabled, no timer is running and the phone is on the chosen
     * Wi-Fi, Bluetooth goes off.
     *
     * @param knownSsid SSID just delivered by a network callback, or null to look it up
     */
    static synchronized void evaluate(Context c, String knownSsid) {
        Prefs p = new Prefs(c);
        long now = System.currentTimeMillis();
        long until = p.until();
        if (OverrideLogic.active(now, until)) return;
        if (until != 0) {
            p.setUntil(0);
            log(c, p, "Timer ended");
        }
        if (!p.enabled()) return;
        String cur = knownSsid != null ? knownSsid : Wifi.currentSsid(c);
        if (!OverrideLogic.matches(p.ssid(), cur)) return;
        if (!Bt.isOn(c)) return;
        if (Bt.set(c, false)) {
            log(c, p, "Bluetooth turned off (on \"" + cur + "\")");
        } else {
            log(c, p, "Could not turn Bluetooth off - check Setup");
        }
    }

    /** One press of the app button or the tile: Bluetooth on, timer one step longer. */
    static synchronized boolean pressOverride(Context c) {
        Prefs p = new Prefs(c);
        long now = System.currentTimeMillis();
        long until = OverrideLogic.press(now, p.until(), p.stepMinutes());
        p.setUntil(until);
        p.setLastPress(now);
        scheduleExpiry(c, until);
        boolean ok = Bt.isOn(c) || Bt.set(c, true);
        log(c, p, ok ? "Bluetooth kept on until " + clock(c, until)
                : "Timer set until " + clock(c, until) + ", but Bluetooth could not be turned on");
        return ok;
    }

    /** @param reevaluate true = apply the Wi-Fi rule right away (Bluetooth may go off) */
    static synchronized void cancelOverride(Context c, boolean reevaluate) {
        Prefs p = new Prefs(c);
        if (p.until() != 0) {
            p.setUntil(0);
            log(c, p, "Timer ended early");
        }
        scheduleExpiry(c, 0);
        if (reevaluate) evaluate(c, null);
    }

    static void onBluetoothState(Context c, int state) {
        if (state == BluetoothAdapter.STATE_ON) {
            // Bluetooth switched on outside the app while on the Wi-Fi and no timer: off again.
            evaluate(c, null);
        } else if (state == BluetoothAdapter.STATE_OFF) {
            Prefs p = new Prefs(c);
            long now = System.currentTimeMillis();
            if (OverrideLogic.active(now, p.until())) {
                if (now - p.lastPress() < OverrideLogic.PRESS_GRACE_MS) {
                    Bt.set(c, true); // an "off" that was already under way when the user pressed
                } else {
                    cancelOverride(c, false); // user switched Bluetooth off: timer no longer needed
                }
            }
        }
    }

    static void rescheduleExpiry(Context c) {
        long until = new Prefs(c).until();
        if (OverrideLogic.active(System.currentTimeMillis(), until)) scheduleExpiry(c, until);
    }

    private static void scheduleExpiry(Context c, long until) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, new Intent(c, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (until <= 0) {
            am.cancel(pi);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi);
                return;
            }
        } catch (RuntimeException e) {
            // exact alarms not permitted: fall back to an inexact one
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi);
        } catch (RuntimeException e) {
            // the 15-minute periodic job still ends the timer
        }
    }

    /** Start (or refresh) the background watcher if the feature is switched on. */
    static void ensureRunning(Context c) {
        if (!new Prefs(c).enabled()) return;
        WatchJob.schedule(c);
        try {
            Intent i = new Intent(c, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            // Android 12+ may refuse a start from the background; the periodic job covers the gap.
        }
    }

    static void stopWatching(Context c) {
        WatchJob.cancel(c);
        try {
            c.stopService(new Intent(c, GuardService.class));
        } catch (RuntimeException e) {
            // nothing to stop
        }
    }
}
