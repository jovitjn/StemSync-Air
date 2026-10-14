package air.stemsync.audio

import android.content.Context
import android.net.Uri
import air.stemsync.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

class Library(private val context: Context) {
    private val root = File(context.filesDir, "audio").apply { mkdirs() }
    private val index = File(root, "library.json")
    fun file(track: Track) = File(root, track.sha256 + ".wav")
    fun tracks(): List<Track> = runCatching { Protocol.json.decodeFromString<List<Track>>(index.readText()).filter { runCatching { it.validate(); Files.verified(file(it), it) }.getOrDefault(false) } }.getOrDefault(emptyList())
    @Synchronized fun remember(track: Track) {
        val old = runCatching { Protocol.json.decodeFromString<List<Track>>(index.readText()) }.getOrDefault(emptyList())
        val temp = File(root, "library.tmp")
        temp.writeText(Protocol.json.encodeToString((old.filter { it.id != track.id } + track).takeLast(64)))
        check(temp.renameTo(index))
    }
    fun installDemo(): List<Track> {
        val tracks = Protocol.json.decodeFromString<List<Track>>(context.assets.open("demo/manifest.json").bufferedReader().use { it.readText() })
        tracks.forEach { track ->
            track.validate()
            if (!Files.verified(file(track), track)) context.assets.open("demo/${track.filename}").use { input -> file(track).outputStream().use { input.copyTo(it) } }
            check(Files.verified(file(track), track)); remember(track)
        }
        return tracks
    }
    fun import(uri: Uri): Track {
        val temp = File.createTempFile("import", ".wav", root)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { Files.copyBounded(input, it, MAX_FILE_BYTES) } }
            val wav = Wav.inspect(temp); val hash = Files.sha256(temp)
            val display = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "My track"
            val track = Track(hash.take(24), hash.take(24), "Full mix", display.take(100), "import-${hash.take(16)}.wav", temp.length(), hash, wav.durationMs, wav.sampleRate)
            track.validate(); check(temp.renameTo(file(track))); remember(track); return track
        } finally { temp.delete() }
    }
    fun receive(uri: Uri, track: Track): File {
        track.validate()
        val temp = File.createTempFile("received", ".part", root)
        try {
            val size = context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { Files.copyBounded(input, it, track.size) } }
            require(size == track.size && Files.verified(temp, track)) { "Checksum or size mismatch" }
            val wav = Wav.inspect(temp)
            require(wav.sampleRate == track.sampleRate && wav.durationMs == track.durationMs) { "Audio metadata mismatch" }
            check(temp.renameTo(file(track))); remember(track); return file(track)
        } finally { temp.delete() }
    }
}
