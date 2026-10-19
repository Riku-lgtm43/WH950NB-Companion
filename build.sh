#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

for bin in aapt2 javac d8 jar zip unzip python apksigner keytool curl; do
  command -v "$bin" >/dev/null || {
    echo "Missing $bin. Run: bash setup_termux.sh" >&2
    exit 1
  }
done

# API 34 confirmed working with the Termux aapt2 toolchain on Android 16.
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-34/android.jar}"
if [ ! -s "$ANDROID_JAR" ] || ! unzip -tq "$ANDROID_JAR" >/dev/null 2>&1; then
  echo "Missing/corrupt Android framework: $ANDROID_JAR" >&2
  echo 'Run: bash setup_termux.sh' >&2
  exit 1
fi

# Use official Shizuku API 13.1.5; download on first build, never commit binaries.
SHIZUKU_LIBS="libs/shizuku"
mkdir -p "$SHIZUKU_LIBS"
for name in api aidl shared provider; do
  jarfile="$SHIZUKU_LIBS/$name.jar"
  if [ -s "$jarfile" ] && unzip -tq "$jarfile" >/dev/null 2>&1; then
    continue
  fi
  echo "Fetching Shizuku $name version 13.1.5 from Maven Central..."
  url="https://repo.maven.apache.org/maven2/dev/rikka/shizuku/$name/13.1.5/$name-13.1.5.aar"
  tmpaar="$SHIZUKU_LIBS/$name.aar.tmp"
  tmpjar="$SHIZUKU_LIBS/$name.jar.tmp"
  curl --fail --location --retry 2 --connect-timeout 20 --max-time 90 \
    --silent --show-error "$url" -o "$tmpaar"
  unzip -tq "$tmpaar" >/dev/null || {
    echo "Invalid Shizuku $name archive" >&2
    exit 1
  }
  unzip -p "$tmpaar" classes.jar > "$tmpjar"
  unzip -tq "$tmpjar" >/dev/null || {
    echo "Invalid Shizuku $name classes.jar" >&2
    exit 1
  }
  mv "$tmpjar" "$jarfile"
  rm -f "$tmpaar"
done
LIB_CP="$SHIZUKU_LIBS/api.jar:$SHIZUKU_LIBS/aidl.jar:$SHIZUKU_LIBS/shared.jar:$SHIZUKU_LIBS/provider.jar"

if [ ! -s assets/headphones.png ]; then
  echo 'Missing bundled headphone illustration: assets/headphones.png' >&2
  exit 1
fi

rm -rf build
mkdir -p build/classes build/gen build/dex

echo '[1/5] Compile and link Android resources'
aapt2 compile --dir res -o build/compiled.zip
aapt2 link -o build/base.apk --manifest AndroidManifest.xml \
  -I "$ANDROID_JAR" --java build/gen -A assets \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code 25 --version-name 0.3.21 build/compiled.zip

echo '[2/5] Compile Java'
find src build/gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -cp "$ANDROID_JAR:$LIB_CP" \
  -d build/classes @build/sources.txt

echo '[3/5] Convert to DEX'
jar cf build/classes.jar -C build/classes .
d8 --min-api 26 --lib "$ANDROID_JAR" --output build/dex \
  build/classes.jar "$SHIZUKU_LIBS/api.jar" "$SHIZUKU_LIBS/aidl.jar" \
  "$SHIZUKU_LIBS/shared.jar" "$SHIZUKU_LIBS/provider.jar"

echo '[4/5] Package and align'
(cd build/dex && zip -q ../base.apk classes.dex)
python align_apk.py build/base.apk build/unsigned-aligned.apk

echo '[5/5] Sign APK'
BUILD_VARIANT="${BUILD_VARIANT:-debug}"
case "$BUILD_VARIANT" in
  debug)
    # Local test signing only. KEEP the existing key to allow updates over
    # previously installed private test builds; never publish this as a release.
    KEYSTORE="$HOME/.edifier-control-debug.keystore"
    if [ ! -f "$KEYSTORE" ]; then
      keytool -genkeypair -keyalg RSA -keysize 2048 -validity 3650 \
        -alias edifier -keystore "$KEYSTORE" -storepass android \
        -keypass android -dname 'CN=XRiku Development' -noprompt >/dev/null
      chmod 600 "$KEYSTORE"
    fi
    OUTPUT='build/WH950NB-Companion-v0_3_21-debug.apk'
    apksigner sign --ks "$KEYSTORE" --ks-key-alias edifier \
      --ks-pass pass:android --key-pass pass:android \
      --out "$OUTPUT" build/unsigned-aligned.apk
    echo 'NOTICE: Development-signed APK. Do not distribute it as the public release.'
    ;;
  release)
    : "${EDIFIER_RELEASE_KEYSTORE:?Set path to your private release keystore}"
    : "${EDIFIER_RELEASE_ALIAS:?Set your release key alias}"
    : "${EDIFIER_RELEASE_STORE_PASS:?Set the keystore password in an environment variable}"
    : "${EDIFIER_RELEASE_KEY_PASS:?Set the key password in an environment variable}"
    [ -f "$EDIFIER_RELEASE_KEYSTORE" ] || { echo 'Release keystore not found' >&2; exit 1; }
    OUTPUT='build/WH950NB-Companion-v0_3_21-beta1.apk'
    apksigner sign --ks "$EDIFIER_RELEASE_KEYSTORE" \
      --ks-key-alias "$EDIFIER_RELEASE_ALIAS" \
      --ks-pass env:EDIFIER_RELEASE_STORE_PASS \
      --key-pass env:EDIFIER_RELEASE_KEY_PASS \
      --out "$OUTPUT" build/unsigned-aligned.apk
    ;;
  *) echo "Unknown BUILD_VARIANT='$BUILD_VARIANT'; choose debug or release" >&2; exit 1 ;;
esac
apksigner verify "$OUTPUT"
printf '\nSUCCESS: %s/%s\n' "$PWD" "$OUTPUT"
if [ -d "$HOME/storage/downloads" ]; then
  cp "$OUTPUT" "$HOME/storage/downloads/$(basename "$OUTPUT")"
  echo 'Copied APK to Downloads.'
else
  echo 'To copy the APK into Downloads: termux-setup-storage'
fi
