package air.stemsync.core

import kotlinx.serialization.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.*
import java.security.MessageDigest

const val MAX_FILE_BYTES = 100L * 1024 * 1024
@Serializable
data class Track(val id: String, val songId: String, val stem: String, val title: String,
    val filename: String, val size: Long, val sha256: String, val durationMs: Long,
    val sampleRate: Int, val format: String = "audio/wav") {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && songId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(stem.length in 1..40 && title.length in 1..100)
        require(filename.matches(Regex("[a-zA-Z0-9_-]{1,100}\\.wav"))) { "Unsafe filename" }
        require(size in 45..MAX_FILE_BYTES && durationMs in 1..3_600_000)
        require(format == "audio/wav" && sampleRate in listOf(22050, 24000, 44100, 48000))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
    }
}
@Serializable
enum class Kind { HELLO, MANIFEST, NEED, FILE_META, FILE_READY, FILE_FAILED, SYNC_REQUEST, SYNC_RESPONSE, TIMELINE, ACK, READY, ERROR, LEAVE }
@Serializable
data class Message(
    val protocolVersion: Int = 1, val sessionId: String, val senderId: String,
    val messageId: String, val sequenceNumber: Long, val timestampNs: Long, val kind: Kind,
    val tracks: List<Track> = emptyList(), val contentId: String? = null, val payloadId: Long? = null,
    val timeline: Timeline? = null, val t1: Long? = null, val t2: Long? = null, val t3: Long? = null,
    val ready: Boolean? = null, val revision: Long? = null, val detail: String? = null
)
object Protocol {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    fun encode(m: Message): ByteArray = json.encodeToString(m).toByteArray(Charsets.UTF_8).also { require(it.size <= 24_000) }
    fun decode(bytes: ByteArray): Message {
        require(bytes.size in 1..24_000) { "Control message too large" }
        val m = json.decodeFromString<Message>(bytes.toString(Charsets.UTF_8))
        require(m.protocolVersion == 1) { "Unsupported protocol version" }
        require(m.sessionId.length <= 80 && m.senderId.length in 1..80 && m.messageId.length in 1..100)
        require(m.sequenceNumber >= 0 && m.timestampNs >= 0 && m.tracks.size <= 8 && (m.detail?.length ?: 0) <= 200)
        m.tracks.forEach { it.validate() }
        require(m.timeline?.valid() != false)
        return m
    }
}

enum class TransferPhase { QUEUED, TRANSFERRING, VERIFYING, READY, FAILED }
/** Arrival-order independent gate. A payload announcement is never completion. */
class TransferGate {
    private data class Entry(var track: Track? = null, var arrived: Boolean = false, var complete: Boolean = false, var claimed: Boolean = false)
    private val entries = mutableMapOf<Long, Entry>()
    fun metadata(id: Long, track: Track) { track.validate(); val e = entries.getOrPut(id) { Entry() }; require(e.track == null || e.track == track) { "Conflicting duplicate payload" }; e.track = track }
    fun arrived(id: Long) { entries.getOrPut(id) { Entry() }.arrived = true }
    fun completed(id: Long) { entries.getOrPut(id) { Entry() }.complete = true }
    fun claim(id: Long): Track? { val e = entries[id] ?: return null; if (e.claimed || !e.arrived || !e.complete) return null; return e.track?.also { e.claimed = true } }
    fun remove(id: Long) { entries.remove(id) }
}
object Files {
    fun sha256(file: File) = file.inputStream().use { sha256(it) }
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        var total = 0L; val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= limit) { "Audio exceeds size limit" }; output.write(buffer, 0, n) }
        return total
    }
    fun verified(file: File, track: Track) = file.isFile && file.length() == track.size && sha256(file) == track.sha256
}

data class WavInfo(val sampleRate: Int, val channels: Int, val frames: Long) { val durationMs get() = frames * 1000 / sampleRate }
object Wav {
    fun requireAligned(stems: List<WavInfo>) {
        require(stems.isNotEmpty() && stems.map { it.sampleRate to it.frames }.distinct().size == 1) { "Stems must have identical sample rates and PCM frame counts" }
    }
    /** Narrow, deliberate MVP: uncompressed 16-bit PCM WAV, mono or stereo. */
    fun inspect(file: File): WavInfo = RandomAccessFile(file, "r").use { f ->
        fun tag(): String { val b = ByteArray(4); f.readFully(b); return String(b, Charsets.US_ASCII) }
        fun u32() = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
        fun u16() = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
        require(tag() == "RIFF"); val riff = u32(); require(riff + 8 == f.length()); require(tag() == "WAVE")
        var rate = 0; var channels = 0; var dataBytes = 0L; var formatSeen = false
        while (f.filePointer + 8 <= f.length()) {
            val id = tag(); val size = u32(); val start = f.filePointer
            require(start + size <= f.length())
            when (id) {
                "fmt " -> { require(size >= 16); require(u16() == 1); channels = u16(); rate = u32().toInt(); val byteRate = u32(); val align = u16(); val bits = u16()
                    require(channels in 1..2 && rate in listOf(22050,24000,44100,48000) && bits == 16 && align == channels * 2 && byteRate == rate.toLong()*align); formatSeen = true }
                "data" -> { require(dataBytes == 0L); dataBytes = size }
            }
            f.seek(start + size + (size % 2))
        }
        require(formatSeen && dataBytes > 0 && dataBytes % (channels * 2) == 0L)
        WavInfo(rate, channels, dataBytes / (channels * 2))
    }
}
