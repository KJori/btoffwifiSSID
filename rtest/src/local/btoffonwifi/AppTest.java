package local.btoffonwifi;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.job.JobScheduler;
import android.app.NotificationManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Parcel;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.time.Duration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothA2dp;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWifiInfo;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 31, 34, 35})
public class AppTest {
    /** Default leave wait (1 min). */
    private static final long GRACE = 60_000;
    private Application app;
    private BluetoothAdapter bt;
    private Prefs p;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN);
        bt = ((BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        assertNotNull("adapter", bt);
        p = new Prefs(app);
        p.setArriveDelayMin(0); // most tests check the rule itself; the delay has its own tests
        Bt.resetProfiles();
        a2dpProxy = null;
    }

    // ---- simulated Bluetooth devices ----

    private BluetoothA2dp a2dpProxy;

    /** The media-audio proxy the app binds to; devices added here count as connected for A2DP. */
    private ShadowBluetoothA2dp a2dp() {
        if (a2dpProxy == null) {
            a2dpProxy = Shadow.newInstanceOf(BluetoothA2dp.class);
            shadowOf(bt).setProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
        }
        return Shadow.extract(a2dpProxy);
    }

    private BluetoothDevice device(String mac, String name, int deviceClass) {
        BluetoothDevice d = bt.getRemoteDevice(mac);
        Parcel parcel = Parcel.obtain();
        parcel.writeInt(deviceClass);
        parcel.setDataPosition(0);
        shadowOf(d).setBluetoothClass(BluetoothClass.CREATOR.createFromParcel(parcel));
        parcel.recycle();
        shadowOf(d).setName(name);
        return d;
    }

    private BluetoothDevice earbuds() {
        return device("00:11:22:33:44:01", "Pixel Buds", BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET);
    }

    private BluetoothDevice watch() {
        return device("00:11:22:33:44:02", "Galaxy Watch", BluetoothClass.Device.WEARABLE_WRIST_WATCH);
    }

    private void onWifi(String ssid) {
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid == null ? "<unknown ssid>" : ssid);
        shadowOf(wm).setConnectionInfo(info);
    }

    private void watch(String ssid) {
        p.setEnabled(true);
        p.setSsid(ssid);
    }

    private void idle() {
        ShadowLooper.idleMainLooper();
    }

    private void btBroadcast(int state) {
        app.sendBroadcast(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, state));
        idle();
    }

    // ---- the rule ----

    @Test
    public void ssidIsReadAndNormalized() {
        onWifi("Home");
        assertEquals("Home", Wifi.currentSsid(app));
        onWifi(null);
        assertNull(Wifi.currentSsid(app));
    }

    @Test
    public void turnsBluetoothOffOnTargetWifi() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        assertTrue(Bt.isOn(app));
        Controller.evaluate(app, null);
        assertFalse("bt must be off", bt.isEnabled());
        assertTrue(p.lastEvent().contains("turned off"));
    }

    @Test
    public void leavesBluetoothAloneOnOtherWifiOrWhenDisabledOrUnknown() {
        watch("Home");
        onWifi("Office");
        bt.enable();
        Controller.evaluate(app, null);
        assertTrue("other wifi", bt.isEnabled());

        onWifi(null);
        Controller.evaluate(app, null);
        assertTrue("unknown ssid", bt.isEnabled());

        onWifi("Home");
        p.setEnabled(false);
        Controller.evaluate(app, null);
        assertTrue("feature off", bt.isEnabled());

        p.setEnabled(true);
        p.setSsid("");
        Controller.evaluate(app, null);
        assertTrue("no ssid configured", bt.isEnabled());
    }

    // ---- turning Bluetooth back on after leaving ----

    private void offAtHome() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        Controller.evaluate(app, null);
        assertFalse(bt.isEnabled());
        assertTrue("app remembers it switched bt off", p.offByApp());
    }

    @Test
    public void placeAndGraceLogic() {
        assertEquals(OverrideLogic.HOME, OverrideLogic.place("Home", "Home", true));
        assertEquals(OverrideLogic.AWAY, OverrideLogic.place("Home", "Office", true));
        assertEquals(OverrideLogic.AWAY, OverrideLogic.place("Home", null, false));
        assertEquals("hidden name is not leaving", OverrideLogic.UNKNOWN, OverrideLogic.place("Home", null, true));
        assertFalse(OverrideLogic.waited(100_000, 0, GRACE));
        assertFalse(OverrideLogic.waited(100_000, 100_000 - GRACE + 1, GRACE));
        assertTrue(OverrideLogic.waited(100_000, 100_000 - GRACE, GRACE));
    }

    @Test
    public void leavingTurnsBluetoothBackOnAfterGrace() {
        offAtHome();
        onWifi("Office");
        Controller.evaluate(app, null);
        assertFalse("not yet: inside the grace period", bt.isEnabled());
        assertTrue(p.awaySince() > 0);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(am).getNextScheduledAlarm();
        assertNotNull("re-check scheduled", alarm);
        assertEquals(p.awaySince() + GRACE, alarm.triggerAtTime);

        // the harness cannot move the clock, so move the stored start of "away" instead
        p.setAwaySince(System.currentTimeMillis() - GRACE - 1000);
        new AlarmReceiver().onReceive(app, shadowOf(alarm.operation).getSavedIntent());
        assertTrue("bt back on after leaving", bt.isEnabled());
        assertFalse(p.offByApp());
        assertTrue(p.lastEvent(), p.lastEvent().contains("back on"));

        onWifi("Home");
        Controller.evaluate(app, null);
        assertFalse("off again at home", bt.isEnabled());
        assertEquals("away timer reset at home", 0, p.awaySince());
    }

    @Test
    public void shortDropOutDoesNotTurnBluetoothOn() {
        offAtHome();
        onWifi("Office");
        Controller.evaluate(app, null);
        onWifi("Home"); // back within the minute
        Controller.evaluate(app, null);
        assertEquals(0, p.awaySince());
        assertFalse(bt.isEnabled());
        assertTrue("still remembered for a real departure", p.offByApp());
    }

    @Test
    public void optionOffLeavesBluetoothOff() {
        offAtHome();
        p.setRestoreOnLeave(false);
        onWifi("Office");
        p.setAwaySince(System.currentTimeMillis() - GRACE - 1000);
        Controller.evaluate(app, null);
        assertFalse(bt.isEnabled());
    }

    @Test
    public void bluetoothTheUserSwitchedOffStaysOff() {
        watch("Home");
        onWifi("Home");
        Controller.evaluate(app, null); // bt already off: app did nothing
        assertFalse(p.offByApp());
        onWifi("Office");
        p.setAwaySince(System.currentTimeMillis() - GRACE - 1000);
        Controller.evaluate(app, null);
        assertFalse("user's own off is respected", bt.isEnabled());
    }

    @Test
    public void hiddenWifiNameIsNotLeaving() {
        offAtHome();
        onWifi(null);
        p.setAwaySince(System.currentTimeMillis() - GRACE - 1000);
        if (Wifi.wifiConnected(app)) {
            Controller.evaluate(app, null);
            assertFalse(bt.isEnabled());
        }
    }

    @Test
    public void bluetoothOnFromElsewhereForgetsOffByApp() {
        offAtHome();
        Controller.onBluetoothState(app, BluetoothAdapter.STATE_ON); // e.g. user, or the timer
        assertFalse(p.offByApp());
    }

    @Test
    public void switchingFeatureOffForgetsOffByApp() {
        offAtHome();
        Controller.stopWatching(app);
        assertFalse(p.offByApp());
        assertEquals(0, p.awaySince());
    }

    @Test
    public void lostNetworkCallbackLeadsToRestore() {
        offAtHome();
        ServiceController<GuardService> sc = Robolectric.buildService(GuardService.class).create().startCommand(0, 1);
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        ConnectivityManager.NetworkCallback cb = shadowOf(cm).getNetworkCallbacks().iterator().next();
        onWifi("Office");
        cb.onLost(cm.getActiveNetwork());
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        assertNotNull("re-check scheduled after loss", shadowOf(am).getNextScheduledAlarm());
        p.setAwaySince(System.currentTimeMillis() - GRACE - 1000);
        new AlarmReceiver().onReceive(app, new Intent());
        assertTrue(bt.isEnabled());
        sc.destroy();
    }

    // ---- arrival delay ----

    @Test
    public void arrivalDelayWaitsBeforeSwitchingOff() {
        p.setArriveDelayMin(5);
        watch("Home");
        onWifi("Home");
        bt.enable();
        Controller.evaluate(app, null);
        assertTrue("still on during the arrival delay", bt.isEnabled());
        long home = p.homeSince();
        assertTrue(home > 0);
        assertEquals(home + 5 * 60000L, Controller.pendingOffAt(app));
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(am).getNextScheduledAlarm();
        assertNotNull("re-check at the end of the delay", alarm);
        assertEquals(home + 5 * 60000L, alarm.triggerAtTime);

        p.setHomeSince(System.currentTimeMillis() - 5 * 60000L - 1000);
        new AlarmReceiver().onReceive(app, shadowOf(alarm.operation).getSavedIntent());
        assertFalse("off once the delay has passed", bt.isEnabled());
        assertTrue(p.offByApp());
    }

    @Test
    public void briefReconnectWhileLeavingDoesNotSwitchOff() {
        p.setArriveDelayMin(5);
        watch("Home");
        bt.enable();
        onWifi("Home");
        Controller.evaluate(app, null);
        onWifi("Office");
        Controller.evaluate(app, null);
        assertEquals("arrival clock reset when away", 0, p.homeSince());
        onWifi("Home");
        Controller.evaluate(app, null);
        assertTrue("a short visit back does not switch it off", bt.isEnabled());
    }

    @Test
    public void leaveWaitIsAdjustable() {
        offAtHome();
        p.setLeaveGraceMin(5);
        onWifi("Office");
        p.setAwaySince(System.currentTimeMillis() - 2 * 60000L);
        Controller.evaluate(app, null);
        assertFalse("2 of 5 minutes: not yet", bt.isEnabled());
        p.setAwaySince(System.currentTimeMillis() - 5 * 60000L - 1000);
        Controller.evaluate(app, null);
        assertTrue(bt.isEnabled());
    }

    // ---- earbuds / headset ----

    @Test
    public void earbudsKeepBluetoothOnUntilTheyDisconnect() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        BluetoothDevice buds = earbuds();
        a2dp().addDevice(buds, BluetoothProfile.STATE_CONNECTED);
        Controller.evaluate(app, null);
        assertTrue("earbuds connected: stays on", bt.isEnabled());
        assertEquals("Pixel Buds", Bt.connectedAudio(app));
        a2dp().removeDevice(buds);
        Controller.onAudioState(app, BluetoothProfile.STATE_DISCONNECTED);
        assertFalse("off once they disconnect", bt.isEnabled());
    }

    @Test
    public void watchConnectedForCallsDoesNotKeepBluetoothOn() {
        // The reported bug: a watch that takes calls connects as a hands-free device.
        watch("Home");
        onWifi("Home");
        bt.enable();
        watch();
        shadowOf(bt).setProfileConnectionState(BluetoothProfile.HEADSET, BluetoothProfile.STATE_CONNECTED);
        a2dp(); // proxy bound, no media-audio devices
        Controller.evaluate(app, null);
        assertNull(Bt.connectedAudio(app));
        assertFalse("watch on hands-free: off", bt.isEnabled());
    }

    @Test
    public void watchOnHandsFreeDoesNotCountBeforeProxyIsBound() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        shadowOf(bt).setProfileConnectionState(BluetoothProfile.HEADSET, BluetoothProfile.STATE_CONNECTED);
        Controller.evaluate(app, null);
        assertFalse("fallback ignores hands-free", bt.isEnabled());
    }

    @Test
    public void watchStreamingMediaStillDoesNotCount() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        a2dp().addDevice(watch(), BluetoothProfile.STATE_CONNECTED);
        Controller.evaluate(app, null);
        assertFalse("a wearable never keeps it on", bt.isEnabled());
    }

    @Test
    public void earbudsCountNextToAWatch() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        a2dp().addDevice(watch(), BluetoothProfile.STATE_CONNECTED);
        a2dp().addDevice(earbuds(), BluetoothProfile.STATE_CONNECTED);
        Controller.evaluate(app, null);
        assertTrue(bt.isEnabled());
        assertEquals("Pixel Buds", Bt.connectedAudio(app));
    }

    @Test
    public void mediaAudioCountsBeforeProxyIsBound() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        shadowOf(bt).setProfileConnectionState(BluetoothProfile.A2DP, BluetoothProfile.STATE_CONNECTED);
        Controller.evaluate(app, null);
        assertTrue("fallback: media audio connected keeps it on", bt.isEnabled());
    }

    @Test
    public void statusNamesTheDeviceKeepingBluetoothOn() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        a2dp().addDevice(earbuds(), BluetoothProfile.STATE_CONNECTED);
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        String status = ((TextView) ac.get().findViewById(R.id.tv_status)).getText().toString();
        assertTrue(status, status.contains("Kept on for Pixel Buds"));
        ac.pause().stop().destroy();
    }

    @Test
    public void earbudsOptionOffSwitchesOffAnyway() {
        p.setKeepForAudio(false);
        watch("Home");
        onWifi("Home");
        bt.enable();
        shadowOf(bt).setProfileConnectionState(BluetoothProfile.A2DP, BluetoothProfile.STATE_CONNECTED);
        Controller.evaluate(app, null);
        assertFalse(bt.isEnabled());
    }

    @Test
    public void earbudsDisconnectBroadcastReachesService() {
        watch("Home");
        onWifi("Home");
        BluetoothDevice buds = earbuds();
        a2dp().addDevice(buds, BluetoothProfile.STATE_CONNECTED);
        ServiceController<GuardService> sc = Robolectric.buildService(GuardService.class).create().startCommand(0, 1);
        bt.enable();
        Controller.evaluate(app, null);
        assertTrue(bt.isEnabled());
        a2dp().removeDevice(buds);
        app.sendBroadcast(new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED));
        idle();
        assertFalse(bt.isEnabled());
        sc.destroy();
    }

    // ---- several Wi-Fi networks ----

    @Test
    public void severalWifiNetworks() {
        watch("Home\nOffice");
        onWifi("Office");
        bt.enable();
        Controller.evaluate(app, null);
        assertFalse("second network counts too", bt.isEnabled());
        p.setAwaySince(System.currentTimeMillis() - GRACE - 1000);
        onWifi("Cafe");
        Controller.evaluate(app, null);
        assertTrue("away from all of them: back on", bt.isEnabled());

        assertEquals("\"Home\", \"Office\"", OverrideLogic.describe(" Home\n\nOffice "));
        assertEquals("Home\nOffice", OverrideLogic.addTarget("Home\nOffice", "Office"));
        assertEquals("Home\nOffice\nCafe", OverrideLogic.addTarget("Home\nOffice", " Cafe "));
        assertEquals("Cafe", OverrideLogic.addTarget("", "Cafe"));
    }

    // ---- notification ----

    @Test
    public void notificationShowsStateAndButtonsWork() {
        watch("Home");
        onWifi("Home");
        ServiceController<GuardService> sc = Robolectric.buildService(GuardService.class).create().startCommand(0, 1);
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);

        new ActionReceiver().onReceive(app, new Intent(ActionReceiver.PRESS));
        assertTrue("button turns bt on", bt.isEnabled());
        assertTrue(p.until() > System.currentTimeMillis());
        Notification n = shadowOf(nm).getNotification(1);
        assertNotNull(n);
        String text = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(text, text.contains("until"));
        assertEquals("+ step and End timer", 2, n.actions.length);

        new ActionReceiver().onReceive(app, new Intent(ActionReceiver.CANCEL));
        assertEquals(0, p.until());
        assertFalse("rule applied after End timer", bt.isEnabled());
        n = shadowOf(nm).getNotification(1);
        text = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(text, text.contains("switched off by this app"));
        assertEquals(1, n.actions.length);
        sc.destroy();
    }

    // ---- the timer ----

    @Test
    public void overrideTurnsBluetoothOnAndHoldsItThenExpires() {
        watch("Home");
        onWifi("Home");
        assertFalse(bt.isEnabled());

        long t0 = System.currentTimeMillis();
        assertTrue(Controller.pressOverride(app));
        assertTrue("bt on after press", bt.isEnabled());
        long until1 = p.until();
        assertEquals(30 * 60000L, until1 - t0, 2000);

        Controller.pressOverride(app);
        long until2 = p.until();
        assertEquals("second press adds a step", 30 * 60000L, until2 - until1, 2000);

        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(am).getNextScheduledAlarm();
        assertNotNull("expiry alarm scheduled", alarm);
        assertEquals(until2, alarm.triggerAtTime);
        assertEquals(AlarmReceiver.class.getName(),
                shadowOf(alarm.operation).getSavedIntent().getComponent().getClassName());

        Controller.evaluate(app, null);
        assertTrue("held on while timer runs", bt.isEnabled());

        // the harness cannot move System.currentTimeMillis(), so move the stored end time instead
        p.setUntil(System.currentTimeMillis() - 1000);
        new AlarmReceiver().onReceive(app, shadowOf(alarm.operation).getSavedIntent());
        assertFalse("bt off after expiry on target wifi", bt.isEnabled());
        assertEquals(0, p.until());
    }

    @Test
    public void expiryAwayFromTargetWifiLeavesBluetoothOn() {
        watch("Home");
        onWifi("Office");
        Controller.pressOverride(app);
        p.setUntil(System.currentTimeMillis() - 1000);
        new AlarmReceiver().onReceive(app, new Intent());
        assertTrue(bt.isEnabled());
        assertEquals(0, p.until());
    }

    @Test
    public void endTimerNowAppliesRuleImmediately() {
        watch("Home");
        onWifi("Home");
        Controller.pressOverride(app);
        assertTrue(bt.isEnabled());
        Controller.cancelOverride(app, true);
        assertFalse(bt.isEnabled());
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        assertNull("alarm cancelled", shadowOf(am).getNextScheduledAlarm());
    }

    // ---- background service ----

    @Test
    public void serviceStartsForegroundRegistersListenersAndReactsToBluetoothOn() {
        watch("Home");
        onWifi("Home");
        ServiceController<GuardService> sc = Robolectric.buildService(GuardService.class).create().startCommand(0, 1);
        GuardService s = sc.get();
        Notification n = shadowOf(s).getLastForegroundNotification();
        assertNotNull("foreground notification", n);
        assertTrue(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)).contains("Home"));
        assertTrue(GuardService.running);
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertEquals("one network callback", 1, shadowOf(cm).getNetworkCallbacks().size());

        // Bluetooth switched on from system settings while on the Wi-Fi, no timer: off again
        bt.enable();
        btBroadcast(BluetoothAdapter.STATE_ON);
        assertFalse("bt forced off", bt.isEnabled());

        // with a timer running the same event is ignored
        Controller.pressOverride(app);
        btBroadcast(BluetoothAdapter.STATE_ON);
        assertTrue("bt held on by timer", bt.isEnabled());

        // user switches Bluetooth off during the timer (after the grace window): timer ends
        p.setLastPress(System.currentTimeMillis() - 60000);
        bt.disable();
        btBroadcast(BluetoothAdapter.STATE_OFF);
        assertEquals("timer cancelled by manual off", 0, p.until());
        assertFalse(bt.isEnabled());

        sc.destroy();
        assertFalse(GuardService.running);
        assertEquals("callback unregistered", 0, shadowOf(cm).getNetworkCallbacks().size());
    }

    @Test
    public void networkCallbackTriggersRule() {
        watch("Home");
        onWifi("Office");
        ServiceController<GuardService> sc = Robolectric.buildService(GuardService.class).create().startCommand(0, 1);
        bt.enable();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        ConnectivityManager.NetworkCallback cb = shadowOf(cm).getNetworkCallbacks().iterator().next();
        cb.onAvailable(cm.getActiveNetwork());
        assertTrue("other wifi: stays on", bt.isEnabled());
        onWifi("Home");
        cb.onAvailable(cm.getActiveNetwork());
        assertFalse("target wifi: off", bt.isEnabled());
        sc.destroy();
    }

    @Test
    public void serviceStopsItselfWhenFeatureIsOff() {
        p.setEnabled(false);
        ServiceController<GuardService> sc = Robolectric.buildService(GuardService.class).create().startCommand(0, 1);
        assertTrue(shadowOf(sc.get()).isStoppedBySelf());
        sc.destroy();
    }

    @Test
    public void bootReceiverStartsWatcherAndJob() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent started = shadowOf(app).getNextStartedService();
        assertNotNull("service start requested", started);
        assertEquals(GuardService.class.getName(), started.getComponent().getClassName());
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        assertEquals(1, js.getAllPendingJobs().size());
        assertTrue(js.getAllPendingJobs().get(0).isPeriodic());
        assertTrue(js.getAllPendingJobs().get(0).isPersisted());
        assertFalse("rule applied at boot", bt.isEnabled());
    }

    @Test
    public void bootReceiverDoesNothingWhenFeatureIsOff() {
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(shadowOf(app).getNextStartedService());
    }

    @Test
    public void periodicJobAppliesRule() {
        watch("Home");
        onWifi("Home");
        bt.enable();
        WatchJob job = Robolectric.buildService(WatchJob.class).create().get();
        assertFalse(job.onStartJob(null));
        assertFalse(bt.isEnabled());
    }

    // ---- quick settings tile ----

    @Test
    public void tileTapsExtendTimer() {
        watch("Home");
        onWifi("Home");
        OverrideTile tile = Robolectric.buildService(OverrideTile.class).create().get();
        tile.onStartListening();
        Tile qs = tile.getQsTile();
        assertNotNull("tile object", qs);
        assertEquals(Tile.STATE_INACTIVE, qs.getState());

        long t0 = System.currentTimeMillis();
        tile.onClick();
        assertTrue("bt on after tile tap", bt.isEnabled());
        assertEquals(30 * 60000L, p.until() - t0, 2000);
        tile.onClick();
        tile.onClick();
        assertEquals("three taps = 90 min", 90 * 60000L, p.until() - t0, 2000);
        assertEquals(Tile.STATE_ACTIVE, qs.getState());
        String shown = Build.VERSION.SDK_INT >= 29 ? String.valueOf(qs.getSubtitle()) : String.valueOf(qs.getLabel());
        assertTrue(shown, shown.toLowerCase().contains("until"));
    }

    // ---- screen ----

    private void locationOn() {
        if (Build.VERSION.SDK_INT >= 28) {
            shadowOf((android.location.LocationManager) app.getSystemService(Context.LOCATION_SERVICE))
                    .setLocationEnabled(true);
        } else {
            android.provider.Settings.Secure.putInt(app.getContentResolver(),
                    android.provider.Settings.Secure.LOCATION_MODE, 3);
        }
    }

    @Test
    public void activityShowsStateAndButtonsWork() {
        locationOn();
        watch("Home");
        onWifi("Home");
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ac.get();
        idle();

        android.widget.LinearLayout nets = a.findViewById(R.id.list_networks);
        assertEquals(1, nets.getChildCount());
        assertEquals("Home", ((TextView) nets.getChildAt(0).findViewById(R.id.net_name)).getText().toString());
        assertEquals("connected marker", View.VISIBLE, nets.getChildAt(0).findViewById(R.id.net_state).getVisibility());
        assertTrue(((Switch) a.findViewById(R.id.sw_enabled)).isChecked());
        assertEquals("Bluetooth is off", ((TextView) a.findViewById(R.id.tv_title)).getText().toString());
        assertTrue(((TextView) a.findViewById(R.id.tv_wifi)).getText().toString().contains("Home"));

        Switch restore = a.findViewById(R.id.sw_restore);
        assertTrue("restore option on by default", restore.isChecked());
        restore.performClick();
        assertFalse(p.restoreOnLeave());
        a.findViewById(R.id.row_restore).performClick(); // the whole row toggles
        assertTrue(p.restoreOnLeave());
        assertTrue(((Switch) a.findViewById(R.id.sw_audio)).isChecked());

        Button override = a.findViewById(R.id.btn_override);
        Button cancel = a.findViewById(R.id.btn_cancel);
        assertFalse(cancel.isEnabled());
        assertEquals(View.GONE, cancel.getVisibility());
        override.performClick();
        override.performClick();
        assertTrue(bt.isEnabled());
        assertTrue(p.until() > System.currentTimeMillis() + 55 * 60000L);
        assertTrue(cancel.isEnabled());
        assertTrue(((TextView) a.findViewById(R.id.tv_override)).getText().toString().contains("left"));
        assertTrue(((TextView) a.findViewById(R.id.tv_status)).getText().toString().startsWith("Timer"));

        cancel.performClick();
        assertFalse("end now on target wifi turns bt off", bt.isEnabled());
        assertEquals(0, p.until());

        onWifi("Cafe");
        a.findViewById(R.id.btn_current).performClick();
        assertEquals("current Wi-Fi is added to the list", "Home\nCafe", p.ssid());
        a.findViewById(R.id.btn_current).performClick();
        assertEquals("not added twice", "Home\nCafe", p.ssid());
        assertEquals(2, nets.getChildCount());
        nets.getChildAt(0).findViewById(R.id.net_remove).performClick();
        assertEquals("Cafe", p.ssid());
        assertEquals(1, nets.getChildCount());

        a.findViewById(R.id.row_arrive).performClick();
        android.app.AlertDialog dlg = (android.app.AlertDialog) org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("picker shown", dlg);
        shadowOf(dlg).clickOnItem(3); // "5 min"
        assertEquals(5, p.arriveDelayMin());

        Switch sw = a.findViewById(R.id.sw_enabled);
        sw.performClick();
        assertFalse(p.enabled());
        sw.performClick();
        assertTrue(p.enabled());

        org.robolectric.shadows.ShadowAlertDialog.reset();
        a.findViewById(R.id.btn_tile).performClick();
        idle();
        // Android 13+: system "add tile" prompt is requested; older: instructions dialog is shown
        assertEquals(Build.VERSION.SDK_INT < 33,
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog() != null);
        ac.pause().stop().destroy();
    }

    @Test
    public void networkListHelpers() {
        assertEquals("Office", OverrideLogic.removeTarget("Home\nOffice", " Home "));
        assertEquals("", OverrideLogic.removeTarget("Home", "Home"));
        assertEquals(java.util.Arrays.asList("Home", "Office"), OverrideLogic.targetList(" Home \n\nOffice"));
    }

    @Test
    public void setupButtonRequestsMissingPermissionsInOrder() {
        shadowOf(app).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT);
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ac.get();
        idle();
        Button setup = a.findViewById(R.id.btn_setup);
        assertEquals(View.VISIBLE, setup.getVisibility());

        setup.performClick();
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION,
                shadowOf(a).getLastRequestedPermission().requestedPermissions[0]);

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        setup.performClick();
        String second = shadowOf(a).getLastRequestedPermission().requestedPermissions[0];
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(Manifest.permission.ACCESS_BACKGROUND_LOCATION, second);
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            setup.performClick();
        }
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(Manifest.permission.BLUETOOTH_CONNECT,
                    shadowOf(a).getLastRequestedPermission().requestedPermissions[0]);
            // without the permission the big button asks for it instead of starting a timer
            a.findViewById(R.id.btn_override).performClick();
            assertEquals(0, p.until());
        }
        ac.pause().stop().destroy();
    }
}
