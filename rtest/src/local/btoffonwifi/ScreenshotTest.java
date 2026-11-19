package local.btoffonwifi;

import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWifiInfo;

/** Renders the main screen in a few states to PNG files (for the README and for review). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h851dp-xxhdpi")
public class ScreenshotTest {
    private Application app;
    private BluetoothAdapter bt;
    private Prefs p;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT);
        shadowOf((LocationManager) app.getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(true);
        shadowOf((PowerManager) app.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(app.getPackageName(), true);
        bt = ((BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        Bt.resetProfiles();
        p = new Prefs(app);
        p.setEnabled(true);
        p.setSsid("Home\nOffice");
        p.setArriveDelayMin(5);
    }

    private void onWifi(String ssid) {
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf((WifiManager) app.getSystemService(Context.WIFI_SERVICE)).setConnectionInfo(info);
    }

    private void shoot(String name) throws Exception {
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        View content = ac.get().findViewById(R.id.content);
        int w = ac.get().getResources().getDisplayMetrics().widthPixels;
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        content.layout(0, 0, w, content.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(w, content.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(ac.get().getColor(R.color.bg));
        content.draw(c);
        File dir = new File(System.getProperty("screenshots.dir", "screenshots"));
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        ac.pause().stop().destroy();
    }

    @Test
    public void homeLight() throws Exception {
        onWifi("Home");
        bt.disable();
        p.setOffByApp(true);
        p.setLastEvent("18:37  Bluetooth turned off (on \"Home\")");
        shoot("1-home-light");
    }

    @Test
    @Config(qualifiers = "+night")
    public void homeDark() throws Exception {
        onWifi("Home");
        bt.disable();
        p.setOffByApp(true);
        p.setLastEvent("18:37  Bluetooth turned off (on \"Home\")");
        shoot("2-home-dark");
    }

    @Test
    public void timerLight() throws Exception {
        onWifi("Home");
        bt.enable();
        Controller.pressOverride(app);
        Controller.pressOverride(app);
        shoot("3-timer-light");
    }

    @Test
    @Config(qualifiers = "+night")
    public void arrivingDark() throws Exception {
        onWifi("Home");
        bt.enable();
        p.setHomeSince(System.currentTimeMillis() - 2 * 60_000L);
        shoot("4-arriving-dark");
    }

    @Test
    public void setupLight() throws Exception {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT);
        p.setSsid("");
        onWifi("Home");
        shoot("5-setup-light");
    }
}
