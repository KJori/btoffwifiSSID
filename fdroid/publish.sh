#!/usr/bin/env bash
# Adds the APK built by ./build.sh to the F-Droid repository kept on the "fdroid" branch, served at
#   https://raw.githubusercontent.com/KJori/btoffwifiSSID/fdroid/repo
# Env: FDROID_KEYSTORE_B64  repo signing key (PKCS12, base64). Without it: dry run with a throwaway key.
#      PUBLISH=yes          push the result (only with the real key).
#      ANDROID_HOME         Android SDK; fdroidserver must be on PATH.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
APP=local.btoffonwifi
VC=$(grep -o 'android:versionCode="[0-9]*"' app/AndroidManifest.xml | grep -o '[0-9]*')
PASS=fdroidrepo   # the key file is the secret; its password adds nothing once the file leaks
SITE="$ROOT/fdroid-site"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

rm -rf "$SITE"
git worktree prune
if git fetch -q --depth 1 origin fdroid 2>/dev/null; then
    git worktree add -q --detach "$SITE" FETCH_HEAD
else
    echo "No fdroid branch yet: starting the repository"
    git worktree add -q --orphan -b fdroid-start "$SITE"
fi
cd "$SITE"

if [ -e "repo/${APP}_${VC}.apk" ]; then
    echo "versionCode $VC is already in the F-Droid repo"
    exit 0
fi
mkdir -p repo metadata
cp "$ROOT/BtOffOnWifi.apk" "repo/${APP}_${VC}.apk"
cp "$ROOT/fdroid/$APP.yml" "metadata/$APP.yml"

if [ -n "${FDROID_KEYSTORE_B64:-}" ]; then
    echo "$FDROID_KEYSTORE_B64" | base64 -d > "$TMP/repo.p12"
else
    echo "::warning title=F-Droid dry run::No FDROID_KEYSTORE_B64 secret: index signed with a throwaway key, nothing published."
    keytool -genkeypair -keystore "$TMP/repo.p12" -storetype PKCS12 -alias fdroidrepo -keyalg RSA \
        -keysize 2048 -validity 1 -storepass "$PASS" -keypass "$PASS" -dname "CN=throwaway" 2>/dev/null
    PUBLISH=no
fi

cat > config.yml <<EOF
repo_url: https://raw.githubusercontent.com/KJori/btoffwifiSSID/fdroid/repo
repo_name: KJori apps
repo_description: >-
  Apps built and signed by GitHub Actions from https://github.com/KJori/btoffwifiSSID
archive_older: 0
keystore: $TMP/repo.p12
repo_keyalias: fdroidrepo
keystorepass: $PASS
keypass: $PASS
keydname: CN=KJori F-Droid repo
sdk_path: ${ANDROID_HOME:-$ROOT/sdk}
EOF
chmod 600 config.yml
fdroid update --pretty
rm -f config.yml

echo "Repository contents:"
ls repo
grep -q "\"$APP\"" repo/index-v2.json || { echo "::error::$APP missing from index-v2.json"; exit 1; }

git add repo metadata
git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit -q -m "F-Droid repo: add $APP versionCode $VC"
if [ "${PUBLISH:-no}" = "yes" ]; then
    git push -q origin HEAD:refs/heads/fdroid
    echo "Published versionCode $VC to the fdroid branch"
else
    echo "Dry run: not pushed"
fi
