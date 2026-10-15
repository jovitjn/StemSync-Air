# Validation record

This is a build-verified Android MVP, with functional tests on Android virtual devices. The Samsung phone + tablet offline acceptance test and physical speaker alignment have **not** been executed. Emulator results do not establish physical wireless reliability, acoustic alignment, or battery behavior.

## Environment and reproducibility

Validation date: 2026-10-15. Current build: 0.2.0 (version code 2). macOS host, isolated Amazon Corretto JDK 17, Gradle 8.13, Android SDK 36 / Build Tools 35.0.0, AGP 8.13.2, Kotlin 2.3.20. App minimum API 29, target API 36. The two Google APIs Android 15 / API 35 arm64 emulators use a Pixel 7 phone profile (1080×2400) and Pixel Tablet landscape profile (2560×1600). They are not Samsung hardware. Emulator audio output was disabled; decoder and player-state validation is not a listening test.

From the project directory with JDK 17 and the SDK configured:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest
python3 tools/verify_audio.py
```

A running device is required for the connected tests. The wrapper pins the official Gradle distribution checksum. Dependencies must be resolved before an offline build. The delivery APK is a debug-signed build, for installation and evaluation, not Play Store publication.

## Automated checks

Version 0.2.0 final combined build, unit, lint and connected regression: **BUILD SUCCESSFUL**. All six instrumentation tests passed on each emulator (12 executions), with zero failures and zero skips. See [curated reports](test-results/README.md).

The regression caught two test timing issues: holding a transient Pause node through a slow scroll, and requesting Play before a seek had finished preparing. The test now re-queries a stable paused control and waits for decoder readiness, while retaining playback/position assertions. The app also explicitly displays the authoritative duration at end of song; restarting then schedules position zero.

- 84 pure Kotlin tests: clock estimator/quality 25; timeline 13; drift controller 5; transfer/protocol/WAV validation 19; session consent/readiness 10; manifest scopes/current-file cache 8; exact PCM alignment/content validation 4. All passed with no skips.
- Android instrumentation covers home actions, help/settings, visible connection errors, real Media3 decoding of all five 32-second WAV files, host playback/pause/seek/stem selection/stop/end/restart, and Activity recreation versus genuine backgrounding.
- Audio verification checks all five hashes, equal sample rates/channels/durations, exact integer full-mix equality to the aligned stems, and no clipping. Passed.
- Lint: zero errors; 12 warnings. Eleven report available dependency/tool versions. One asks for legacy backup configuration despite the explicit `fullBackupContent="false"` and `allowBackup="false"`; Android 12+ extraction rules also exclude application data. Warnings are retained transparently.

The clock and timeline tests use synthetic inputs with independently specified expected results; they are not measured radio data. The Android playback test exercises the real player locally, while its direct ViewModel host setup does not prove permission granting or Nearby success. Those are separate manual checks.

## Five correctness fixes in version 0.2

- Immutable manifest snapshots invalidate delayed cache and transfer results after a newer manifest or session. Deterministic unit cases model out-of-order completion against the same production scope guard; they are not actual concurrent disk-IO integration tests.
- Clock readiness rejects delayed, stale or uncertain recent samples, including lag between applied and estimated offset. Tests cover uniform delay, sudden congestion, clean recovery and expired historical low-RTT samples.
- Configuration recreation preserves foreground playback and retained state; genuine backgrounding still pauses. Both paths are checked with ActivityScenario.
- Startup does no whole-library hashing. Cache tests verify only requested files and reject same-length corruption by SHA-256; integrity is not inferred from the index.
- Imported stems compare exact PCM frame counts and sample rates. A one-frame mismatch with identical millisecond durations is rejected. Broad picker MIME visibility is paired with actual content validation.

## Version 0.2.0 transfer smoke test

The rebuilt APK was installed on both emulators with app data cleared. Local discovery and matching-code authentication succeeded. All five demo files arrived through Nearby FILE payloads; independent receiver SHA-256 checks matched all five manifest hashes. The guest reached “Ready · following the host” under the new timing-quality gates. This is a virtual-device functional check, not physical or internet-isolated acceptance. The APK signature also verified successfully.

## Earlier functional emulator observations (version 0.1)

The actual Nearby SDK was used, with no replacement transport. On a fresh guest library, all five demo WAV files were sent by Nearby FILE payloads, copied and verified privately, and their independently read receiver SHA-256 values matched the manifest. The demo bundled in the guest APK is not installed into its playable cache on Join.

The version 0.1 APK check demonstrated discovery, matching authentication codes, real transfer, successful checksum verification and guest preparation. The host seek to 0:16 reached the same displayed position on the guest. Selecting Drums on the guest set its checked accessibility state and preserved 0:16; this corrects the earlier helper's insufficient selection assertion. Earlier emulator checks also demonstrated scheduled Play and Pause.

Manual virtual-device reconnect/cache recovery, system file-picker import and diagnostics export remain pending. They are implemented and covered in part by component tests, but are not reported as end-to-end passes. Internet was not isolated in this virtual environment, so these observations are **not** an offline Samsung acceptance result.

No physical throughput, RTT distribution, setup-latency benchmark, acoustic accuracy number, or general performance claim is derived from a single emulator run. See [PERFORMANCE.md](PERFORMANCE.md) for repeatable measurement procedures and [HARDWARE-TEST.md](HARDWARE-TEST.md) for the required physical acceptance steps.

## Status by feature

| Feature | Implemented | Compiled | Automated checks | Physical Android tested | Limits |
|---|---|---|---|---|---|
| Nearby discovery and explicit consent | Yes | Yes | Consent policy; emulator manual path | No | One host + one guest; Play services required |
| Real FILE transfer, SHA-256, cache, retry | Yes | Yes | Transfer ordering/validation; emulator manual path | No | WAV only; retry starts over; radio interruption pending |
| Media3 full mix and stems | Yes | Yes | Five assets decode; playback and selection | No | Decoder switch can produce a gap; no listening test |
| Host Play, Pause, Seek, Stop and end/restart | Yes | Yes | Timeline and actual player tests | No | Soft scheduling; no sample-accuracy guarantee |
| Four-timestamp clock estimation | Yes | Yes | Offset sign, asymmetry, recent quality, degradation/recovery | No | Link asymmetry biases offset |
| Drift correction and speaker delay | Yes | Yes | Error sign, persistence, bounded speed and seek policy | No | Logical player error excludes speaker latency |
| Reconnect and cache reuse | Yes | Yes | Reconnection timeline; manual virtual-device recovery check pending | No | Explicit reconnect + code confirmation; no partial transfer resume |
| Diagnostics and CSV export | Yes | Yes | Underlying estimator/statistics tests | No | Snapshot only; export picker requires manual acceptance |
| File picker imports | Yes | Yes | WAV parser, exact frame alignment/content validation | No | System picker end-to-end pending; aligned PCM16 only |
| Phone/tablet UI and failure recovery | Yes | Yes | Compose tests, recreation/background test and emulator screenshots | No | Rotation, text scaling and older Android versions need hardware matrix |
| Original music and reproducible build | Yes | Yes | Hash/alignment/no-clipping checks; APK produced | No | Debug signing; original synthesized lead |

## Remaining physical acceptance

Run the complete Samsung procedure with Wi-Fi/Bluetooth enabled and mobile data/internet disconnected. Include host-only imported audio, rejected consent, interrupted transfer and retry, radio loss during playback, reauthentication/rejoin, foreground/background and audio-focus behavior, speaker-delay calibration and repeated acoustic trials. Keep failed trials. No item in that procedure is implicitly passed by this document.
