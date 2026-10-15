# Spotify compatibility

Reviewed against Spotify's official documentation on 2026-10-15. **This build does not implement Spotify playback.** A Spotify link or offline Spotify download cannot be imported as a WAV or distributed through this app's Nearby session.

## What the supported SDK can do

Spotify's Android App Remote SDK controls the installed Spotify app: it can request playback and receive player state. Spotify handles the audio, its cache and output route. The SDK does not supply decoded audio files or separate instrument stems to StemSync's Media3 player. Offline App Remote support does not make Spotify's cached music available for Nearby file transfer.

A separate Spotify controller could be built with a registered developer application, its client ID, a registered redirect URI, the Android package/signing fingerprint, the Spotify app and user authorization. That would control Spotify playback separately; it would not deliver StemSync's synchronized two-speaker or distributed-instrument mode. There is no client secret embedded in the current app, no Spotify credentials requested, and no account integration configured.

Spotify's developer policy also restricts broadcasting a single source to multiple simultaneous listeners. The current shared audio implementation is consequently limited to local files and the original demo. Do not present a link-opening shortcut or an unconfigured controller as completed Spotify synchronization.

For listening together within Spotify, its own Jam feature supports shared listening. Starting a Jam and joining remotely require Premium under the currently documented rules. Jam is operated in Spotify; it is not an offline StemSync transport and is not evidence of precise speaker alignment.

## Official references

- [Android SDK capabilities](https://developer.spotify.com/documentation/android)
- [Android setup and app registration](https://developer.spotify.com/documentation/android/tutorials/getting-started)
- [Developer policy, including prohibited applications](https://developer.spotify.com/policy)
- [Start or join a Jam](https://support.spotify.com/us/article/jam/)
