package local.btoffonwifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the watcher after a reboot or after the app was reinstalled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Controller.rescheduleExpiry(c);
        Controller.ensureRunning(c);
        Controller.evaluate(c, null);
    }
}
