package local.btoffonwifi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int[] STEPS = {15, 30, 60, 120};
    private static final String[] STEP_LABELS = {"15 min", "30 min", "1 hour", "2 hours"};
    private static final int[] ARRIVE = {0, 1, 2, 5, 10, 15};
    private static final String[] ARRIVE_LABELS = {"immediately", "1 min", "2 min", "5 min", "10 min", "15 min"};
    private static final int[] LEAVE = {1, 2, 5, 10, 15};
    private static final String[] LEAVE_LABELS = {"1 min", "2 min", "5 min", "10 min", "15 min"};

    private static final int STEP_LOCATION = 1;
    private static final int STEP_BACKGROUND = 2;
    private static final int STEP_BLUETOOTH = 3;
    private static final int STEP_LOCATION_ON = 4;
    private static final int STEP_BATTERY = 5;

    private Prefs p;
    private EditText edSsid;
    private TextView tvStatus, tvOverride, tvSetup, tvLog;
    private Button btnOverride, btnCancel, btnSetup;

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

        Switch sw = findViewById(R.id.sw_enabled);
        edSsid = findViewById(R.id.ed_ssid);
        tvStatus = findViewById(R.id.tv_status);
        tvOverride = findViewById(R.id.tv_override);
        tvSetup = findViewById(R.id.tv_setup);
        tvLog = findViewById(R.id.tv_log);
        btnOverride = findViewById(R.id.btn_override);
        btnCancel = findViewById(R.id.btn_cancel);
        btnSetup = findViewById(R.id.btn_setup);
        Button btnCurrent = findViewById(R.id.btn_current);
        Button btnTile = findViewById(R.id.btn_tile);
        Spinner spStep = findViewById(R.id.sp_step);

        sw.setChecked(p.enabled());
        sw.setOnCheckedChangeListener((v, on) -> {
            p.setEnabled(on);
            if (on) {
                Controller.ensureRunning(this);
                Controller.evaluate(this, null);
            } else {
                Controller.stopWatching(this);
            }
            refresh();
        });

        Switch swAudio = findViewById(R.id.sw_audio);
        swAudio.setChecked(p.keepForAudio());
        swAudio.setOnCheckedChangeListener((v, on) -> {
            p.setKeepForAudio(on);
            Controller.evaluate(this, null);
            refresh();
        });

        minutesSpinner(R.id.sp_arrive, ARRIVE, ARRIVE_LABELS, p.arriveDelayMin(), p::setArriveDelayMin);
        minutesSpinner(R.id.sp_leave, LEAVE, LEAVE_LABELS, p.leaveGraceMin(), p::setLeaveGraceMin);

        Switch swRestore = findViewById(R.id.sw_restore);
        swRestore.setChecked(p.restoreOnLeave());
        swRestore.setOnCheckedChangeListener((v, on) -> {
            p.setRestoreOnLeave(on);
            Controller.evaluate(this, null);
            refresh();
        });

        edSsid.setText(p.ssid());
        edSsid.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                p.setSsid(s.toString().trim());
            }
        });

        btnCurrent.setOnClickListener(v -> {
            String s = Wifi.currentSsid(this);
            if (s != null) {
                if (OverrideLogic.matches(p.ssid(), s)) {
                    toast("\"" + s + "\" is already in the list");
                } else {
                    edSsid.setText(OverrideLogic.addTarget(p.ssid(), s));
                }
            } else {
                toast(Wifi.wifiConnected(this)
                        ? "Android will not reveal the Wi-Fi name yet - finish Setup below"
                        : "Not connected to Wi-Fi");
            }
        });

        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, STEP_LABELS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spStep.setAdapter(ad);
        int sel = 1;
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i] == p.stepMinutes()) sel = i;
        spStep.setSelection(sel);
        spStep.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                p.setStepMinutes(STEPS[pos]);
                refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

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
        btnSetup.setOnClickListener(v -> runStep(nextStep()));
        btnTile.setOnClickListener(v -> addTile());
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
        Controller.ensureRunning(this); // refreshes the notification text after an SSID edit
        super.onPause();
    }

    /** A spinner of minute values; picking one stores it and re-applies the rule. */
    private void minutesSpinner(int id, int[] values, String[] labels, int current,
                                java.util.function.IntConsumer store) {
        Spinner sp = findViewById(id);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) sel = i;
        sp.setSelection(sel);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long rowId) {
                if (values[pos] == currentValue(id)) return;
                store.accept(values[pos]);
                Controller.evaluate(MainActivity.this, null);
                refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private int currentValue(int spinnerId) {
        return spinnerId == R.id.sp_arrive ? p.arriveDelayMin() : p.leaveGraceMin();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------- status ----------

    private void refresh() {
        long now = System.currentTimeMillis();
        long until = p.until();
        boolean active = OverrideLogic.active(now, until);

        String ssid = Wifi.currentSsid(this);
        String wifi = ssid != null ? "\"" + ssid + "\""
                : Wifi.wifiConnected(this) ? "connected, name hidden by Android (see Setup)" : "not connected";
        boolean match = OverrideLogic.matches(p.ssid(), ssid);
        tvStatus.setText("Wi-Fi now: " + wifi + (match ? "  (match)" : "")
                + "\nBluetooth: " + btStatus()
                + "\nWatcher: " + (!p.enabled() ? "switched off" : GuardService.running ? "running" : "starting"));

        btnOverride.setText(active ? "Keep Bluetooth on  +" + label(p.stepMinutes())
                : "Bluetooth on for " + label(p.stepMinutes()));
        btnCancel.setEnabled(active);
        tvOverride.setText(active
                ? "Bluetooth stays on until " + Controller.clock(this, until)
                        + " (" + OverrideLogic.remaining(now, until) + " left). Press again for longer."
                : "No timer running. Press again for longer, up to 24 h.");

        int next = nextStep();
        tvSetup.setText(mark(Perms.location(this)) + "Location permission (Android needs it to read the Wi-Fi name)\n"
                + (Build.VERSION.SDK_INT >= 29
                        ? mark(Perms.background(this)) + "Location set to \"Allow all the time\" (works with screen off)\n" : "")
                + (Build.VERSION.SDK_INT >= 31
                        ? mark(Perms.bluetooth(this)) + "Nearby devices permission (to switch Bluetooth)\n" : "")
                + mark(Perms.locationOn(this)) + "Location switched on in system settings\n"
                + mark(Perms.batteryExempt(this)) + "Battery: unrestricted (recommended)");
        btnSetup.setVisibility(next == 0 ? View.GONE : View.VISIBLE);
        btnSetup.setText(next == STEP_BATTERY ? "Allow unrestricted battery use" : "Fix next item");

        String ev = p.lastEvent();
        tvLog.setText(ev.isEmpty() ? "" : "Last action: " + ev);
    }

    private String btStatus() {
        if (!Bt.available(this)) return "not available";
        if (!Bt.isOn(this)) return p.offByApp() ? "off (switched off by this app)" : "off";
        String audio = p.keepForAudio() ? Bt.connectedAudio(this) : null;
        if (audio != null) return "on (kept on: " + audio + " connected)";
        long offAt = Controller.pendingOffAt(this);
        return offAt > 0 ? "on, goes off at " + Controller.clock(this, offAt) : "on";
    }

    private static String label(int minutes) {
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i] == minutes) return STEP_LABELS[i];
        return minutes + " min";
    }

    private static String mark(boolean ok) {
        return ok ? "\u2713  " : "\u2717  ";
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
