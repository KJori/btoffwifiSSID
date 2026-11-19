package local.btoffonwifi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.format.DateFormat;

import java.util.Date;

/** All decisions live here; the service, tile, job and receivers only deliver events. */
final class Controller {
    private Controller() {}

    private static final int ALARM_EXPIRY = 1;
    private static final int ALARM_RECHECK = 2;
    /** After a Wi-Fi network or earbuds go away, look again this soon in case Android lagged. */
    private static final long SETTLE_RECHECK_MS = 15_000;

    static String clock(Context c, long t) {
        return DateFormat.getTimeFormat(c).format(new Date(t));
    }

    private static void log(Context c, Prefs p, String msg) {
        p.setLastEvent(clock(c, System.currentTimeMillis()) + "  " + msg);
    }

    /**
     * The rule: if watching is enabled, no timer is running and the phone has been on one of the
     * chosen Wi-Fi networks for the arrival delay, Bluetooth goes off - unless earbuds,
     * headphones or a speaker are connected (optional; watches never count). Optionally, once the phone has been away for the leave
     * delay, Bluetooth that this app switched off goes back on.
     *
     * @param knownSsid SSID just delivered by a network callback, or null to look it up
     */
    static synchronized void evaluate(Context c, String knownSsid) {
        try {
            decide(c, knownSsid);
        } finally {
            GuardService.refreshNotification(c);
        }
    }

    private static void decide(Context c, String knownSsid) {
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
        int place = OverrideLogic.place(p.ssid(), cur, cur != null || Wifi.wifiConnected(c));
        if (place == OverrideLogic.UNKNOWN) return;
        if (place == OverrideLogic.AWAY) {
            p.setHomeSince(0);
            restoreIfLeft(c, p, now);
            return;
        }

        p.setAwaySince(0);
        long homeSince = p.homeSince();
        if (homeSince == 0) {
            homeSince = now;
            p.setHomeSince(now);
        }
        if (!Bt.isOn(c)) return;
        if (p.keepForAudio() && Bt.audioConnected(c)) return; // looked at again when they disconnect
        long offAt = homeSince + p.arriveDelayMin() * 60_000L;
        if (now < offAt) {
            scheduleRecheck(c, p, offAt);
            return;
        }
        if (Bt.set(c, false)) {
            p.setOffByApp(true);
            log(c, p, "Bluetooth turned off (on \"" + cur + "\")");
        } else {
            log(c, p, "Could not turn Bluetooth off - check Setup");
        }
    }

    /** Away from the chosen Wi-Fi: turn Bluetooth back on if this app switched it off. */
    private static void restoreIfLeft(Context c, Prefs p, long now) {
        if (p.awaySince() == 0) p.setAwaySince(now);
        if (!p.restoreOnLeave() || !p.offByApp()) return;
        if (Bt.isOn(c)) {
            p.setOffByApp(false); // already back on, nothing left to undo
            return;
        }
        long graceMs = p.leaveGraceMin() * 60_000L;
        if (!OverrideLogic.waited(now, p.awaySince(), graceMs)) {
            scheduleRecheck(c, p, p.awaySince() + graceMs);
            return;
        }
        if (Bt.set(c, true)) {
            p.setOffByApp(false);
            log(c, p, "Bluetooth turned back on (left " + OverrideLogic.describe(p.ssid()) + ")");
        } else {
            log(c, p, "Could not turn Bluetooth back on - check Setup");
        }
    }

    /** A Wi-Fi network went away: apply the rule now and look again shortly. */
    static void onWifiLost(Context c) {
        Prefs p = new Prefs(c);
        if (p.enabled() && p.restoreOnLeave() && p.offByApp()) {
            // Android may still report the old SSID for a moment; the later check sees the truth.
            scheduleRecheck(c, p, System.currentTimeMillis() + SETTLE_RECHECK_MS);
        }
        evaluate(c, null);
    }

    /** Earbuds, headphones or a speaker connected or disconnected (media audio). */
    static void onAudioState(Context c, int state) {
        if (state != BluetoothProfile.STATE_DISCONNECTED) return;
        Prefs p = new Prefs(c);
        if (p.enabled()) scheduleRecheck(c, p, System.currentTimeMillis() + SETTLE_RECHECK_MS);
        evaluate(c, null);
    }

    /** One press of the app button, the tile or the notification: Bluetooth on, timer one step longer. */
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
        GuardService.refreshNotification(c);
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
        else GuardService.refreshNotification(c);
    }

    static void onBluetoothState(Context c, int state) {
        if (state == BluetoothAdapter.STATE_ON) {
            // Whoever switched it on, there is no longer an "off by this app" to undo.
            new Prefs(c).setOffByApp(false);
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
            GuardService.refreshNotification(c);
        }
    }

    /** When Bluetooth will be switched off after arriving, or 0 if nothing is pending. */
    static long pendingOffAt(Context c) {
        Prefs p = new Prefs(c);
        long now = System.currentTimeMillis();
        if (!p.enabled() || p.homeSince() == 0 || OverrideLogic.active(now, p.until())) return 0;
        if (!OverrideLogic.matches(p.ssid(), Wifi.currentSsid(c)) || !Bt.isOn(c)) return 0;
        if (p.keepForAudio() && Bt.audioConnected(c)) return 0;
        long offAt = p.homeSince() + p.arriveDelayMin() * 60_000L;
        return offAt > now ? offAt : 0;
    }

    static void rescheduleExpiry(Context c) {
        long until = new Prefs(c).until();
        if (OverrideLogic.active(System.currentTimeMillis(), until)) scheduleExpiry(c, until);
    }

    private static void scheduleExpiry(Context c, long until) {
        scheduleAlarm(c, ALARM_EXPIRY, until);
    }

    /**
     * Ask for a re-check at {@code at}. One alarm serves every pending re-check: an earlier one
     * that is still due is kept, because each re-check schedules whatever later one it needs.
     */
    private static void scheduleRecheck(Context c, Prefs p, long at) {
        long now = System.currentTimeMillis();
        long pending = p.recheckAt();
        if (pending > now && pending <= at) return;
        p.setRecheckAt(at);
        scheduleAlarm(c, ALARM_RECHECK, at);
    }

    /** One alarm per request code; a new time replaces the old one, at <= 0 cancels it. */
    private static void scheduleAlarm(Context c, int requestCode, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, requestCode, new Intent(c, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (at <= 0) {
            am.cancel(pi);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return;
            }
        } catch (RuntimeException e) {
            // exact alarms not permitted: fall back to an inexact one
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (RuntimeException e) {
            // the 15-minute periodic job still applies the rule
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
        Prefs p = new Prefs(c);
        p.setOffByApp(false); // switching the feature off must not turn Bluetooth on later
        p.setAwaySince(0);
        p.setRecheckAt(0);
        scheduleAlarm(c, ALARM_RECHECK, 0);
        WatchJob.cancel(c);
        try {
            c.stopService(new Intent(c, GuardService.class));
        } catch (RuntimeException e) {
            // nothing to stop
        }
    }
}
