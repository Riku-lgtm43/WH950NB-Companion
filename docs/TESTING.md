# Protocol checks and manual release checks

From the repository root, run `bash test_protocol.sh` with a Java JDK before rebuilding. These checks
cover command conversion and constructed protocol replies; they do not test
Android Bluetooth, real WH950NB hardware, or codec audio routing.

## Device regression checklist

These boxes are not marked as passed for this exact archive. Earlier tests do not replace a fresh release check.

On a clean Termux environment:

- [ ] `bash setup_termux.sh` succeeds and installs a valid Android 34 `android.jar`.
- [ ] `BUILD_VARIANT=debug bash build.sh` succeeds and produces a verifiable test APK.
- [ ] App launches on Android 16; status bar and bottom tabs remain visible.
- [ ] Controls, Sound, Settings tabs switch without losing layout or state.
- [ ] WH950NB shows connected only when the headphones are actually connected.
- [ ] Battery readback displays Android's available level.
- [ ] ANC, Ambient, Wind, Off commands operate; level sliders send on release.
- [ ] ANC/Ambient reported mode and last-sent state are not conflated.
- [ ] AAC/SBC codec readback and switching work with Shizuku on the tested root-mode setup.
- [ ] LDAC may be affected by ROM audio routing; test only where known working.
- [ ] Sound modes, EQ, and sleep timer behave as before; verify that EQ may be unavailable in Spatial mode and works after returning to Music mode.
- [ ] Notification appears when connected, disappears on disconnect, respects toggle.
- [ ] Notification pending/confirmed/failed feedback displays correctly.
- [ ] Quick Settings tiles operate after installation.
- [ ] Recording diagnostics is off by default; exported traces are reviewed.
- [ ] Font scale, display DPI, portrait/landscape, and disconnected state verified.
- [ ] Release-signing uses a private key not present in Git and APK verifies.

Do not claim tests have passed unless performed on a real device.

## October 19, 2026 developer test evidence

The developer supplied Android 16 diagnostic logs showing successful WH950NB detection, battery reading, ANC, Ambient, Wind and Off readbacks, and Shizuku codec switching between LDAC, AAC and SBC. Ambient, Wind and Off required a second command after the first verification attempts did not match. Sound modes, EQ presets and timer commands were transmitted, and the developer reported they worked physically; the app does not independently read back these settings. This evidence applies to the tested installation, not necessarily to a newly built APK from this updated archive. The unchecked release checklist above remains required.
