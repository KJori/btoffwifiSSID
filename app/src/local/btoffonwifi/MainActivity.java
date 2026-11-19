package local.btoffonwifi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.function.IntConsumer;

public class MainActivity extends Activity {
    private static final int[] STEPS = {15, 30, 60, 120};
    private static final String[] STEP_LABELS = {"15 min", "30 min", "1 hour", "2 hours"};
    private static final int[] ARRIVE = {0, 1, 2, 5, 10, 15};
    private static final String[] ARRIVE_LABELS = {"Immediately", "1 min", "2 min", "5 min", "10 min", "15 min"};
    private static final int[] LEAVE = {1, 2, 5, 10, 15};
    private static final String[] LEAVE_LABELS = {"1 min", "2 min", "5 min", "10 min", "15 min"};

    private static final int STEP_LOCATION = 1;
    private static final int STEP_BACKGROUND = 2;
    private static final int STEP_BLUETOOTH = 3;
    private static final int STEP_LOCATION_ON = 4;
    private static final int STEP_BATTERY = 5;

    private Prefs p;
    private Switch swEnabled, swRestore, swAudio;
    private TextView tvEnabled, tvTitle, tvStatus, tvWifi, tvOverride, tvNoNetworks, tvLog;
    private ImageView imgStatus;
    private View statusCircle, cardSetup, rowStep, rowArrive, rowLeave;
    private Button btnOverride, btnCancel, btnSetup;
    private LinearLayout listNetworks, listSetup;
    /** What the network and setup lists currently show, so the 1 s tick rebuilds them only on change. */
    private String shownNetworks, shownSetup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        p = new Prefs(this);
        GuardService.createChannel(this);

        swEnabled = findViewById(R.id.sw_enabled);
        tvEnabled = findViewById(R.id.tv_enabled);
        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        tvWifi = findViewById(R.id.tv_wifi);
        imgStatus = findViewById(R.id.img_status);
        statusCircle = findViewById(R.id.status_circle);
        tvOverride = findViewById(R.id.tv_override);
        btnOverride = findViewById(R.id.btn_override);
        btnCancel = findViewById(R.id.btn_cancel);
        listNetworks = findViewById(R.id.list_networks);
        tvNoNetworks = findViewById(R.id.tv_no_networks);
        cardSetup = findViewById(R.id.card_setup);
        listSetup = findViewById(R.id.list_setup);
        btnSetup = findViewById(R.id.btn_setup);
        tvLog = findViewById(R.id.tv_log);

        swEnabled.setChecked(p.enabled());
        swEnabled.setOnCheckedChangeListener((v, on) -> {
            p.setEnabled(on);
            if (on) {
                Controller.ensureRunning(this);
                Controller.evaluate(this, null);
            } else {
                Controller.stopWatching(this);
            }
            refresh();
        });

        // timer
        btnOverride.setOnClickListener(v -> {
            if (!Perms.bluetooth(this)) {
                runStep(STEP_BLUETOOTH);
                return;
            }
            if (!Controller.pressOverride(this)) toast("Android refused to turn Bluetooth on");
            refresh();
        });
        btnCancel.setOnClickListener(v -> {
            Controller.cancelOverride(this, true);
            refresh();
        });
        rowStep = row(R.id.row_step, R.drawable.ic_plus, "Each press adds");
        rowStep.setOnClickListener(v -> choose("Each press adds", STEPS, STEP_LABELS, p.stepMinutes(), p::setStepMinutes));

        // networks
        findViewById(R.id.btn_current).setOnClickListener(v -> addCurrentWifi());
        findViewById(R.id.btn_add_name).setOnClickListener(v -> addByName());

        // behaviour
        rowArrive = row(R.id.row_arrive, R.drawable.ic_timer, "Switch off after arriving");
        rowArrive.setOnClickListener(v -> choose("Switch off after arriving", ARRIVE, ARRIVE_LABELS,
                p.arriveDelayMin(), p::setArriveDelayMin));
        swRestore = switchRow(R.id.row_restore, R.id.sw_restore, R.drawable.ic_logout,
                "Turn back on when I leave", "Only if this app switched it off",
                p.restoreOnLeave(), p::setRestoreOnLeave);
        rowLeave = row(R.id.row_leave, R.drawable.ic_timer, "Wait before turning back on");
        rowLeave.setOnClickListener(v -> choose("Wait before turning back on", LEAVE, LEAVE_LABELS,
                p.leaveGraceMin(), p::setLeaveGraceMin));
        swAudio = switchRow(R.id.row_audio, R.id.sw_audio, R.drawable.ic_headphones,
                "Keep on for earbuds", "Earbuds, headphones or speakers. Never watches.",
                p.keepForAudio(), p::setKeepForAudio);

        // setup and extras
        btnSetup.setOnClickListener(v -> runStep(nextStep()));
        View tile = row(R.id.btn_tile, R.drawable.ic_tile, "Add quick settings tile");
        ((TextView) tile.findViewById(R.id.row_summary)).setText("Tap: Bluetooth on for a while. Long-press: open this app.");
        tile.setOnClickListener(v -> addTile());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Controller.ensureRunning(this);
        Controller.evaluate(this, null);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        Controller.ensureRunning(this); // refreshes the notification text after a settings change
        super.onPause();
    }

    // ---------- building blocks ----------

    /** Sets up an included row_setting; returns the row (its id is the include id). */
    private View row(int rowId, int icon, String title) {
        View r = findViewById(rowId);
        ((ImageView) r.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) r.findViewById(R.id.row_title)).setText(title);
        return r;
    }

    private Switch switchRow(int rowId, int switchId, int icon, String title, String summary,
                             boolean checked, java.util.function.Consumer<Boolean> store) {
        View r = row(rowId, icon, title);
        ((TextView) r.findViewById(R.id.row_summary)).setText(summary);
        Switch s = r.findViewById(R.id.row_switch);
        s.setId(switchId);
        s.setVisibility(View.VISIBLE);
        s.setChecked(checked);
        s.setOnCheckedChangeListener((v, on) -> {
            store.accept(on);
            Controller.evaluate(this, null);
            refresh();
        });
        r.setOnClickListener(v -> s.toggle());
        return s;
    }

    private static void summary(View row, String text) {
        ((TextView) row.findViewById(R.id.row_summary)).setText(text);
    }

    /** Single-choice dialog of minute values; picking one stores it and re-applies the rule. */
    private void choose(String title, int[] values, String[] labels, int current, IntConsumer store) {
        int sel = -1;
        for (int i = 0; i < values.length; i++) if (values[i] == current) sel = i;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    store.accept(values[which]);
                    d.dismiss();
                    Controller.evaluate(this, null);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String label(int[] values, String[] labels, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return labels[i];
        return value + " min";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------- networks ----------

    private void addCurrentWifi() {
        String s = Wifi.currentSsid(this);
        if (s == null) {
            toast(Wifi.wifiConnected(this)
                    ? "Android will not reveal the Wi-Fi name yet - finish Setup below"
                    : "Not connected to Wi-Fi");
        } else if (OverrideLogic.matches(p.ssid(), s)) {
            toast("\"" + s + "\" is already in the list");
        } else {
            setNetworks(OverrideLogic.addTarget(p.ssid(), s));
        }
    }

    private void addByName() {
        EditText ed = new EditText(this);
        ed.setHint("Wi-Fi name, exactly as shown");
        ed.setSingleLine(true);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
        box.addView(ed);
        new AlertDialog.Builder(this)
                .setTitle("Add Wi-Fi network")
                .setMessage("Names are case-sensitive.")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> setNetworks(OverrideLogic.addTarget(p.ssid(), ed.getText().toString())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setNetworks(String list) {
        p.setSsid(list);
        Controller.evaluate(this, null);
        refresh();
    }

    private void fillNetworks(String current) {
        String key = p.ssid() + "|" + current;
        if (key.equals(shownNetworks)) return;
        shownNetworks = key;
        listNetworks.removeAllViews();
        List<String> names = OverrideLogic.targetList(p.ssid());
        tvNoNetworks.setVisibility(names.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (String name : names) {
            View r = inf.inflate(R.layout.row_network, listNetworks, false);
            boolean here = name.equals(current);
            ((TextView) r.findViewById(R.id.net_name)).setText(name);
            r.findViewById(R.id.net_state).setVisibility(here ? View.VISIBLE : View.GONE);
            ((ImageView) r.findViewById(R.id.net_icon)).setImageTintList(
                    ColorStateList.valueOf(getColor(here ? R.color.accent : R.color.text2)));
            View remove = r.findViewById(R.id.net_remove);
            remove.setContentDescription("Remove " + name);
            remove.setOnClickListener(v -> setNetworks(OverrideLogic.removeTarget(p.ssid(), name)));
            listNetworks.addView(r);
        }
    }

    // ---------- status ----------

    private void refresh() {
        long now = System.currentTimeMillis();
        long until = p.until();
        boolean active = OverrideLogic.active(now, until);
        String cur = Wifi.currentSsid(this);
        boolean connected = cur != null || Wifi.wifiConnected(this);
        boolean home = OverrideLogic.matches(p.ssid(), cur);
        boolean on = Bt.isOn(this);
        String audio = p.keepForAudio() && on ? Bt.connectedAudio(this) : null;
        int next = nextStep();

        tvEnabled.setText(p.enabled() ? "Automatic switching is on" : "Paused");

        String title, why;
        int circle;
        if (!Bt.available(this)) {
            title = "No Bluetooth";
            why = "This device has no Bluetooth to switch.";
            circle = R.color.warn;
        } else if (!p.enabled()) {
            title = on ? "Bluetooth is on" : "Bluetooth is off";
            why = "Paused. Switch on at the top to let the app take over.";
            circle = R.color.neutral;
        } else if (OverrideLogic.targetList(p.ssid()).isEmpty()) {
            title = "Add your Wi-Fi";
            why = "Tell the app where Bluetooth should go off.";
            circle = R.color.warn;
        } else if (next != 0 && next != STEP_BATTERY) {
            title = "Finish setup";
            why = "Android needs a few permissions first, see Setup below.";
            circle = R.color.warn;
        } else {
            title = on ? "Bluetooth is on" : "Bluetooth is off";
            circle = on ? R.color.accent : R.color.neutral;
            long offAt = Controller.pendingOffAt(this);
            long backAt = p.awaySince() + p.leaveGraceMin() * 60_000L;
            if (active) {
                why = "Timer: on until " + Controller.clock(this, until)
                        + " (" + OverrideLogic.remaining(now, until) + " left)";
            } else if (audio != null) {
                why = "Kept on for " + audio;
            } else if (offAt > 0) {
                why = "Goes off at " + Controller.clock(this, offAt) + ", " + (offAt - now + 59_999) / 60_000
                        + " min after arriving";
            } else if (home) {
                why = on ? "On your Wi-Fi, but Android refused to switch it off. Check Setup."
                        : p.offByApp() ? "Switched off because you are home" : "Off, and you are home";
            } else if (!on && p.offByApp() && p.restoreOnLeave() && p.awaySince() > 0) {
                why = backAt > now ? "Back on at " + Controller.clock(this, backAt) + " now you have left"
                        : "Turning back on now you have left";
            } else {
                why = connected ? "Not on one of your networks, so left as it is" : "No Wi-Fi, so left as it is";
            }
        }
        tvTitle.setText(title);
        tvStatus.setText(why);
        imgStatus.setImageResource(on ? R.drawable.ic_bt_on : R.drawable.ic_bt_off);
        imgStatus.setImageTintList(ColorStateList.valueOf(getColor(R.color.card)));
        statusCircle.setBackgroundTintList(ColorStateList.valueOf(getColor(circle)));
        tvWifi.setText(cur != null ? "Wi-Fi: " + cur + (home ? " (one of yours)" : "")
                : connected ? "Wi-Fi: connected, but Android hides the name (see Setup)" : "Wi-Fi: not connected");

        // timer
        String step = label(STEPS, STEP_LABELS, p.stepMinutes());
        btnOverride.setText(active ? "+ " + step : "Bluetooth on for " + step);
        btnCancel.setVisibility(active ? View.VISIBLE : View.GONE);
        btnCancel.setEnabled(active);
        tvOverride.setText(active
                ? "On until " + Controller.clock(this, until) + ", " + OverrideLogic.remaining(now, until) + " left"
                : "Also at home. Press again for longer, up to 24 hours.");
        summary(rowStep, step);

        fillNetworks(cur);

        // behaviour
        int arrive = p.arriveDelayMin();
        summary(rowArrive, arrive == 0 ? "Immediately" : arrive + " min on your Wi-Fi first");
        summary(rowLeave, label(LEAVE, LEAVE_LABELS, p.leaveGraceMin()) + " away first");
        rowLeave.setEnabled(p.restoreOnLeave());
        rowLeave.setAlpha(p.restoreOnLeave() ? 1f : 0.4f);

        fillSetup(next);

        String ev = p.lastEvent();
        tvLog.setText(ev.isEmpty() ? "" : "Last action: " + ev);
    }

    private void fillSetup(int next) {
        boolean[] ok = {
                Perms.location(this), Perms.background(this), Perms.bluetooth(this),
                Perms.locationOn(this), Perms.batteryExempt(this)};
        String[] text = {
                "Location permission, so Android reveals the Wi-Fi name",
                "Location \"Allow all the time\", so it works with the screen off",
                "Nearby devices, to switch Bluetooth",
                "Location switched on in system settings",
                "Battery: unrestricted (recommended)"};
        boolean[] applies = {true, Build.VERSION.SDK_INT >= 29, Build.VERSION.SDK_INT >= 31, true, true};
        StringBuilder key = new StringBuilder();
        for (boolean b : ok) key.append(b ? '1' : '0');
        cardSetup.setVisibility(next == 0 ? View.GONE : View.VISIBLE);
        btnSetup.setText(next == STEP_BATTERY ? "Allow unrestricted battery use" : "Fix next item");
        if (key.toString().equals(shownSetup)) return;
        shownSetup = key.toString();
        listSetup.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < ok.length; i++) {
            if (!applies[i]) continue;
            View r = inf.inflate(R.layout.row_check, listSetup, false);
            ImageView ic = r.findViewById(R.id.check_icon);
            ic.setImageResource(ok[i] ? R.drawable.ic_check : R.drawable.ic_close);
            ic.setImageTintList(ColorStateList.valueOf(getColor(ok[i] ? R.color.ok : R.color.warn)));
            ((TextView) r.findViewById(R.id.check_text)).setText(text[i]);
            listSetup.addView(r);
        }
    }

    // ---------- setup ----------

    private int nextStep() {
        if (!Perms.location(this)) return STEP_LOCATION;
        if (!Perms.background(this)) return STEP_BACKGROUND;
        if (!Perms.bluetooth(this)) return STEP_BLUETOOTH;
        if (!Perms.locationOn(this)) return STEP_LOCATION_ON;
        if (!Perms.batteryExempt(this)) return STEP_BATTERY;
        return 0;
    }

    private void runStep(int step) {
        try {
            switch (step) {
                case STEP_LOCATION:
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, step);
                    break;
                case STEP_BACKGROUND:
                    if (Build.VERSION.SDK_INT >= 30) {
                        toast("Choose \"" + Api30.allTheTimeLabel(this) + "\"");
                    }
                    requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, step);
                    break;
                case STEP_BLUETOOTH:
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, step);
                    break;
                case STEP_LOCATION_ON:
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    break;
                case STEP_BATTERY:
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            openAppSettings();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean granted = results.length > 0;
        for (int r : results) if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) granted = false;
        // Android stops showing the dialog after repeated refusals; then only the settings page works.
        if (!granted && perms.length > 0 && code != STEP_BACKGROUND
                && !shouldShowRequestPermissionRationale(perms[0])) {
            toast("Grant it under Permissions");
            openAppSettings();
        }
        if (granted) {
            Controller.ensureRunning(this);
            Controller.evaluate(this, null);
        }
        refresh();
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            toast("Open Settings > Apps > BT Off on Wi-Fi > Permissions");
        }
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Api33.requestTile(this);
                return;
            } catch (RuntimeException e) {
                // fall through to the manual instructions
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Add the tile")
                .setMessage("Pull down the quick settings panel, tap the edit (pencil) button, "
                        + "then drag \"BT timer\" into the active tiles.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static final class Api30 {
        static CharSequence allTheTimeLabel(Activity a) {
            return a.getPackageManager().getBackgroundPermissionOptionLabel();
        }
    }

    private static final class Api33 {
        static void requestTile(Activity a) {
            StatusBarManager sbm = (StatusBarManager) a.getSystemService(StatusBarManager.class);
            sbm.requestAddTileService(new ComponentName(a, OverrideTile.class), "BT timer",
                    Icon.createWithResource(a, R.drawable.ic_bt), a.getMainExecutor(), r -> { });
        }
    }
}
