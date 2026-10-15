# How StemSync Air keeps time

## System boundary

This is an Android application above the wireless link layer. Nearby Connections selects local transports. The app neither knows which transport is active nor implements a Bluetooth audio profile. Google Play services is a device dependency; no application server, user account, or streaming service is involved. Install/update Play services before going offline. Google documents SDK usage analytics separately; this app adds no telemetry of its own.

One advertiser hosts a P2P_STAR room. One discoverer joins. The protocol uses endpoint IDs independently of display names, and requires local consent to the Nearby authentication digits plus a successful mutual connection result. A second guest is explicitly rejected in this release. The transport abstraction and participant list allow future fanout, but multiple guests are not supported by the current product policy.

## Clock estimate

All timestamps are `SystemClock.elapsedRealtimeNanos()` in nanoseconds. No wall-clock timestamp participates in scheduling. For guest request send t1, host callback receive t2, host reply construction t3, and guest callback receive t4:

```
theta = ((t2 - t1) + (t3 - t4)) / 2    // host minus guest
rtt   = (t4 - t1) - (t3 - t2)
hostNow = guestNow + theta
localDeadline = hostDeadline - theta
```

RTT excludes the measured host response processing interval. It still contains SDK and callback scheduling delays. t3 precedes serialization and SDK queuing; that uncertainty is part of the reverse path. The unit test with an injected +37 ms clock offset recovers +37 ms for symmetric links. Forward 30 ms and reverse 10 ms produces +47 ms: bias is `(forward - reverse)/2`, not zero.

The estimator stores 24 valid samples, takes the five lowest RTT samples, then uses their median offset. At least five samples are needed for readiness. Requests repeat every 750 ms; replies must match an outstanding t1 and arrive within five seconds. Negative timestamps, reversed local intervals, negative network RTT and overlong exchanges are rejected. The newest successful sample expires after ten seconds. Population standard deviation over the rolling RTT samples is displayed as RTT variation; it is not RSSI or radio signal strength.

When playing, offset changes are limited to 0.25 ms per accepted sample. This suppresses abrupt timeline movement at the cost of slower recovery from a real offset change. When stopped, the estimate may update immediately. Reconnecting clears the estimator, then requires a fresh set of samples.

## Shared timeline and scheduling

A timeline carries session ID, song ID, monotonic revision, playing state, base position, effective host time, duration and rate. Rate is fixed at one for the MVP. For playing state:

```
positionMs = clamp(basePositionMs + max(0, hostNow - effectiveHostTimeNs)/1e6,
                   0, durationMs)
```

Paused state holds the base position. A future revision remains pending while the previous revision continues, which is essential for scheduled pause and seek. Older and duplicate revisions cannot replace newer ones. A different session must explicitly reset the store.

The host checks local preparation and all connected guests' verified-file, decoder, foreground and clock readiness. It publishes transitions one second ahead. Guests acknowledge receipt; the host retries once after 700 ms if the revision is unacknowledged and shows the acknowledgement status. This is best-effort coordination, not an atomic distributed commit: a partition near the deadline may leave the host playing alone. Reliable disconnect callbacks and manual reconnect are the recovery path.

A main-thread coroutine checks deadlines every 20 ms. This is deliberately honest soft scheduling, not a real-time audio callback. Callback scheduling, decoder readiness and Android output latency affect actual onset. If a state arrives late, its target position is advanced to the current shared time. Switching a local stem prepares another file at that same musical position; preparation can produce a short gap.

A positive speaker calibration delay evaluates the timeline at `hostNow - delayNs`. It shifts both the start deadline and the subsequent target position. The range is ±300 ms in 5 ms UI steps. It is a manual listening adjustment, not a hardware-latency measurement.

## Drift policy

Every 500 ms during active playback:

```
errorMs = player.currentPosition - expectedCalibratedPosition
```

An error below 25 ms resets correction. Three consecutive errors of the same sign are required. Persistent errors up to 250 ms use 0.995× if ahead and 1.005× if behind. Larger persistent errors seek to the target. A seek resets persistence. Playback speed returns to 1× in the deadband or on a new revision. The ±0.5% correction can take many seconds to remove moderate offsets, deliberately favoring continuity over aggressive seeking.

The displayed “Estimated playback timeline error” compares Android's reported player position to an estimated clock timeline. It is **not measured acoustic synchronization error**. Speaker buffers, DAC latency, audio paths and sound propagation remain unobserved.

## Files and protocol

Only host-selected files or the explicitly loaded original demo can be sent. JSON control messages are limited to 24,000 bytes, use protocol version 1, and include a session ID, sender ID, random message ID, sequence, and monotonic send timestamp. Duplicate message IDs are suppressed in a 1,024-entry window. Playback revisions, rather than generic envelope sequence numbers, determine ordering of timeline messages. Guests cannot publish manifests or timelines to the host.

Each manifest is limited to eight entries; the picker allows six. Each entry contains song/content/stem identity, display name, safe filename, byte size, WAV format, duration, sample rate and SHA-256. Metadata associates a Nearby FILE payload ID with an entry already accepted in the manifest. Sender-controlled filenames never choose a destination: private storage is named by the validated hex digest.

A received payload is usable only after all three conditions hold: metadata, FILE arrival, successful transfer completion. The gate is independent of arrival order. The receiver opens the supported payload URI with ContentResolver, copies at most the declared byte count to a private temporary file, validates length, SHA-256, RIFF structure, PCM format, sample rate and duration, and renames into the private cache. Only then is FILE_READY sent. The original received URI is deleted after processing; payload resources are closed. The sender waits for the receiver's verification acknowledgement before marking READY.

Identical verified content may be reused after rehashing the private file and comparing both content ID and checksum. The guest never installs demo assets merely because they are bundled in its APK. Initial demo participation therefore exercises genuine transfer. A separate host-only import provides the stronger hardware test.

Transfers timeout after 120 seconds. Retry requests a new payload; interrupted transfers restart from zero. No partial resume is claimed. Explicit cancellation and connection loss remove in-flight state. File size is capped at 100 MiB; valid formats are 16-bit PCM WAV, mono/stereo, 22.05/24/44.1/48 kHz, up to one hour. Multi-file imports check duration and rate; matching lengths cannot prove semantic musical alignment.

## Lifecycle and permissions

Minimum API 29, target/compile API 36. Android 29–32 requests fine and coarse location together for legacy Nearby discovery (precise access is required by that SDK path), and READ_EXTERNAL_STORAGE for the legacy Nearby FILE receive path; API 31+ requests Bluetooth scan/connect/advertise; API 33+ requests nearby Wi-Fi devices. The app does not use those permissions to derive location. API 37's ACCESS_LOCAL_NETWORK requirement is not enabled by this API 36 target; revisit the official Nearby and local-network guidance before raising the target.

Audio selection uses the Storage Access Framework. Modern received-file handling uses URI access, not arbitrary Downloads paths. Modern READ_MEDIA_AUDIO, microphone and background-location permissions are absent. The legacy storage permission is capped at API 32 and is used for Nearby received files; user imports still use SAF. Wi-Fi state is read to explain disabled radios. CHANGE_WIFI_STATE is declared without an upper SDK cap: the pinned Nearby 19.3.0 explicitly required it when advertising on the API 35 emulator (8033 when absent). The app does not call radio enable/disable APIs. The app never enables radios silently. Denied permissions are retried only after a user action, and permanently denied permissions direct the user to Settings.

Foreground operation is the supported lifecycle. Leaving the activity pauses local playback; a host also schedules a global pause. Returning resynchronizes to the current state. A guest tells the host it is not ready while backgrounded. Media3 manages media audio focus and noisy-output handling. There is no background playback service.

## Decisions

- **Native Android / Compose:** direct access to permissions, Nearby, monotonic clocks and Media3, with one adaptive UI and no JavaScript bridge.
- **Nearby:** authenticated offline discovery and transfer without maintaining a custom radio stack. Trade-off: Play services and limited transport observability.
- **Transfer before playback:** network jitter does not continuously starve audio; storage, startup delay and file-size limits are the costs.
- **Host timeline:** one authority avoids conflicting controls. Losing the host requires reconnecting to that authority.
- **Monotonic clocks:** wall-clock adjustments cannot jump playback, but cross-device clock origins must be estimated.
- **Media3:** mature decoding, focus and seeking. Its player API does not provide sample-accurate synchronized speaker onset.
- **Future streaming:** would need bounded jitter buffers, continuous packet timestamps, codec framing, loss recovery, congestion control and an audio-clock-aware renderer. That is a separate design, not a mode hidden in this file-transfer MVP.

## Official references consulted

- [Nearby setup and permissions](https://developers.google.com/nearby/connections/android/get-started)
- [Advertise and discover](https://developers.google.com/nearby/connections/android/discover-devices)
- [Authentication and connection lifecycle](https://developers.google.com/nearby/connections/android/manage-connections)
- [FILE payload completion and URI handling](https://developers.google.com/nearby/connections/android/exchange-data)
- [Payload resource lifetime](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Payload)
- [Nearby stable release history](https://developers.google.com/android/guides/releases)
- [AGP 8.13.2 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Kotlin releases](https://kotlinlang.org/docs/releases.html)
- [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)
- [Nearby Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)

Pinned versions are in the build files. Nearby 19.3.0 is used with the AGP 8.13.2 toolchain; the initial attempt with Nearby 19.5.1 exposed Kotlin 2.4 metadata incompatibility in Kotlin 2.2. The project does not disable metadata validation. Release upgrades should repeat compile, lint, numerical, decoding and physical acceptance tests.
