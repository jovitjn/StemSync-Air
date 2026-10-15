# StemSync Air engineering design

## System boundary

StemSync Air is an Android application above the wireless link layer. Google Nearby Connections provides offline discovery, authenticated connections and payload exchange. Google Play services chooses the underlying local transport, which may include Bluetooth, Bluetooth Low Energy or Wi-Fi. The application does not implement a Bluetooth audio profile and does not stream commercial music.

Audio files are distributed before playback. Each device then renders its local file against a shared monotonic timeline. This removes continuous network jitter from the audio path and makes the wireless channel responsible for commands, timing probes and bounded file transfers.

## Distributed session model

Every connected device is an equal participant. Any participant may request play, pause, seek or a song change. One temporary coordinator supplies the authoritative clock and orders concurrent requests.

Coordinator authority is scoped by a monotonically increasing term. Playback state is scoped by a monotonically increasing revision. Devices compare `(term, revision)` before accepting a command, so duplicates and delayed messages cannot overwrite newer state.

The network uses a star-shaped Nearby topology for group connectivity. The physical hub is temporary. If it leaves, participants elect a replacement, reconnect around it and rebuild their timing estimates.

## Coordinator election

Participants exchange heartbeats containing the coordinator term and latest committed playback revision. Missing heartbeats trigger an election. A deterministic tuple of election term, priority and stable device ID selects one winner.

Playback pauses during the transition. The elected coordinator announces a new term, gathers the latest committed state and starts fresh timing probes. Commands from earlier terms are invalid even if delivered after reconnection.

## Clock estimation

All scheduling timestamps use `SystemClock.elapsedRealtimeNanos()`. For participant send/receive times `t1` and `t4`, and coordinator receive/send times `t2` and `t3`:

```text
theta = ((t2 - t1) + (t3 - t4)) / 2
rtt   = (t4 - t1) - (t3 - t2)
coordinatorNow = participantNow + theta
localDeadline = coordinatorDeadline - theta
```

RTT excludes measured coordinator response time but still includes transport queues and callback scheduling. Asymmetric forward and reverse paths bias the offset by half their delay difference.

Each participant maintains its own sample window. Recent low-RTT samples determine the applied offset. Readiness considers sample count, sample age, RTT and uncertainty. Offset changes during playback are slewed to prevent an abrupt jump in the logical timeline.

## Shared playback transaction

A playback request is a two-phase distributed transaction.

During `PREPARE`, participants validate the requested song, prepare their selected local file, seek to the target position and report player and timing readiness. The coordinator calculates the start delay from the maximum preparation and timing budget among accepted participants.

During `COMMIT`, the coordinator sends the target position and a future effective coordinator timestamp. Participants translate that timestamp into their local monotonic clocks. A commit received after its deadline is rejected rather than played late.

This same mechanism handles initial play, pause, forward seek, backward seek and song replacement. A newer revision supersedes an uncommitted older preparation.

## Timeline and drift

A committed timeline contains session ID, coordinator term, playback revision, song ID, playing state, base position, effective coordinator time, duration and playback rate.

For active playback:

```text
positionMs = clamp(
    basePositionMs + max(0, coordinatorNow - effectiveTimeNs) / 1e6,
    0,
    durationMs
)
```

Every device periodically compares Media3's reported position with the expected timeline. A deadband rejects noise. Persistent moderate errors receive bounded speed correction; sustained large errors seek to the expected position. New revisions reset drift history.

The calculated error describes Android's player timeline. It is not a direct acoustic measurement because speaker buffers, DAC latency, vendor processing and sound propagation remain unobserved.

## Participant state isolation

Authentication, transfer progress, file readiness, clock quality and playback acknowledgements are keyed by endpoint ID and connection generation. A stale callback from an earlier connection cannot mutate the current participant.

A slow participant does not block transfer state for the rest of the group. The coordinator can wait, continue with ready devices or remove an unresponsive participant according to the selected session policy.

## Files and integrity

Control messages include session ID, sender ID, message ID, sequence, coordinator term, playback revision and monotonic send timestamp. Duplicate message IDs are suppressed within a bounded window.

The audio manifest contains song/content identity, stem name, display name, safe filename, byte length, PCM frame count, duration, sample rate and SHA-256. Sender-controlled names never choose the private destination path; stored files are named by validated digest.

A received file becomes usable only after metadata arrival, complete payload transfer, exact-length verification, SHA-256 verification and WAV validation. The parser checks RIFF structure, PCM16 format, channel count, sample rate and exact frame count. Only then does the receiver acknowledge content readiness.

Cached files are rehashed against the active manifest before reuse. Manifest revisions and immutable snapshots prevent a delayed verification result from marking a newer song ready.

## Audio import

The Storage Access Framework accepts content URIs from Android's system picker. A single MP3 is identified using `MediaExtractor` and decoded with `MediaCodec` into bounded PCM16 WAV. Partial output is deleted after failure.

Single PCM16 WAV files can be imported directly. Multi-file stem import requires identical sample rates and exact PCM frame counts. Matching displayed duration alone is insufficient and cannot prove that the stems are musically aligned.

## Lifecycle and permissions

The application requests the Bluetooth, nearby Wi-Fi and legacy location permissions required by the device's Android version and Nearby Connections. It does not silently enable radios or derive user location.

Audio import uses document URIs rather than broad modern media-library access. Received files are copied into app-private storage. Foreground playback is the supported lifecycle, and Media3 manages media audio focus and noisy-output changes.

## Design decisions

- **Equal participants with an elected coordinator:** any device can control playback while commands still have a single deterministic order.
- **Monotonic clocks:** wall-clock adjustments cannot move scheduled audio events.
- **Prepare/commit barrier:** devices load and seek before a shared deadline is chosen.
- **Per-device timing models:** every participant receives a deadline translated through its own offset and uncertainty.
- **Transfer before playback:** network jitter does not continuously starve the audio renderer.
- **Content-addressed files:** integrity and cache identity use SHA-256 rather than filenames.
- **Media3:** provides stable decoding, seeking and audio focus, while its output remains soft real-time rather than sample-accurate.
- **Nearby Connections:** provides authenticated offline group transport while abstracting the selected radio technology.

## References

- [Nearby Connections overview](https://developers.google.com/nearby/connections/overview)
- [Nearby connection strategies](https://developers.google.com/nearby/connections/strategies)
- [Android advertising and discovery](https://developers.google.com/nearby/connections/android/discover-devices)
- [Connection authentication](https://developers.google.com/nearby/connections/android/manage-connections)
- [Payload exchange](https://developers.google.com/nearby/connections/android/exchange-data)
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [Android supported media formats](https://developer.android.com/media/platform/supported-formats)
