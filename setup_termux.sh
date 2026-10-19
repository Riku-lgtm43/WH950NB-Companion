#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

# Tested project build baseline: Android API 34 framework with Termux aapt2.
# Some Termux aapt2 builds cannot load an API 35 framework, even if it exists.
pkg update -y
pkg install -y openjdk-21 aapt2 d8 apksigner curl zip unzip python coreutils

SDK_DIR="$HOME/android-sdk/platforms/android-34"
FRAMEWORK="$SDK_DIR/android.jar"
ARCHIVE="${TMPDIR:-$HOME}/edifier-platform-34.zip"
# This SHA-1 comes from Google's Android SDK repository metadata for
# platforms;android-34 revision 3, file platform-34-ext7_r03.zip.
EXPECTED_SHA1='1f2e9478d6a7601425ceaa553311dc43191f103d'
URL='https://dl.google.com/android/repository/platform-34-ext7_r03.zip'

mkdir -p "$SDK_DIR"
if [ -s "$FRAMEWORK" ]; then
  if unzip -tq "$FRAMEWORK" >/dev/null 2>&1; then
    printf 'Using installed Android 34 framework: %s\n' "$FRAMEWORK"
    printf '\nSetup complete. Run: bash build.sh\n'
    exit 0
  fi
  echo "Existing $FRAMEWORK is not a valid ZIP archive. Back it up or remove it before rerunning." >&2
  exit 1
fi

if [ ! -s "$ARCHIVE" ]; then
  echo 'Downloading official Android 34 platform archive (~63 MB)...'
  curl --fail --location --retry 2 --connect-timeout 20 --max-time 300 \
       "$URL" --output "$ARCHIVE"
fi

# Validate the archive before unpacking anything from it.
printf '%s  %s\n' "$EXPECTED_SHA1" "$ARCHIVE" | sha1sum -c - || {
  echo "Checksum failed. Remove $ARCHIVE and rerun setup to download a fresh copy." >&2
  exit 1
}
JAR_PATH="$(unzip -Z1 "$ARCHIVE" | grep -E '(^|/)android[.]jar$' | head -n 1)"
if [ -z "$JAR_PATH" ]; then
  echo 'android.jar missing from official platform archive' >&2
  exit 1
fi
TEMP_JAR="${FRAMEWORK}.tmp.$$"
trap 'rm -f "$TEMP_JAR"' EXIT
unzip -p "$ARCHIVE" "$JAR_PATH" > "$TEMP_JAR"
unzip -tq "$TEMP_JAR" >/dev/null || {
  echo 'Extracted framework jar is corrupt' >&2
  exit 1
}
mv "$TEMP_JAR" "$FRAMEWORK"
trap - EXIT
rm -f "$ARCHIVE"
printf '\nInstalled: %s\nSetup complete. Run: bash build.sh\n' "$FRAMEWORK"
