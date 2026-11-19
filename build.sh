#!/usr/bin/env bash
# Builds the APK with the Android SDK command-line tools only (no Gradle, no libraries).
# Needs: JDK 17, and an Android SDK with build-tools 34.0.0 and platforms;android-34.
set -euo pipefail
SDK="${ANDROID_SDK_ROOT:-$PWD/sdk}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
APP=app
OUT=build
KS="${KEYSTORE:-signing/release.jks}"
KS_PASS="${KEYSTORE_PASS:-btoffonwifi}"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLAT" --manifest "$APP/AndroidManifest.xml" \
    --java "$OUT/gen" "$OUT/res.zip"

javac -source 8 -target 8 -Xlint:-options -Xlint:deprecation \
    -bootclasspath "$PLAT" -cp "$BT/core-lambda-stubs.jar" \
    -d "$OUT/classes" $(find "$APP/src" "$OUT/gen" -name '*.java')

"$BT/d8" --release --lib "$PLAT" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j ../base.apk classes.dex)

"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias app \
        -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=BT Off on Wi-Fi"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias app \
    --out BtOffOnWifi.apk "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose --min-sdk-version 24 BtOffOnWifi.apk | head -6
