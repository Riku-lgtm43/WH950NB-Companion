# Security and privacy

- The app does not request INTERNET or location permission and has no built-in analytics.
- The selected Bluetooth device address is kept in app-private preferences, not hardcoded in source.
- Optional diagnostic traces are local, size-limited and off by default. Review them before sharing; exported logs can expose device identifiers.
- Codec controls use Shizuku with privileged access. Only grant access to an app you trust.
- Keep private signing keys and passwords outside the repository.

For a vulnerability report, use a private GitHub security advisory when available. Do not include secrets or unredacted device logs in public issues.

This is not a security certification. See [LEGAL.md](LEGAL.md) for general warranty and project notices.
