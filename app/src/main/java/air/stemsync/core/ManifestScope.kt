package air.stemsync.core

/** A lease for asynchronous work. A new manifest or connection invalidates old work. */
class ManifestScope {
    data class Snapshot internal constructor(val epoch: Long, val sessionId: String, val sequence: Long, val tracks: List<Track>)
    private var epoch = 0L
    private var current: Snapshot? = null
    fun begin(sessionId: String, sequence: Long, tracks: List<Track>): Snapshot {
        val snapshot = Snapshot(++epoch, sessionId, sequence, tracks.toList())
        current = snapshot
        return snapshot
    }
    fun current() = current
    fun accepts(snapshot: Snapshot, track: Track? = null) = current == snapshot && (track == null || track in snapshot.tracks)
    fun clear() { epoch++; current = null }
}

/** Verify only the files needed by this session; index entries never establish integrity. */
object RequiredAudioCache {
    fun verified(required: List<Track>, file: (Track) -> java.io.File): List<Track> = required.filter { track ->
        runCatching {
            track.validate()
            val candidate = file(track)
            Files.verified(candidate, track) && Wav.inspect(candidate).let { it.sampleRate == track.sampleRate && it.durationMs == track.durationMs }
        }.getOrDefault(false)
    }
}
