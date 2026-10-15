# Multi-device synchronization protocol

## Session model

StemSync Air treats every connected device as an equal participant. Any participant can request playback changes, import music or become the session coordinator.

The coordinator is a temporary protocol role. It provides:

- One monotonic clock reference.
- One ordered stream of playback revisions.
- Serialization of simultaneous participant requests.
- Collection of preparation and timing acknowledgements.
- Selection of shared future deadlines.

This avoids conflicting timelines without creating a permanent privileged host.

## Participant state

State is stored independently for every endpoint:

```text
participant ID
authentication state
connection generation
coordinator term
manifest revision
transfer state
audio preparation state
clock offset
round-trip time
timing uncertainty
latest acknowledged playback revision
```

Disconnecting or slowing one participant cannot overwrite another participant's transfer, readiness or acknowledgement state.

## Coordinator terms and command revisions

The group assigns each coordinator a monotonically increasing term. Playback actions receive monotonically increasing revisions within that term.

The ordering key is:

```text
(coordinatorTerm, playbackRevision)
```

A participant accepts only a key newer than the last accepted key. Duplicate messages are acknowledged without replaying the action. Messages from an older coordinator term are rejected.

Any participant may send:

```text
ACTION_REQUEST(
    requesterId,
    action,
    songId,
    targetPositionMs
)
```

The coordinator validates and serializes requests before issuing a new revision.

## Clock model

Every participant measures its clock relative to the coordinator. For participant send and receive times `t1` and `t4`, and coordinator receive and reply times `t2` and `t3`:

```text
theta = ((t2 - t1) + (t3 - t4)) / 2
rtt   = (t4 - t1) - (t3 - t2)
```

`theta` is coordinator time minus participant time:

```text
coordinatorNow = participantNow + theta
participantDeadline = coordinatorDeadline - theta
```

The estimator selects recent low-RTT samples and tracks uncertainty from half-RTT, offset disagreement and sample age. All calculations use monotonic nanoseconds.

## Prepare/commit barrier

Play, seek and song-change operations follow the same state machine:

```text
REQUESTED
    ↓
PREPARING
    ↓
PREPARED
    ↓
COMMITTED
    ↓
PLAYING or PAUSED
```

The coordinator broadcasts:

```text
PREPARE(
    coordinatorTerm,
    playbackRevision,
    songId,
    targetPositionMs,
    desiredState
)
```

Each participant verifies the manifest and files, prepares the selected local mix or stem, seeks to the target and refreshes its clock estimate. It then responds:

```text
PREPARED(
    coordinatorTerm,
    playbackRevision,
    measuredPreparationMs,
    rttNs,
    uncertaintyNs
)
```

The coordinator calculates a deadline from the slowest accepted readiness budget:

```text
budget[i] = preparation[i]
          + rtt[i] / 2
          + uncertainty[i]
          + schedulerMargin

startDelay = max(minimumDelay, max(budget[i]))
deadline = coordinatorNow + startDelay
```

It then broadcasts:

```text
COMMIT(
    coordinatorTerm,
    playbackRevision,
    targetPositionMs,
    effectiveCoordinatorTimeNs
)
```

Every participant translates the deadline into its own clock. A late participant does not start behind the group; it reports a missed deadline and waits for a new synchronization point.

## Coordinator election and recovery

Participants monitor coordinator heartbeats. When the coordinator disappears, the remaining group pauses playback and begins a new election term.

The winner is selected deterministically from:

```text
(electionTerm, electionPriority, stableDeviceId)
```

The new coordinator announces its term and the latest committed playback key. Participants discard clock estimates tied to the previous coordinator, exchange fresh timing probes and prepare the last committed song position. Playback resumes through a new prepare/commit barrier.

This prevents split-brain playback: a device never continues executing commands from two coordinator terms.

## Content distribution

Audio uses content-addressed storage. A manifest describes every required file using:

```text
song ID
content ID
stem name
safe display name
byte length
sample rate
PCM frame count
duration
SHA-256
```

Files are shared with participants that do not already have verified matching content. Transfer state is scoped to both participant and manifest revision. Completion for one participant cannot mark another participant ready.

The receiver validates payload completion, length, SHA-256, RIFF structure, PCM format, sample rate and frame count before acknowledging readiness.

## Drift control

After the shared deadline, expected playback position is:

```text
expectedMs = basePositionMs
           + max(0, estimatedCoordinatorNow - effectiveTimeNs) / 1e6
```

The error signal is:

```text
errorMs = playerCurrentPositionMs - expectedMs
```

A deadband ignores small measurement noise. Repeated moderate errors use bounded speed correction, while sustained large errors seek to the shared target. A new playback revision resets accumulated correction state.

## Transport boundary

Nearby Connections supplies offline encrypted discovery and transport. Its star topology supports one physical hub with several connected spokes. Application-level coordinator and control permissions remain independent of that temporary transport role.

When the physical hub leaves, participants re-form the local topology around the newly elected coordinator. Nearby may use Bluetooth, BLE, Wi-Fi or another local transport; the application does not force a Bluetooth-only data channel.

## Acoustic boundary

Clock alignment schedules application-level player actions. It cannot directly observe decoder buffers, audio-mixer latency, DAC latency, vendor speaker processing or sound propagation through the room.

Manual calibration applies a stable per-device delay. Physical evaluation can estimate acoustic onset error by recording all speakers together and cross-correlating a known transient from each channel.
