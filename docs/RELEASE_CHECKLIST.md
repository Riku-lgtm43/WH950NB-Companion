# Release checklist

This checklist covers the source and APK intended for publication. Checked items record what the developer has confirmed, not independent legal or security approval.

## Source and artwork

- [x] Developer confirms writing the Java app with help from ChatGPT and Google's Android documentation. No Edifier app or firmware source code was copied.
- [x] Developer confirms researching Bluetooth commands from HCI logs of personally owned WH950NB headphones, then testing them in Tasker before implementing them in Java. See [research notes](PROTOCOL_ORIGIN.md).
- [x] Developer identifies `assets/headphones.svg` and `assets/headphones.png` as created for this project with ChatGPT assistance.
- [x] Developer reports drawing the custom icons. The matching Material `expand_more` icon retains its Apache 2.0 notice.
- [ ] Check any outside code examples, tools, and artwork used in the release for license requirements. Developer statements do not independently prove ownership.

## Before publishing the next APK

- [ ] Review the exact source files for secrets, private keys, Bluetooth addresses, and raw HCI logs.
- [ ] Review the full Git history if publishing an existing repository.
- [ ] Inspect the final APK's permissions, contents, and signing certificate.
- [ ] Check exported Android components and how they handle outside requests. The notification connection receiver is exported; Quick Settings services and the Shizuku provider are exported for integration. Do not change them without testing.
- [ ] Run `bash test_protocol.sh`, `BUILD_VARIANT=debug bash build.sh`, and the [device tests](TESTING.md) on the exact source being released.
- [ ] Check the README, screenshots, app labels, and release page for accurate features and unofficial branding.
- [ ] If replacing Beta 1, confirm the new APK uses the original signing key so existing users can update.

## Earlier Beta 1 release

- [x] The developer reports publishing `v0.3.21-beta.1` as a GitHub pre-release.
- [x] The original APK reportedly passed a local `apksigner verify` check.
- [x] The developer reports testing the original Beta 1 APK with WH950NB on rooted Android 16 and root-mode Shizuku.

Earlier tests do not confirm a newly built APK. The original signing key has not been located in the reviewed Termux project files.

See [source and license review](SOURCE_LICENSE_AUDIT.md), [third-party notices](../THIRD_PARTY_NOTICES.md), and [legal notices](../LEGAL.md).

## Remaining security review

The manifest-declared Bluetooth connection receiver and dynamically registered codec-event receiver are exported to receive system Bluetooth broadcasts. Both need platform sender-protection or spoofing tests before changing their exported status, because making system-broadcast listeners non-exported can break delivery. The Shizuku user-service Binder entry point also requires access-path verification. No unauthorized access was demonstrated in the static review.

The build currently checks archive structure for downloaded Shizuku dependencies, but does not pin their hashes. The SDK archive uses a published SHA-1. Dependency hash pinning remains a future supply-chain improvement; no unverified hashes have been inserted.
