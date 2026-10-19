# Bluetooth command flow (v0.3.21 beta)

This is a map for contributors. It describes the current implementation, not
a manufacturer specification. WH950NB commands are undocumented and may
vary with firmware. See `PROTOCOL_ORIGIN.md` for the developer-reported origin
and its verification limits.

## Noise controls: ANC, Ambient, Wind, Off

```text
App button or slider (MainActivity.dispatch)
         |   or NotificationActionReceiver / ControlTile
         v
BluetoothController.sendVerified(...)
         |
         v
Queue and connected-WH950NB check (ConnectedHeadphones)
         |
         v
Protocol.bytes(command) -> RFCOMM channel 18 -> headphone
         |
         v
Protocol.STATE_QUERY -> Protocol.parseState(reply)
         |
         v
Compare the returned mode/level against the requested mode/level
         |
         +---- matching readback -> observed state and confirmed feedback
         |
         +---- no matching readback -> not confirmed / failed feedback
         |
         v
Prefs + notification / Quick Settings tile refresh + callback to the app
```

Important details:

- A successful socket write **does not prove** that the headphones changed state.
- The controller serializes requests and tracks newer noise commands so a
  stale result does not overwrite a newer request.
- It first checks for convergence by issuing bounded state queries on the same
  socket. It does not blindly accept the first reported state if it differs.
- If repeated valid reads show an unchanged/different state, the current code
  permits **one extra write** of an idempotent noise command on a fresh socket.
  This retry is skipped if there is a newer request. Other failure paths may
  use a delayed, query-only fallback. EQ, sound mode, and sleep timer commands
  are not retried by this noise-command logic.
- `Protocol.State.confidence` distinguishes `OBSERVED`, `INFERRED`, and
  `UNKNOWN` protocol mappings. A valid inferred mapping is not independently
  proven on every device or firmware revision.
- The app deliberately describes non-noise commands (EQ, sound mode and
  sleep timer) as sent but *not confirmed*, because the noise-state query does
  not validate these settings.

## Files to read first

| File | Responsibility |
| --- | --- |
| `Protocol.java` | Command hex, state query, state reply decoding |
| `BluetoothController.java` | Queue, RFCOMM transport, state verification, retry decisions |
| `ConnectedHeadphones.java` | Discover connected WH950NB through Android Bluetooth profiles |
| `Prefs.java` | Store last requests, observations, and notification feedback |
| `MainActivity.java` | Button/slider handlers, status messages, three-tab UI |
| `NotificationActionReceiver.java` | ANC/Ambient/Wind notification actions |
| `ControlTile.java` | Quick Settings action and tile status |
| `NotificationHelper.java` | Connection-aware notification display |
| `Diagnostics.java` | Optional local trace events |

## Codec switching is a separate path

The **Sound** tab reads Android's current codec and offers manual LDAC/AAC/SBC
requests. That path uses `MainActivity`, `CodecDumpReader`, `CodecRootHelper`,
and `CodecShizukuService` with Shizuku and suitable privileges where supported. It is **not**
a WH950NB RFCOMM mode command. Android reporting a selected codec does not
prove that audio is routed to the headphones on every custom ROM.

## Tests and safe changes

Run `bash test_protocol.sh` to check command decoding and constructed example
reply frames using only a Java JDK. These tests do not establish compatibility
with other physical headphones or Android versions. Use `TESTING.md` for the
remaining device-only regression checks after changing app behavior.

Preserve the three tabs and **bottom navigation**. Avoid changing Bluetooth
retry rules, notifications, or confirmation semantics without targeted tests
on actual headphones.
