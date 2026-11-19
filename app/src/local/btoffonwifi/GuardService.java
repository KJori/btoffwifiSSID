package local.btoffonwifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;

/** Keeps the process alive and listens for Wi-Fi and Bluetooth changes. */
public class GuardService extends Service {
    static final String CHANNEL = "watcher";
    private static final int NOTIFICATION_ID = 1;
    static volatile boolean running;
    /** Text last shown, so repeated evaluations do not re-post an unchanged notification. */
    private static volatile String shown;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private BroadcastReceiver btReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (!goForeground()) return;

        Bt.bindProfiles(this);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        callback = Build.VERSION.SDK_INT >= 31
                ? new WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
                : new WifiCallback();
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.registerNetworkCallback(req, callback);
        } catch (RuntimeException e) {
            callback = null;
        }

        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String action = i.getAction();
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    Controller.onBluetoothState(c, i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                } else {
                    // earbuds / headphones (media audio) connected or disconnected
                    Controller.onAudioState(c, i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
                }
            }
        };
        IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(btReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!goForeground()) return START_NOT_STICKY;
        if (!new Prefs(this).enabled()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        Controller.rescheduleExpiry(this);
        Controller.evaluate(this, null);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        shown = null;
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException e) {
                // already gone
            }
        }
        Wifi.clearCache();
        if (btReceiver != null) {
            try {
                unregisterReceiver(btReceiver);
            } catch (RuntimeException e) {
                // already gone
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean goForeground() {
        try {
            Notification n = build(this);
            startForeground(NOTIFICATION_ID, n);
            shown = text(this);
            return true;
        } catch (RuntimeException e) {
            stopSelf();
            return false;
        }
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) Api26.createChannel(c);
    }

    /** Show the current state in the running watcher's notification. */
    static void refreshNotification(Context c) {
        if (!running) return;
        String t = text(c);
        if (t.equals(shown)) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIFICATION_ID, build(c));
            shown = t;
        } catch (RuntimeException e) {
            // keep the old text
        }
    }

    /** One line of state; also the key for "has anything changed". */
    private static String text(Context c) {
        Prefs p = new Prefs(c);
        long now = System.currentTimeMillis();
        String names = OverrideLogic.describe(p.ssid());
        if (names.isEmpty()) return "No Wi-Fi name set yet";
        if (OverrideLogic.active(now, p.until())) {
            return "Bluetooth on until " + Controller.clock(c, p.until()) + " (+" + p.stepMinutes() + " min per tap)";
        }
        long offAt = Controller.pendingOffAt(c);
        if (offAt > 0) return "Bluetooth goes off at " + Controller.clock(c, offAt);
        if (!Bt.isOn(c) && p.offByApp()) return "Bluetooth switched off by this app";
        return "Watching for " + names;
    }

    @SuppressWarnings("deprecation")
    static Notification build(Context c) {
        createChannel(c);
        Prefs p = new Prefs(c);
        boolean active = OverrideLogic.active(System.currentTimeMillis(), p.until());
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c).setPriority(Notification.PRIORITY_MIN);
        b.setSmallIcon(R.drawable.ic_bt)
                .setContentTitle("Bluetooth off on Wi-Fi")
                .setContentText(text(c))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(action(c, ActionReceiver.PRESS,
                        (active ? "+" : "Bluetooth on ") + p.stepMinutes() + " min"));
        if (active) b.addAction(action(c, ActionReceiver.CANCEL, "End timer"));
        return b.build();
    }

    private static Notification.Action action(Context c, String what, String title) {
        PendingIntent pi = PendingIntent.getBroadcast(c, what.hashCode(),
                new Intent(c, ActionReceiver.class).setAction(what),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_bt), title, pi).build();
    }

    private final class WifiCallback extends ConnectivityManager.NetworkCallback {
        WifiCallback() {
            super();
        }

        WifiCallback(int flags) {
            super(flags);
        }

        @Override
        public void onAvailable(Network network) {
            Controller.evaluate(GuardService.this, null);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            String ssid = Wifi.ssidOf(caps);
            if (ssid != null) Wifi.cache(network, ssid);
            Controller.evaluate(GuardService.this, ssid);
        }

        @Override
        public void onLost(Network network) {
            Wifi.lost(network);
            Controller.onWifiLost(GuardService.this);
        }
    }

    private static final class Api26 {
        static void createChannel(Context c) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Background watcher",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while the app waits for the chosen Wi-Fi. Can be hidden.");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }
}
