#!/usr/bin/env bash
# Runs the Robolectric suite after ./build.sh, fetching the test libraries from Maven Central.
# Used by the GitHub Actions workflow; also works locally with network access.
set -uo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
SDK="${ANDROID_SDK_ROOT:-$ROOT/sdk}"
PLAT="$SDK/platforms/android-34/android.jar"

mvn -q -B dependency:copy-dependencies -DoutputDirectory=lib || exit 2
# Some dependencies (androidx.test) are Android .aar archives: put their classes.jar on the path.
for aar in lib/*.aar; do
    [ -e "$aar" ] || continue
    unzip -p "$aar" classes.jar > "${aar%.aar}.jar"
done

# Point Robolectric at the manifest and the resources compiled by build.sh.
mkdir -p cfg/com/android/tools
cat > cfg/com/android/tools/test_config.properties <<EOF
android_merged_manifest=$ROOT/app/AndroidManifest.xml
android_resource_apk=$ROOT/build/base.apk
android_custom_package=local.btoffonwifi
EOF

CP="$ROOT/build/classes:cfg:$(ls lib/*.jar | tr '\n' ':')"
rm -rf tc && mkdir -p tc
javac -nowarn -cp "$CP:$PLAT" -d tc src/local/btoffonwifi/*.java || exit 2
mkdir -p screenshots
java -Xmx2g -Dscreenshots.dir="$PWD/screenshots" -cp "tc:$CP:$PLAT" org.junit.runner.JUnitCore \
    local.btoffonwifi.AppTest local.btoffonwifi.ScreenshotTest > test_out.txt 2>&1
status=$?

grep -E '^(OK \(|Tests run:)' test_out.txt
# Full trace of the first failure, for the log.
awk '/^1\) /{p=1} /^2\) /{exit} p' test_out.txt | head -40
# Each failure as a GitHub annotation, so it shows on the run page.
grep -A2 -E '^[0-9]+\) ' test_out.txt | grep -v '^--$' | paste - - - | while IFS= read -r line; do
    echo "::error title=Test failure::$line"
done
exit $status
