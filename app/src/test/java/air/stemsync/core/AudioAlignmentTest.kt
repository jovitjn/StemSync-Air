package air.stemsync.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioAlignmentTest {
    private fun wav(frames: Int): File {
        val b=ByteBuffer.allocate(44+frames*2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray());b.putInt(36+frames*2);b.put("WAVEfmt ".toByteArray());b.putInt(16);b.putShort(1);b.putShort(1);b.putInt(44100);b.putInt(88200);b.putShort(2);b.putShort(16);b.put("data".toByteArray());b.putInt(frames*2)
        return File.createTempFile("alignment", ".wav").also { it.writeBytes(b.array()) }
    }
    @Test fun oneFrameDifferenceIsRejectedEvenWhenMillisecondsMatch() {
        val a=wav(44100); val b=wav(44101)
        try {
            val first=Wav.inspect(a);val second=Wav.inspect(b)
            assertEquals(first.durationMs,second.durationMs);assertEquals(1L,second.frames-first.frames)
            assertThrows(IllegalArgumentException::class.java){Wav.requireAligned(listOf(first,second))}
        } finally {a.delete();b.delete()}
    }
    @Test fun exactFrameCountsAndRatesAreAccepted() { Wav.requireAligned(listOf(WavInfo(44100,1,44101),WavInfo(44100,2,44101))) }
    @Test fun equalDurationDifferentRatesAreRejected() { assertThrows(IllegalArgumentException::class.java){Wav.requireAligned(listOf(WavInfo(44100,1,44100),WavInfo(48000,1,48000)))} }
    @Test fun contentValidationRejectsRenamedNonWav() {
        val f=File.createTempFile("pretend", ".wav");try { f.writeText("This is not a WAV despite its extension and MIME.");assertThrows(IllegalArgumentException::class.java){Wav.inspect(f)} } finally {f.delete()}
    }
}
