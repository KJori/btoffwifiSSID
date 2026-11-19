#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
CP="../build/classes:cfg:$(ls lib/*.jar | tr '\n' ':')"
rm -rf tc && mkdir -p tc
javac -nowarn -cp "$CP:../sdk/platforms/android-34/android.jar" -d tc src/local/btoffonwifi/AppTest.java || exit 2
timeout 480 java -Xmx1g -Drobolectric.offline=true -Drobolectric.dependency.dir=$PWD/androidall -Duser.home=$PWD/rhome \
     -Drobolectric.logging=stderr \
     -cp "tc:$CP:../sdk/platforms/android-34/android.jar" org.junit.runner.JUnitCore local.btoffonwifi.AppTest > test_out.txt 2>&1
echo "junit exit $?"
