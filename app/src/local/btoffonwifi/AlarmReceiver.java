package local.btoffonwifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when the "keep Bluetooth on" timer runs out. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Controller.rescheduleExpiry(c); // no-op unless the alarm came a moment early
        Controller.evaluate(c, null);
        if (!GuardService.running) Controller.ensureRunning(c);
    }
}
