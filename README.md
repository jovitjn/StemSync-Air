# StemSync Air

**Your music. Every speaker. Together.**

I built StemSync Air to turn a group of nearby Android phones and tablets into one coordinated music system. Devices connect locally, exchange verified audio files and follow a shared playback timeline without an application server or music-streaming account.

The project combines my interests in music, wireless communication and digital signal processing. It is written in Kotlin with Jetpack Compose, Google Nearby Connections and AndroidX Media3.

<img src="docs/screenshots/phone-home.png" width="320" alt="StemSync Air home screen on an Android phone">

## Previous Android build

[Download StemSync Air v0.2.1](distribution/StemSync-Air-v0.2.1-debug.apk)

This earlier Android 10+ debug build and its [SHA-256 checksum](distribution/StemSync-Air-v0.2.1-debug.apk.sha256) are retained with the project history.

## Main features

- Connects multiple nearby Android devices in one offline session.
- Gives every participant equal access to play, pause, seek and change songs.
- Elects a temporary session coordinator to order commands and provide the reference clock.
- Re-elects the coordinator and rebuilds timing state when that device leaves.
- Measures an independent clock offset, round-trip time and uncertainty for every participant.
- Uses a prepare/commit barrier so all devices seek and prepare before a shared start deadline.
- Transfers MP3 or PCM16 WAV music and aligned WAV stems through Nearby Connections.
- Verifies received files with byte length, SHA-256 and WAV structure checks.
- Lets each device play the full mix or a different aligned instrument stem.
- Corrects persistent playback drift conservatively and supports manual speaker-delay calibration.

There is no backend, account system, recording feature or commercial music integration. Music is transferred first and played locally, so short variations in wireless throughput do not continuously interrupt playback.

## How it works

### Equal participants and coordinator election

All connected devices have the same application-level permissions. Any participant can request a playback action. One device is elected as the temporary coordinator because a distributed music system still needs one ordered command stream and one clock reference at any instant.

The coordinator receives requests, assigns increasing playback revisions and broadcasts the committed result. Two nearly simultaneous seeks therefore become consecutive revisions instead of conflicting timelines.

Playback messages carry a session ID, coordinator term and playback revision. A device rejects a message from an older term or a revision it has already applied. This makes duplicate and delayed messages harmless.

If the coordinator disconnects, playback pauses, the remaining devices elect a replacement and discard timing estimates tied to the previous coordinator. Fresh offsets are measured before the session resumes.

### Per-device clock synchronization

Every participant estimates its clock relative to the coordinator. Pairwise measurements between every possible device are unnecessary: all devices align to the same reference using O(N) timing relationships instead of O(N²).

For participant send/receive timestamps `t1` and `t4`, and coordinator receive/send timestamps `t2` and `t3`:

```text
coordinatorMinusParticipant = ((t2 - t1) + (t3 - t4)) / 2
networkRTT                  = (t4 - t1) - (t3 - t2)
```

All timestamps use Android's monotonic clock. Wall-clock changes and time-zone changes therefore cannot move a scheduled playback deadline.

If the coordinator chooses future deadline `T`, participant `i` converts it into its local clock:

```text
localDeadline[i] = T - coordinatorMinusParticipant[i]
```

The estimator prefers recent low-RTT samples because they contain less queueing delay. Readiness also considers sample age and timing uncertainty, so receiving several poor samples is not enough to declare a device synchronized.

### Synchronized play, seek and song changes

Every playback change uses two phases:

1. A participant requests play, pause, seek or a new song.
2. The coordinator creates a new revision and broadcasts `PREPARE` with the song and target position.
3. Each device verifies the content, pauses, loads the correct file, seeks and waits until its player and timing estimate are ready.
4. Devices return `PREPARED` with timing-quality information.
5. The coordinator selects a future deadline using preparation time, half-RTT, clock uncertainty and a scheduler safety margin.
6. It broadcasts `COMMIT` with the target position and effective coordinator time.
7. Every prepared device translates the deadline into its local clock and starts from the requested position.

A device that receives the commit after the deadline stays paused and resynchronizes instead of beginning late without warning.

### Drift correction

During playback, each device calculates its expected position from the committed timeline:

```text
expectedPosition = targetPosition
                 + (estimatedCoordinatorNow - effectiveTime)

error = actualPlayerPosition - expectedPosition
```

Small persistent errors use bounded playback-speed adjustment. Large sustained errors trigger a seek. A deadband prevents correction from reacting continuously to normal player-position noise.

### Verified audio distribution

Music is described by a manifest containing content identity, format, byte length, sample rate, duration and SHA-256. A received file becomes playable only after:

1. Its metadata is associated with the expected transfer.
2. The complete payload arrives successfully.
3. Its length and SHA-256 match the manifest.
4. Its RIFF/WAV structure and audio metadata pass validation.

Files are stored by their content digest rather than a sender-controlled filename. Cached files are rehashed before reuse.

### MP3, WAV and stems

A single MP3 is decoded locally into PCM16 WAV before distribution. Deterministic PCM frame counts make content validation and synchronized seeking simpler. A single WAV can be used directly.

The conversion pipeline is:

1. The selected content URI is copied into a bounded temporary input file. A `RIFF` signature identifies WAV input; other single-file input enters the MP3 decoder.
2. `MediaExtractor` finds the `audio/mpeg` track and supplies compressed MP3 access units with their presentation timestamps.
3. Android's `MediaCodec` decoder converts those access units into PCM output buffers. The requested output encoding is signed 16-bit PCM.
4. The first output-format event determines the real sample rate and channel count. The converter accepts mono or stereo at supported standard rates from 8 to 48 kHz and rejects mid-stream format changes.
5. PCM buffers are appended sequentially after a reserved 44-byte header. Codec-configuration buffers are excluded, decoded size is bounded and partial output is deleted after failure.
6. The converter calculates the PCM frame count and writes a little-endian RIFF/WAVE header from the actual decoded data:

```text
bytesPerSample = 2
blockAlign     = channels × bytesPerSample
frameCount     = pcmDataBytes / blockAlign
byteRate       = sampleRate × blockAlign
duration       = frameCount / sampleRate
```

7. The completed file passes the same WAV parser used for received content. SHA-256 is then calculated over the entire decoded WAV, so every participant receives and verifies the same PCM representation.

The conversion is bounded to 100 MiB for both input and decoded output and has a three-minute wall-time limit. Decoder resources and temporary files are released on both success and failure.

Up to six WAV files can represent aligned stems from one song. Stem files must have the same sample rate and exact PCM frame count. Equal rounded durations are insufficient because files that differ by one frame would no longer share the same sample timeline.

Each participant can independently select the full mix or one instrument while preserving the same musical position.

## Local wireless transport

Nearby Connections provides encrypted, fully offline discovery, connection and payload exchange. It may use Bluetooth, Bluetooth Low Energy, Wi-Fi or another available local transport selected by Google Play services. The application requires the relevant Bluetooth and nearby-device permissions but does not claim that every payload travels over Bluetooth alone because Nearby does not expose that choice.

## Architecture

```text
Compose UI
    │
SessionViewModel
    ├── participant and coordinator state
    ├── command ordering and prepare/commit barrier
    ├── per-device clock estimators
    ├── transfer and readiness state
    └── shared playback timeline
            │
    ┌───────┴────────┐
Nearby Connections  Media3 player
    │                 │
verified files       local audio output
```

The pure Kotlin core contains protocol validation, coordinator terms, playback revisions, timing estimation, timeline evaluation and drift policy. Endpoint IDs identify participants independently of display names. The Android layer handles permissions, Nearby callbacks, private file storage and Media3 playback.

See [the detailed engineering design](docs/ENGINEERING.md) and [the multi-device protocol](docs/MULTI-DEVICE.md).

<img src="docs/screenshots/tablet-player.png" width="800" alt="StemSync Air player on an Android tablet">

## Original demo music

*Afterglow on the lake* is a 32-second, 120 BPM, A-minor instrumental generated specifically for this project. It contains drums, bass, plucked lead, keys and a full mix. Every stem contains 1,411,200 samples at 44.1 kHz, and the integer full mix is derived from the aligned stems. No commercial music or artwork is included.

```sh
python3 -m pip install numpy
python3 tools/generate_demo.py
python3 tools/verify_audio.py
```

## Build from source

The project uses JDK 17, Android SDK Platform 36 and Build Tools 35.0.0.

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

## Engineering limits

- Android application scheduling and Media3 do not provide hard real-time speaker onset.
- Decoder buffering, DAC latency, speaker processing and acoustic propagation vary between devices.
- Manual per-device delay remains useful for compensating for stable output-path differences.
- MP3 is converted to PCM before transfer, so long songs require more private storage.
- Multi-file stem imports require already aligned PCM16 WAV files; the app does not separate instruments from a mixed song.
- The app coordinates local playback rather than streaming live audio between speakers.

## License

Project code, synthesis tools and original demo assets are provided under the [MIT License](LICENSE). AndroidX libraries retain their own licenses, and Google Play services remains subject to Google's SDK terms. See [third-party notes](docs/THIRD-PARTY.md).
