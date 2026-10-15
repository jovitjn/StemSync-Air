package air.stemsync.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import air.stemsync.core.MAX_FILE_BYTES
import air.stemsync.core.SUPPORTED_PCM_RATES
import air.stemsync.core.Wav
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode once on the host; peers receive the same verified PCM file and timeline. */
object Mp3Import {
    fun decode(source: File, destination: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mpeg"
            } ?: error("Choose an MP3 song or a 16-bit PCM WAV file")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType("audio/mpeg")
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            var rate = 0
            var channels = 0
            var bytes = 0L
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 180_000
            RandomAccessFile(destination, "rw").use { out ->
                out.setLength(0); out.write(ByteArray(44))
                while (!outputEnded) {
                    check(SystemClock.elapsedRealtime() < deadline) { "MP3 conversion timed out. Try a shorter song" }
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = decoder.outputFormat
                        val nextRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val nextChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(nextRate in SUPPORTED_PCM_RATES && nextChannels in 1..2) { "MP3 must be mono/stereo at a standard 8–48 kHz sample rate" }
                        require(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) || output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "Unsupported MP3 decoder output" }
                        require(bytes == 0L || rate == nextRate && channels == nextChannels) { "MP3 audio format changed mid-song" }
                        rate = nextRate; channels = nextChannels
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                require(rate > 0 && channels > 0) { "Missing MP3 audio format" }
                                require(bytes + info.size + 44 <= MAX_FILE_BYTES) { "Song exceeds the 100 MiB decoded audio limit. Choose a shorter song" }
                                val buffer = requireNotNull(decoder.getOutputBuffer(index))
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size); buffer.get(chunk); out.write(chunk)
                                bytes += info.size
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
                require(bytes > 0 && channels > 0 && bytes % (channels * 2) == 0L) { "MP3 contains no usable audio" }
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()); header.putInt((bytes+36).toInt()); header.put("WAVEfmt ".toByteArray())
                header.putInt(16); header.putShort(1); header.putShort(channels.toShort()); header.putInt(rate)
                header.putInt(rate*channels*2); header.putShort((channels*2).toShort()); header.putShort(16)
                header.put("data".toByteArray()); header.putInt(bytes.toInt())
                out.seek(0); out.write(header.array())
            }
            Wav.inspect(destination)
        } catch (failure: Exception) {
            destination.delete()
            throw failure
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
