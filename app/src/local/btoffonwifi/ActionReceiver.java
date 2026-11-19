package local.btoffonwifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Buttons in the watcher notification. */
public class ActionReceiver extends BroadcastReceiver {
    static final String PRESS = "local.btoffonwifi.action.PRESS";
    static final String CANCEL = "local.btoffonwifi.action.CANCEL";

    @Override
    public void onReceive(Context c, Intent i) {
        if (PRESS.equals(i.getAction())) {
            Controller.pressOverride(c);
        } else if (CANCEL.equals(i.getAction())) {
            Controller.cancelOverride(c, true);
        }
    }
}
