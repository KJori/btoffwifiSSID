package local.btoffonwifi;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings tile: each tap keeps Bluetooth on one step longer. */
public class OverrideTile extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (!Perms.bluetooth(this)) {
            openApp(); // permission can only be granted from the app screen
            return;
        }
        Controller.pressOverride(this);
        refresh();
    }

    @SuppressWarnings("deprecation")
    private void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        } catch (RuntimeException e) {
            // tile stays as it is
        }
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        Prefs p = new Prefs(this);
        long until = p.until();
        boolean active = OverrideLogic.active(System.currentTimeMillis(), until);
        String idle = "+" + p.stepMinutes() + " min";
        String on = "On until " + Controller.clock(this, until);
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setLabel("BT timer");
            t.setSubtitle(active ? on : "Tap: " + idle);
        } else {
            t.setLabel(active ? "BT " + on.toLowerCase() : "BT timer " + idle);
        }
        t.updateTile();
    }
}
