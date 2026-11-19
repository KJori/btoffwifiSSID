package local.btoffonwifi;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

final class Bt {
    private Bt() {}

    private static BluetoothAdapter adapter(Context c) {
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    static boolean available(Context c) {
        try {
            return adapter(c) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** On, or on its way on. */
    static boolean isOn(Context c) {
        try {
            BluetoothAdapter a = adapter(c);
            if (a == null) return false;
            int s = a.getState();
            return s == BluetoothAdapter.STATE_ON || s == BluetoothAdapter.STATE_TURNING_ON;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Media-audio (A2DP) proxy, bound once and kept; null until Android has handed it over. */
    private static volatile BluetoothProfile a2dp;
    private static volatile boolean a2dpRequested;

    /** Ask Android for the A2DP proxy (answered asynchronously); no-op once bound or pending. */
    static void bindProfiles(Context c) {
        if (a2dp != null || a2dpRequested) return;
        try {
            BluetoothAdapter a = adapter(c);
            if (a == null) return;
            a2dpRequested = a.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) a2dp = proxy;
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dp = null;
                        a2dpRequested = false;
                    }
                }
            }, BluetoothProfile.A2DP);
        } catch (RuntimeException e) {
            a2dpRequested = false;
        }
    }

    /** For tests: forget the proxy so each test binds its own. */
    static void resetProfiles() {
        a2dp = null;
        a2dpRequested = false;
    }

    /**
     * Name of connected earbuds, headphones or a speaker, or null if there are none.
     *
     * <p>Counts only devices connected for media audio (A2DP) that also say they are audio
     * devices. A watch that takes calls connects with the hands-free profile, the same one a
     * headset uses for calls, so hands-free alone never counts; wearables never count.
     */
    static String connectedAudio(Context c) {
        try {
            BluetoothAdapter a = adapter(c);
            if (a == null) return null;
            bindProfiles(c);
            BluetoothProfile p = a2dp;
            if (p != null) {
                for (BluetoothDevice d : p.getConnectedDevices()) {
                    if (isAudioDevice(d)) {
                        String n = d.getName();
                        return n == null || n.isEmpty() ? "audio device" : n;
                    }
                }
                return null;
            }
            // Proxy not bound yet (e.g. woken by an alarm): media audio connected is the safe sign.
            return a.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                    ? "audio device" : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean audioConnected(Context c) {
        return connectedAudio(c) != null;
    }

    private static boolean isAudioDevice(BluetoothDevice d) {
        BluetoothClass cls = d.getBluetoothClass();
        return cls != null && cls.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
    }

    /**
     * Switch the adapter. Works on Android 13+ only because this app targets API 31;
     * returns false if the system refuses (missing permission, vendor block).
     */
    @SuppressWarnings("deprecation")
    static boolean set(Context c, boolean on) {
        try {
            BluetoothAdapter a = adapter(c);
            if (a == null) return false;
            return on ? a.enable() : a.disable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
