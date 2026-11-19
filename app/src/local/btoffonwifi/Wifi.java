package local.btoffonwifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

final class Wifi {
    private Wifi() {}

    /** Last SSID delivered by the network callback (the route Android 12+ prefers). */
    private static volatile String cachedSsid;
    private static volatile Network cachedNet;

    static void cache(Network n, String ssid) {
        cachedNet = n;
        cachedSsid = ssid;
    }

    static void lost(Network n) {
        if (n != null && n.equals(cachedNet)) clearCache();
    }

    static void clearCache() {
        cachedNet = null;
        cachedSsid = null;
    }

    /** Name of the connected Wi-Fi network, or null if not connected or Android will not say. */
    static String currentSsid(Context c) {
        String s = fromWifiManager(c);
        return s != null ? s : cachedSsid;
    }

    @SuppressWarnings("deprecation")
    private static String fromWifiManager(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            return info == null ? null : OverrideLogic.normalizeSsid(info.getSSID());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String ssidOf(NetworkCapabilities caps) {
        if (caps == null || Build.VERSION.SDK_INT < 29) return null;
        try {
            return Api29.ssidOf(caps);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    static boolean wifiConnected(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return false;
    }

    private static final class Api29 {
        static String ssidOf(NetworkCapabilities caps) {
            TransportInfo ti = caps.getTransportInfo();
            if (ti instanceof WifiInfo) return OverrideLogic.normalizeSsid(((WifiInfo) ti).getSSID());
            return null;
        }
    }
}
