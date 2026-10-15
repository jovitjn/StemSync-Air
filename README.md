# StemSync Air

**Your music. Every speaker. Together.**

A guitar part on a phone, drums on a tablet, and both devices following the same musical timeline without an internet music service in between.

I built StemSync Air around a question that connects my interests in music, wireless communication and DSP: can nearby Android devices act like one coordinated speaker system? The app combines local audio transfer, clock estimation and a host-controlled playback timeline. It is written in Kotlin with Jetpack Compose, Google Nearby Connections and AndroidX Media3.

**Current version: 0.2.1 — MP3 import added.**

**Status:** version 0.2.1 installable debug build; implementation and automated validation are documented in [VALIDATION.md](docs/VALIDATION.md). Physical Samsung phone/tablet acceptance and acoustic synchronization remain unverified. No sample-perfect or zero-latency claim is made.

**Development update — 16 October 2026:** I have completed the next multi-device implementation in a separate working copy. It removes the permanent host/guest control hierarchy, introduces an elected session coordinator, and uses per-device timing plus a prepare/commit barrier for synchronized song changes and seeking. A teammate is currently verifying that build. The verified source and replacement APK have not been published here yet, so the download below remains the tested two-device v0.2.1 build. The new design is documented in [MULTI-DEVICE.md](docs/MULTI-DEVICE.md).

## Download

[Download StemSync Air v0.2.1 for Android](distribution/StemSync-Air-v0.2.1-debug.apk)

This is a debug APK for Android 10 or newer. Its SHA-256 checksum is stored beside it in [`StemSync-Air-v0.2.1-debug.apk.sha256`](distribution/StemSync-Air-v0.2.1-debug.apk.sha256). Install the same version on both devices.

<img src="docs/screenshots/phone-home.png" width="320" alt="StemSync Air home on an Android phone emulator">

## What it does

- Creates an offline Nearby Connections room with one host and one guest.
- Requires both users to compare and confirm the connection code.
- Transfers real WAV files, verifies byte length and SHA-256, and prepares them locally with Media3.
- Plays the full mix on both devices, or a different aligned instrument on each.
- Gives the host play, pause, seek and stop controls on a shared future timeline.
- Estimates guest-to-host clock offset and RTT, checks logical playback drift, and applies conservative correction.
- Provides explicit reconnect, verified cache reuse, per-device volume and manual speaker-delay adjustment.
- Imports MP3 or WAV songs, or up to six aligned PCM16 WAV stems, using the system file picker.

There is no backend, login, recording feature or commercial music integration. Nearby handles local radio transports; this is not Bluetooth-only transfer or continuous audio streaming.

## How the features work

| Feature | What happens internally |
| --- | --- |
| Offline room | The host advertises a Nearby Connections `P2P_STAR` service and the guest discovers it. Nearby may choose Bluetooth, Bluetooth Low Energy, Wi-Fi or another local transport. The app does not send audio through a server. |
| Connection verification | Nearby produces the same short authentication code at both endpoints. The two users compare it and both must approve before the session accepts files or control messages. This reduces the risk of joining the wrong nearby device. |
| MP3 and WAV import | Android's system file picker gives the app a content URI, so files can be selected from normal local folders and compatible document providers. A single MP3 is decoded locally into PCM16 WAV because deterministic PCM frame counts are easier to validate and synchronize. PCM16 WAV can be used directly. |
| Stem import | Up to six WAV files can represent instruments from one song. The importer compares sample rate and exact PCM frame count, rather than rounded duration, because equal duration labels do not guarantee aligned sample timelines. The stems must already start at the same musical origin. |
| Verified transfer | The host sends a session manifest and associates per-file metadata with each audio payload. After receipt, the guest checks the declared byte length and SHA-256 digest, then validates the WAV structure against the manifest. Playback is enabled only after every required file passes these checks and the transfer completes. |
| Shared playback timeline | Play, pause, seek and stop are represented as revisioned timeline commands. For play or seek, the host sends a target song position and a future deadline on its monotonic clock. The delay gives both devices time to prepare before the audible transition. |
| Clock synchronization | The guest exchanges timestamp probes with the host. Four monotonic timestamps estimate round-trip time and the difference between the two device clocks. The estimator favors recent low-RTT samples because they are less likely to contain queueing delay. |
| Connection-quality gate | Playback synchronization requires at least five recent timing samples, RTT no greater than 150 ms, estimated uncertainty no greater than 75 ms and a latest sample no older than 2.5 seconds. An unstable connection therefore cannot be treated as synchronized merely because probes arrived. |
| Drift correction | Each device compares the expected shared timeline position with Media3's measured player position. Small persistent errors use bounded playback-speed correction; larger sustained errors trigger a seek. This is intentionally conservative to avoid audible speed changes and oscillation. |
| Per-device output | Each device can play the full mix or one aligned stem. Volume and a manual ±300 ms speaker-delay adjustment remain local, which lets the user compensate for a device's output pipeline or physical speaker placement. |
| Reconnect and cache | Received files are kept in app-private storage. Before reuse, every required file is rehashed and its WAV metadata is validated against the current manifest. Playback timing is measured again for the new connection. |

The clock calculation is similar to a compact NTP exchange. If the guest sends at `t1`, the host receives at `t2`, the host replies at `t3`, and the guest receives at `t4`:

```text
hostMinusGuest = ((t2 - t1) + (t3 - t4)) / 2
networkRTT     = (t4 - t1) - (t3 - t2)
```

All four values come from monotonic clocks, so a wall-clock correction or time-zone change cannot move the playback schedule. The method assumes roughly symmetric path delay. Android scheduling, decoding and speaker latency remain additional sources of error, which is why I describe the result as synchronized playback rather than sample-accurate audio.

## Install and listen

Minimum Android version is 10. Google Play services must be installed and working before going offline. Copy the downloaded APK to both devices, open it in their file managers and allow installation from that source if prompted. For USB installation:

```sh
adb -s PHONE_SERIAL install -r StemSync-Air-v0.2.1-debug.apk
adb -s TABLET_SERIAL install -r StemSync-Air-v0.2.1-debug.apk
adb -s PHONE_SERIAL shell am start -n air.stemsync/.MainActivity
```

1. Turn on Bluetooth and Wi-Fi on both devices. Internet access and mobile data are unnecessary. Older Android versions also need Location enabled for discovery.
2. On the phone, select **Host Session**. On the tablet, select **Join Session**, then the phone's room.
3. Compare the verification codes and choose **Codes match** on both devices.
4. Wait for file transfer, verification, audio preparation and clock synchronization. The original demo is loaded by the host; a fresh guest receives it through the real transfer path.
5. Press **Play together** on the host. Choose **Full mix** for group listening, or different instrument chips for distributed instruments. The host also controls pause and seeking.
6. Keep both apps visible. After a lost connection, restore the radios, tap **Reconnect**, reauthenticate and wait for fresh timing estimates.

For a host-only file test, use **Import song / stems** on the phone with an original file that is absent from the tablet. A single file is a full song. Multiple selected files are treated as stems; they must have identical PCM frame counts and sample rates and already be musically aligned. Supported input: one MP3 or PCM16 WAV song, or aligned PCM16 WAV stems. Standard 8–48 kHz mono/stereo rates are supported. MP3 is decoded locally once into WAV before verification and transfer; both the input and decoded file must fit 100 MiB. Select only one MP3 at a time.

**Help** contains permissions/settings links and a ±300 ms speaker-delay control. Positive delay makes that device later. **Connection details** shows real available observations and exports a diagnostic snapshot. An unavailable measurement stays “Not measured.”

## The original music

*Afterglow on the lake* is a 32-second, 120 BPM, A-minor instrumental built from deterministic synthesis. It has drums, bass, a plucked lead, keys, and a full mix. Every file has 1,411,200 samples at 44.1 kHz. The integer full mix exactly equals the aligned integer stems. No commercial recording or artwork is included.

```sh
python3 -m pip install numpy
python3 tools/generate_demo.py
python3 tools/verify_audio.py
```

The generator and generated assets are included under the project MIT license. The plucked lead can later be replaced by an original guitar recording exported at the same timeline and format.

## Build in Android Studio or from the terminal

Open this folder as the project. Install JDK 17 and Android SDK Platform 36 / Build Tools 35.0.0. Point Android Studio or `ANDROID_HOME` at that SDK. The Gradle wrapper is included, with its official distribution checksum pinned.

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest    # requires a running device/emulator
```

The built APK is `app/build/outputs/apk/debug/app-debug.apk`. First-time dependency resolution requires internet; using the installed app does not require an application server. Pinned stable dependencies are listed in the build files. The tested toolchain uses Gradle 8.13, AGP 8.13.2, Kotlin 2.3.20, Nearby 19.3.0 and Media3 1.9.4. Version-update lint warnings are retained rather than hidden.

## Architecture

`SessionViewModel` coordinates a StateFlow UI, Nearby transport, private audio library and Media3 player. The pure Kotlin `core` package contains protocol validation, connection consent, transfer-order gating, the clock estimator, timeline store and drift controller. Compose uses a scrollable phone layout and a two-column player at tablet widths.

The host schedules transitions one second ahead; guests translate the shared host timeline through their offset estimates. Every manifest and playback command carries a revision so an older asynchronous scan or delayed network message cannot overwrite newer song state. See [ENGINEERING.md](docs/ENGINEERING.md) for the protocol, sign conventions, permission decisions, transfer safety and lifecycle trade-offs.

<img src="docs/screenshots/tablet-player.png" width="800" alt="Real tablet emulator session after transferred files, host seek and guest drum selection">

## Validation and limitations

See [the validation record](docs/VALIDATION.md), [Samsung acceptance procedure](docs/HARDWARE-TEST.md), and [performance evaluation method](docs/PERFORMANCE.md). Automated synthetic timing tests do not demonstrate physical audio alignment. A real two-device test is still required.

- One host plus one guest; additional guests are explicitly declined.
- Foreground operation only. Backgrounding pauses output; no foreground playback service is claimed.
- MP3 songs are converted locally to PCM WAV; longer songs may exceed the 100 MiB decoded limit. Multiple-file stem imports must be aligned WAVs. The app does not separate instruments from a mixed song.
- Switching stems can produce a preparation gap. Scheduling is soft, with 20 ms polling; speaker onset is not sample-accurate.
- Transfer retry restarts from zero. A partition near a scheduled start can leave the host playing alone.
- Private files persist for reconnect/replay within the session; there is no full library browser or cache management screen yet. Clearing app storage removes them.
- Calibration is per device and currently resets when the app process restarts.
- Diagnostic export is a snapshot rather than a continuous raw timestamp trace.
- No physical discovery, throughput, reconnect or acoustic measurements have been fabricated.

## Next release: multi-device sessions

The current public release supports one host and one guest. The next implementation has been completed in a separate working copy and is now under teammate verification. It treats every connected device as an equal participant while electing one temporary coordinator to order commands and provide the reference clock.

Implemented in the release candidate:

- Multiple authenticated participants with independent transfer, readiness, acknowledgement and reconnect state.
- Equal playback-control requests from any participant, serialized by an elected session coordinator.
- Independent RTT, clock-offset and uncertainty estimates relative to the coordinator.
- Revisioned prepare/commit commands for play, pause, seeking and song changes.
- A readiness barrier that seeks and prepares every device before selecting a shared future start deadline.
- Term and revision checks that reject commands from an old coordinator or an older playback action.
- Coordinator re-election and fresh timing measurements when the current coordinator leaves.
- Isolation of slow or disconnected participants so they cannot corrupt another device's session state.

Nearby may use Bluetooth, Bluetooth Low Energy, Wi-Fi or another local transport selected by Google Play services. The app will continue to require Bluetooth and Wi-Fi to be enabled, but it will not claim that every transfer uses Bluetooth when the SDK does not expose that choice.

Publication remains gated on teammate verification, source review, multi-device hardware testing and a replacement APK. Until those artifacts are committed, the shipped and validated scope in this repository remains one host plus one guest.

## License

Project code, original synthesis and demo assets: [MIT](LICENSE). AndroidX libraries retain their respective licenses; Google Play services is subject to Google's SDK terms and is not made open source by this repository. See [third-party notes](docs/THIRD-PARTY.md).
