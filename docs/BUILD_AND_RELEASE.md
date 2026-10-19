# Build, install and sign

## Termux requirements

Install Termux from [F-Droid](https://f-droid.org/packages/com.termux/) or its [official releases](https://github.com/termux/termux-app/releases/). This is a Java/Android project built with scripts, not Gradle.

For a cloned repository, from the repository root:

```sh
bash setup_termux.sh
bash test_protocol.sh
BUILD_VARIANT=debug bash build.sh
```

For a GitHub-generated ZIP, enter its extracted project directory. For this prepared ZIP, the build scripts are at the top level. The setup script installs build tools and an Android API 34 framework from Google's official SDK distribution. Shizuku API 13.1.5 is downloaded from Maven Central on first build; internet is needed for these downloads. The app itself does not request INTERNET permission.

**Android SDK:** The maintainer's environment successfully built using `$HOME/android-sdk/platforms/android-34/android.jar`; its API 35 platform failed to load with `aapt2`. The SDK version used for compiling does not indicate a minimum Android runtime version.

If necessary, specify the working framework path:

```sh
ANDROID_JAR="$HOME/android-sdk/platforms/android-34/android.jar" BUILD_VARIANT=debug bash build.sh
```

If `aapt2` fails to load an include path, check the integrity and location of the framework JAR. A clean setup on other Termux installations has not been independently verified.

Development output: `build/WH950NB-Companion-v0_3_21-debug.apk` (copied to Downloads if Termux storage access is configured).

## Install and operate

Install the APK and pair/connect WH950NB in Android Bluetooth settings. Grant required Bluetooth permissions, then use Shizuku with suitable privileges only if you need supported advanced codec controls. An ADB-only Shizuku session may lack the privileges needed for codec requests.

The app keeps its original Android package ID, `com.xriku.edifiercontrol`, for data and installation compatibility. Updating requires a matching APK signing certificate.

## Release signing

`build.sh` creates a local development keystore at `~/.edifier-control-debug.keystore` with a known test password. **Never commit this file, publish it, or distribute a debug build as a public release.** Keep that same key if updating existing debug installations.

To distribute a new app, use a private **release keystore** stored outside the repository with a secure backup. **For updates to the published Beta 1 APK, use the original release keystore**: a new key will not match its signing certificate. The path below is an example, not evidence that a keystore exists there. Set the following environment variables for the build:

```sh
export BUILD_VARIANT=release
export EDIFIER_RELEASE_KEYSTORE="/path/to/your/actual-release-key.jks"
export EDIFIER_RELEASE_ALIAS='your-actual-key-alias'
# Enter private passwords without storing them in shell history or Git:
read -rs EDIFIER_RELEASE_STORE_PASS; export EDIFIER_RELEASE_STORE_PASS; echo
read -rs EDIFIER_RELEASE_KEY_PASS; export EDIFIER_RELEASE_KEY_PASS; echo
bash build.sh
unset EDIFIER_RELEASE_STORE_PASS EDIFIER_RELEASE_KEY_PASS
```

Expected release output: `build/WH950NB-Companion-v0_3_21-beta1.apk`. An APK signed with a different certificate cannot normally update an existing installation, whether the old APK was debug- or release-signed. Verify the certificate matches the published APK before replacing a release asset. If the original private key is unavailable, do not silently replace the Beta 1 APK with a differently signed build.

Before distributing an APK, complete [TESTING.md](TESTING.md) and [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md). The published Beta 1 APK has been reported successfully tested on one WH950NB with rooted Android 16 and root-mode Shizuku. This does not establish a clean-install build or complete regression pass on other devices.
