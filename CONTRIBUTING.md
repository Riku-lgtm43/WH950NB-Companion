# Contributing

Open an issue with device model, ROM, Android version, firmware version (if known) and reproducible steps. **Never post raw HCI logs, Bluetooth addresses, credentials or unredacted system dumps.** See [SECURITY.md](SECURITY.md).

Keep changes narrowly scoped and preserve the three-tab layout and bottom navigation unless a redesign is explicitly requested. Test noise controls, Shizuku codec requests, tiles, notifications and status feedback.

Run `bash test_protocol.sh` before proposing protocol changes, then `BUILD_VARIANT=debug bash build.sh` and the relevant [device checklist](docs/TESTING.md). The [Bluetooth flow guide](docs/BLUETOOTH_FLOW.md) explains command delivery and feedback. Self-tests do not replace testing on actual headphones.

This is a personal hobby project. Contributor responses, support and reviews are not guaranteed; see [LEGAL.md](LEGAL.md).
