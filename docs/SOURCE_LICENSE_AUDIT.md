# Source and license review

**Updated:** 2026-10-19  
**Project:** WH950NB Companion v0.3.21 Beta 1

This records what was found in the source ZIP and what the developer confirmed. It is not a legal opinion, a full security audit, or an inspection of the published APK.

## Source and artwork

The developer confirms writing the Java app with help from ChatGPT and Google's official Android documentation. Bluetooth commands were researched using Android HCI logs from the developer's own WH950NB headphones, then tested in Tasker before being added to the Java app. The developer says no Edifier app or firmware source code was copied. The original HCI captures were not independently reviewed.

The developer reports manually drawing the custom Android vector icons. `ic_expand_more.xml` matches Google's Material `expand_more` icon, so its Apache 2.0 notice remains. The headphone SVG and PNG were made for this project with ChatGPT assistance. These statements explain the origin of the assets but cannot prove that every design is original or free of third-party rights.

## Source ZIP findings

| Item | Finding |
| --- | --- |
| Java source | Included; developer-confirmed origin |
| Headphone SVG and PNG | Included; ChatGPT-assisted according to developer |
| Android vector icons | Included; developer reports manual creation, with Material icon attribution |
| Shizuku 13.1.5 | Downloaded during setup; MIT license notice included |
| Manufacturer APK, firmware, or raw HCI captures | Not found in the reviewed source ZIP |
| Private signing key or release password | Not found in the reviewed source ZIP |
| Android INTERNET permission | Not requested in `AndroidManifest.xml` |
| Local diagnostics | Optional; shared logs may contain device identifiers |

The ZIP review does not cover older Git commits or establish that the published APK matches the source. Android build tools are installed separately. The project's MIT license applies only to material the publisher has the right to license.

## Still to check

Before releasing a new APK, inspect the final build and signing certificate, check any reused third-party code or assets, and run the device tests. Review exported Android components and any existing Git history. The original release signing key is needed to publish an APK that installs over the existing Beta 1 app.

See the [release checklist](RELEASE_CHECKLIST.md), [build guide](BUILD_AND_RELEASE.md), and [third-party notices](../THIRD_PARTY_NOTICES.md).

## References

- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
- [Google Material Icons](https://github.com/google/material-design-icons)
- [Android developer documentation](https://developer.android.com/)
