# BT Off on Wi-Fi

Turns Bluetooth off while the phone is connected to one chosen Wi-Fi network (SSID).
A timer keeps Bluetooth on temporarily; each press of the app button or the quick-settings
tile ("BT timer") adds one step (15 min / 30 min / 1 h / 2 h, selectable), up to 24 h.

Package `local.btoffonwifi`, version 1.0. Android 7.0 or newer. No internet permission.

## Install
1. Copy `BtOffOnWifi.apk` to the phone and open it. Allow "install unknown apps" when asked.
2. Play Protect may warn that the app is built for an older Android version. That is
   deliberate (see Limits). Choose "Install anyway".
3. Open the app, enter the Wi-Fi name (or tap "Use current Wi-Fi"), switch the top toggle on.
4. Tap "Fix next item" until every Setup line shows a tick.
5. Tap "Add quick settings tile".

## Behaviour
- Connected to the chosen Wi-Fi, no timer running: Bluetooth is switched off. If Bluetooth is
  switched on from the system toggle in that state, it is switched off again.
- Button or tile press: Bluetooth on, timer +1 step. Tile shows "On until HH:MM".
- Timer ends: Bluetooth goes off if the phone is on the chosen Wi-Fi, otherwise it is left alone.
- Switching Bluetooth off yourself during a timer ends the timer. "End timer now" does the same
  from the app.
- Survives reboot. A low-priority notification is shown while watching; it can be hidden in the
  notification settings.

## Limits
- Android 13+ only lets apps that target Android 12 or older switch Bluetooth. The app therefore
  targets API 31. Do not raise `targetSdkVersion`. A future Android version could remove this
  allowance or refuse to install apps targeting API 31; no app can prevent that.
- Android only reveals the Wi-Fi name to apps with location permission ("Allow all the time")
  and with Location switched on. The app does not use the position itself.
- Some manufacturers (Xiaomi, Huawei, Oppo, older Samsung) stop background apps aggressively.
  Set battery use to unrestricted; a 15-minute periodic check restarts the watcher if needed.
- Tested on a simulated Android framework (Robolectric; Android 7.0, 12, 14, 15), not on a
  physical phone.

## Rebuild
Needs JDK 17 and an Android SDK with `build-tools;34.0.0` and `platforms;android-34`.
`ANDROID_SDK_ROOT=/path/to/sdk ./build.sh` writes `BtOffOnWifi.apk`.
`signing/release.jks` is the signing key; a rebuilt APK only installs over the existing app
if it is signed with the same key. `rtest/` holds the test suite (`rtest/run.sh`).
