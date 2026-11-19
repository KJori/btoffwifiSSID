package local.btoffonwifi;

import android.bluetooth.BluetoothAdapter;
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

    /**
     * Earbuds or a headset connected over classic Bluetooth audio (A2DP or hands-free).
     * Low-energy devices such as watches and trackers deliberately do not count.
     */
    static boolean audioConnected(Context c) {
        try {
            BluetoothAdapter a = adapter(c);
            if (a == null) return false;
            return a.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                    || a.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
        } catch (RuntimeException e) {
            return false;
        }
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
