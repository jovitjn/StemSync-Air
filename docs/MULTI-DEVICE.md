# Multi-device synchronization

## Development status

The equal-participant multi-device implementation was completed in a separate working copy on 16 October 2026 and is undergoing teammate verification. This document records the implemented design. The public source and APK in this repository remain version 0.2.1 until that review is complete and the new files are committed.

## Equal participants with one temporary coordinator

Every connected device has the same application-level permissions. Any participant can request play, pause, seek or a song change. The group still elects one temporary coordinator because a synchronized timeline needs one ordered sequence of commands and one clock reference at any instant.

The coordinator is a protocol role rather than a privileged user. It receives action requests, assigns monotonically increasing revisions and broadcasts the committed result. If two participants seek at nearly the same time, their requests become consecutive revisions instead of competing timelines.

Every message that changes playback carries:

```text
session ID
coordinator term
playback revision
requesting device ID
song ID
target position
effective coordinator time
```

A participant rejects a message from an older coordinator term or a playback revision it has already applied. This makes duplicates harmless and prevents delayed commands from overwriting newer state.

## Per-device clock estimation

Each participant estimates its clock relative to the current coordinator. Pairwise measurements between every possible device are unnecessary: all devices can align transitively to the same reference in O(N) measurement relationships rather than O(N²).

For participant send/receive timestamps `t1` and `t4`, and coordinator receive/send timestamps `t2` and `t3`:

```text
coordinatorMinusParticipant = ((t2 - t1) + (t3 - t4)) / 2
networkRTT                  = (t4 - t1) - (t3 - t2)
```

If the coordinator chooses future deadline `T`, participant `i` schedules against its local monotonic clock at:

```text
localDeadline[i] = T - coordinatorMinusParticipant[i]
```

The estimator prefers recent low-RTT samples and records timing uncertainty. A wall-clock or time-zone change cannot disturb the schedule because the protocol uses monotonic clocks.

## Preparing a seek or song change

A playback change uses two phases.

1. Any participant submits an action request to the coordinator.
2. The coordinator creates a new term/revision pair and broadcasts `PREPARE` with the song and target position.
3. Every participant verifies the requested content, pauses, loads the correct file, seeks to the target and waits until its player and clock estimator are ready.
4. Each participant returns `PREPARED` with its revision and current timing quality.
5. The coordinator chooses a future deadline from the slowest accepted participant's preparation time, half-RTT, clock uncertainty and a scheduler safety margin.
6. The coordinator broadcasts `COMMIT` with the target position and effective coordinator time.
7. Every prepared participant translates that deadline into its own clock and starts from the requested position.

A participant that receives the commit too late does not begin audibly behind the group. It remains paused, reports that it missed the deadline and joins at a later synchronization point.

## Coordinator changes

Participants monitor coordinator heartbeats. If the coordinator leaves or becomes unreachable, playback pauses and the remaining devices start a new election term. The winner is chosen deterministically from election term, priority and stable device ID.

Clock estimates from the previous coordinator are discarded. The group measures fresh offsets, verifies the last committed song and position, and uses the same prepare/commit barrier to resume. Commands from the previous term remain invalid even if they arrive after reconnection.

## Audio and transport limits

Nearby Connections provides the offline topology and may use Bluetooth, Bluetooth Low Energy, Wi-Fi or another local transport selected by Google Play services. The application cannot force or report a Bluetooth-only data path through that API.

Network-clock alignment does not remove decoder, Android scheduler, DAC, speaker-processing or acoustic propagation delay. Logical timeline tests therefore need to be followed by physical recordings and cross-correlation measurements across several real devices. Per-device speaker-delay calibration remains necessary for tighter audible alignment.

## Verification gate

The release should not replace v0.2.1 until review covers:

- Three or more physical Android devices in one session.
- Simultaneous control requests and deterministic ordering.
- Repeated seeks and song changes during playback.
- Slow preparation, missed deadlines and participant removal.
- Coordinator loss, election and session recovery.
- Duplicate, delayed and reordered protocol messages.
- Interrupted file transfers and verified cache reuse.
- Acoustic onset measurements rather than screen-position comparison alone.

The replacement APK will be published after this verification is complete.
