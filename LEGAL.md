# Legal and project notices

This page collects the project's public-facing legal and maintenance notices in one place. See the [MIT license](LICENSE) for the license text and [third-party notices](THIRD_PARTY_NOTICES.md) for dependency and asset attribution.

## Independent project and trademarks

**WH950NB Companion is an unofficial, independently developed hobby app.** It is **not made by, affiliated with, endorsed by, licensed by, certified by, or supported by Edifier**. The name Edifier and model identifier WH950NB are used only to describe compatibility with the headphones. The project makes no claim to those marks.

The public name, screenshots and download pages should not imply manufacturer approval.

## Beta software, warranty and maintenance

This is an experimental beta, developed for learning and personal use and shared as a convenience to other owners. There is **no commitment** to future updates, fixes, new features, support, or responses to issues. Compatibility, reliability and continuing development are not guaranteed.

The software is distributed **as-is**, subject to the warranty disclaimer and other terms in [LICENSE](LICENSE). Users should review the code, permissions and their own device's compatibility before installing it.

## Hardware and privileges

The maintainer has reported successful use on one WH950NB with rooted Android 16 and root-mode Shizuku. Behavior on other devices, firmware and custom ROMs remains unverified. Codec requests rely on privileged Android Bluetooth functionality; a successful request does not prove that audio will route to the headphones.

Shizuku permissions are powerful. Only grant them to software you have reviewed and trust. See [SECURITY.md](SECURITY.md).

## Protocol research and source provenance

The developer reports that Bluetooth command information was obtained from **Bluetooth HCI logs captured while using personally owned WH950NB headphones**, and was later used in a Tasker control project before the Java implementation. Original HCI logs have not been independently examined. This account does not establish the terms of any third-party software that may have generated traffic or authorize copying proprietary implementation code.

No raw HCI logs, manufacturer APK or firmware are intentionally supplied in this source archive. Do not upload raw captures, confidential data, MAC addresses or unrelated third-party traffic. Further details: [docs/PROTOCOL_ORIGIN.md](docs/PROTOCOL_ORIGIN.md).

## Copyright, artwork and third-party material

- Project source is distributed with an [MIT license](LICENSE), **only to the extent the publisher holds the rights needed to license each contribution**. A copyright line does not prove ownership.
- The headphone illustrations in `assets/headphones.svg` and `assets/headphones.png` were created by the project developer with assistance from OpenAI's ChatGPT. They are unofficial, unbranded illustrations, not official Edifier artwork. AI-generated content may not be unique, and its inclusion does not guarantee exclusive copyright protection or freedom from third-party rights.
- Shizuku is a third-party dependency licensed separately under MIT.
- The developer reports manually creating the custom vector icons using common interface design conventions. The `ic_expand_more.xml` path matches a published Google Material icon, so the corresponding Apache-2.0 notice is retained. This account is not independent verification of asset ownership. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Scope of this review

The presence of these notices, ownership claims or protocol research descriptions **does not grant legal clearance**, guarantee non-infringement or prevent a complaint. Legal and contractual rules depend on the specific facts and jurisdiction. When necessary, obtain advice from a qualified professional.

For the release steps that are still unverified, see [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).
