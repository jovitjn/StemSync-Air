# Performance evaluation protocol

No physical wireless or acoustic performance numbers are supplied. The automated tests use synthetic timestamps and file inputs; they are algorithmic evidence, not radio benchmarks. Actual Nearby file transfer and selected control paths were also exercised between virtual devices, without establishing physical radio or acoustic performance. See VALIDATION.md for executed build/test results.

| Quantity | Observable | Procedure / interpretation |
|---|---|---|
| Connection setup | User actions and successful Nearby callback | Measure from Join selection to connected on guest; separately record code-comparison time. Repeat 10 cold starts. |
| Audio throughput | FILE callback bytes and monotonic elapsed time | The app reports completed bytes / (success time − first tracked event), in MiB/s. This is application-level transfer throughput including setup, excluding checksum verification, not a PHY rate. |
| RTT distribution | Four-timestamp messages on guest | Collect guest diagnostics periodically for at least 60 s after setup; mean and population SD cover the rolling 24 samples. Export currently captures a snapshot, not a raw packet trace. |
| Clock offset | Robust estimate, host minus guest | Repeat under idle and transfer load. Offset is biased by asymmetric delays; it is not the difference of wall clocks. |
| Logical error | Media3 currentPosition minus calibrated shared target | Sample while playing, note correction speed/seeks and decoder interruptions. This excludes physical speaker latency. |
| Recovery | Disconnect/reconnect callbacks and recovered song position | Interrupt at a known host position. Count reconnects, record authentication time, cache verification, clock acquisition and resulting position. |
| Acoustic alignment | Not directly observable by this app | Use the external procedure in HARDWARE-TEST.md. Report geometry, audio route, repeated trials and uncertainty. |

## Reproducible algorithmic cases

`TimingTest` injects +37 ms, −83 ms and zero clock offsets; symmetric and asymmetric delays; host processing delay; high-RTT outliers; invalid timestamps; rolling-window updates and bounded active offset slew. `TimelineTest` verifies future start/pause behavior, seeking, a 25-second reconnection, track changes, stale revisions and end clamping. `DriftTest` establishes the error sign and confirms persistence prevents one-sample seek reactions.

A symmetric 10+10 ms path with host offset +37 ms should yield RTT 20 ms and theta +37 ms, independently of a 2 ms or 400 ms host processing interval. A 30+10 ms path yields theta +47 ms; the 10 ms bias is expected. These are specified synthetic inputs, not measured hardware performance.

No naive-start comparison graph is included: that experiment is optional and would not validate physical improvement. Priorities here are build verification, meaningful algorithm tests, actual Media3 decoding where executable, and a precise two-device procedure.

## Limitations and follow-up

The initial thresholds (25 ms deadband, three persistent samples, 250 ms hard correction, ±0.5% speed adjustment) are engineering defaults, not values optimized from Samsung measurements. The 20 ms scheduling poll has no hard deadline guarantee. Main-thread load and power management can increase its error. Characterize actual devices before changing these numbers.

A useful follow-up is a bounded timestamp trace export, percentile statistics, paired acoustic trials, and a comparison with an immediate-start control using the same content and network conditions. Do not report those future measurements as completed work.
