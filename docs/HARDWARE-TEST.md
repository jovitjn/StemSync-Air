# Samsung phone + tablet acceptance test

Status: **not executed on physical devices in the development environment**. Emulator results cannot establish Nearby reliability or acoustic accuracy.

## Setup

1. Record phone/tablet model, Android version, Play services version and APK SHA-256. Install the **same** debug APK on both. In Android's file manager, open the APK and permit installation from that source when prompted. Alternatively, enable USB debugging and run `adb -s SERIAL install -r stemsync-air-debug.apk` for each device. Do not publish the debug build.
2. Enable Wi-Fi and Bluetooth. On Android 10–12L, also enable Location for legacy discovery. Accept the relevant Nearby permission prompts. If declined, use Help → App settings & permissions. Complete any Play services update before the offline test.
3. Disable mobile data and disconnect Wi-Fi from internet-connected access points, while leaving the Wi-Fi radio on. Airplane mode is only suitable if Wi-Fi and Bluetooth are then explicitly reenabled. Keep the app visible and both screens unlocked.
4. Place both devices about one metre apart. Use their built-in speakers. Record local volume and disable unrelated media playback. Do not equate this geometry with measured acoustic alignment.

## Discovery, authentication and actual transfer

5. Phone: Host Session. Tablet: Join Session. Select the discovered phone.
6. Confirm that BOTH devices display the same Nearby authentication code. First exercise Reject and verify that no guest participant or transfer is accepted. Connect again and explicitly select Codes match on both devices.
7. Allow the original demo transfer to complete. A fresh guest does not copy its bundled demo to its playable library; it requests FILE payloads from the host. Observe actual byte progress, Verifying, then Ready. The host should show the guest ready only after it has prepared audio and collected clock samples.
8. Export Connection details on both devices. Compare the song identifiers and SHA-256 entries. Hash agreement is meaningful only alongside the receiver's successful private-file verification, not merely identical displayed metadata.
9. Stronger transfer test: select a short original PCM16 WAV **present only on the phone**, using Import WAV files. Do not pre-copy it to the tablet. Observe the tablet's transfer, verification, preparation and subsequent playback. Optionally clear tablet app storage first to guarantee a cache miss; this erases only the test app's cache and settings.

## Shared playback

10. Select Full mix on both devices for the bundled song. Wait for the guest's Ready status (typically at least five sync exchanges).
11. Host: Play together. Confirm that playback starts after the scheduled lead time and progresses on both devices. Record guest mean RTT, RTT variation, offset estimate and estimated playback timeline error.
12. Pause, resume, then seek to 10 seconds and 24 seconds. Verify both devices follow each control. At end of the 32-second song, confirm both stop. Press Play again and verify a new start from zero.
13. Switch phone to Plucked lead and tablet to Drums during playback. Verify the musical phrase remains on the same timeline. A decoder preparation gap may occur; record it rather than claiming seamless switching.
14. Adjust the tablet's volume and confirm it does not change the phone volume or shared timeline. Switch back to Full mix.
15. Change the song on the host while connected. Confirm old readiness cannot start the new song before verification/preparation.

## Interruptions and recovery

16. During a transfer, use Cancel transfers. Verify no incomplete audio becomes playable. Use Send / retry music and confirm successful completion. Repeat by temporarily disabling the tablet's Wi-Fi/Bluetooth. Retry restarts the file; no partial resume is implemented.
17. During playback, disable a tablet radio or move it out of range. Observe a visible disconnect or stale-clock pause. Restore radios, tap Reconnect, find the same host and **reauthenticate**. After cache validation and new clock estimates, verify the tablet rejoins at the host's current position. For a 32-second demo, do this promptly; a longer original WAV makes a 25-second-late recovery easier to evaluate.
18. Background the guest and confirm local playback pauses. Return and confirm recovery while foregrounded. Background the host and verify a global pause is sent. Background playback is intentionally unsupported.
19. Exercise audio focus by playing audio in another app or receiving a phone call. Record focus-loss behavior and explicit recovery with the host controls. Check that sound does not unexpectedly restart while interrupted.
20. Repeat at portrait, landscape, tablet width and increased system text size. During active playback, rotate or recreate the Activity and verify there is no global pause; genuinely backgrounding must still pause. Check that actions can be scrolled into view and have understandable labels.

## Audible calibration — subjective, not a precision measurement

Choose the same drum stem on both devices. Stand halfway between them. If one speaker sounds early, open Help there and increase Speaker delay in 5–10 ms steps. Positive delay makes that speaker later; negative makes it earlier. Restart playback after large adjustments and repeat several trials. Save the chosen delay in the test record (this version does not persist calibration across process restarts).

For an acoustic measurement, use an **external** recording device or measurement setup; there is no in-app microphone feature. Record each speaker through separate channels with equal geometry, cross-correlate repeated drum transients, and account for channel/interface latency and propagation distance. At room temperature, one metre of path difference is roughly 3 ms. Measure at least ten trials and include uncertainty and device routes. App timeline error alone cannot establish acoustic accuracy.

## Test record

| Trial | Devices / OS | Offline verified | Setup time | Transfer MiB/s | Guest RTT mean / SD | Offset | Timeline error | Acoustic method/result | Recovery | Pass/fail and notes |
|---|---|---|---|---|---|---|---|---|---|---|
| Pending | | | | | | | | Not measured | | |

Keep failed trials. Capture diagnostics before/after each interruption. Record warm-cache versus cold-transfer runs separately. A claimed two-device acceptance requires every core step above to pass on the named physical devices.
