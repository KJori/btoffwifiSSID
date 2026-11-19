package local.btoffonwifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;

/** Keeps the process alive and listens for Wi-Fi and Bluetooth changes. */
public class GuardService extends Service {
    static final String CHANNEL = "watcher";
    static volatile boolean running;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private BroadcastReceiver btReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (!goForeground()) return;

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
                Controller.onBluetoothState(c, i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
            }
        };
        registerReceiver(btReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
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
            startForeground(1, buildNotification());
            return true;
        } catch (RuntimeException e) {
            stopSelf();
            return false;
        }
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) Api26.createChannel(c);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        createChannel(this);
        String ssid = new Prefs(this).ssid().trim();
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        return b.setSmallIcon(R.drawable.ic_bt)
                .setContentTitle("Bluetooth off on Wi-Fi")
                .setContentText(ssid.isEmpty() ? "No Wi-Fi name set yet" : "Watching for \"" + ssid + "\"")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build();
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
