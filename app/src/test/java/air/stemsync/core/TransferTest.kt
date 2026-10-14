package air.stemsync.core
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream

class TransferTest {
    private val track=Track("id","song","Drums","Original","drums.wav",100,"a".repeat(64),1000,44100)
    @Test fun metadataFirst() { val g=TransferGate(); g.metadata(1,track); g.arrived(1); assertNull(g.claim(1)); g.completed(1); assertEquals(track,g.claim(1)) }
    @Test fun payloadFirst() { val g=TransferGate(); g.arrived(1); g.completed(1); assertNull(g.claim(1)); g.metadata(1,track); assertEquals(track,g.claim(1)) }
    @Test fun completionFirst() { val g=TransferGate(); g.completed(1); g.metadata(1,track); assertNull(g.claim(1)); g.arrived(1); assertEquals(track,g.claim(1)) }
    @Test fun missingMetadataNeverReady() { val g=TransferGate(); g.arrived(1); g.completed(1); assertNull(g.claim(1)) }
    @Test fun incompleteNeverReady() { val g=TransferGate(); g.metadata(1,track); g.arrived(1); assertNull(g.claim(1)) }
    @Test fun duplicateNotClaimedTwice() { val g=TransferGate(); g.metadata(1,track); g.arrived(1); g.completed(1); assertNotNull(g.claim(1)); g.metadata(1,track); g.completed(1); assertNull(g.claim(1)) }
    @Test(expected=IllegalArgumentException::class) fun conflictingDuplicateRejected() { val g=TransferGate(); g.metadata(1,track); g.metadata(1,track.copy(id="different")) }
    @Test(expected=IllegalArgumentException::class) fun oversizeRejected() { track.copy(size=MAX_FILE_BYTES+1).validate() }
    @Test(expected=IllegalArgumentException::class) fun unsupportedRejected() { track.copy(format="audio/mp3").validate() }
    @Test(expected=IllegalArgumentException::class) fun traversalRejected() { track.copy(filename="../secret.wav").validate() }
    @Test(expected=IllegalArgumentException::class) fun wrongChecksumShapeRejected() { track.copy(sha256="../oops").validate() }
    @Test fun standardSha256Vector() { assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",Files.sha256("abc".byteInputStream())) }
    @Test fun matchingAndIncorrectChecksum() { val f=File.createTempFile("test","wav"); try { f.writeBytes(ByteArray(100){it.toByte()}); assertTrue(Files.verified(f,track.copy(sha256=Files.sha256(f)))); assertFalse(Files.verified(f,track)) } finally { f.delete() } }
    @Test(expected=IllegalArgumentException::class) fun actualStreamLimitEnforced() { Files.copyBounded(ByteArray(11).inputStream(),ByteArrayOutputStream(),10) }
    @Test fun cancelRemovesGate() { val g=TransferGate(); g.metadata(1,track); g.arrived(1); g.completed(1); g.remove(1); assertNull(g.claim(1)) }
    @Test fun protocolRoundTrip() { val m=Message(sessionId="s",senderId="d",messageId="m",sequenceNumber=1,timestampNs=3,kind=Kind.MANIFEST,tracks=listOf(track)); assertEquals(m,Protocol.decode(Protocol.encode(m))) }
    @Test(expected=IllegalArgumentException::class) fun unsupportedVersionRejected() { val m=Message(protocolVersion=2,sessionId="s",senderId="d",messageId="m",sequenceNumber=1,timestampNs=3,kind=Kind.HELLO); Protocol.decode(Protocol.encode(m)) }
    @Test(expected=IllegalArgumentException::class) fun oversizedControlRejected() { Protocol.decode(ByteArray(24001)) }
    @Test fun allDemoFilesMatchManifestAndWav() {
        val dir=File("src/main/assets/demo")
        val tracks=Protocol.json.decodeFromString<List<Track>>(File(dir,"manifest.json").readText())
        assertEquals(5,tracks.size)
        tracks.forEach { t -> t.validate(); val f=File(dir,t.filename); assertTrue(Files.verified(f,t)); val wav=Wav.inspect(f); assertEquals(32000,wav.durationMs); assertEquals(44100,wav.sampleRate) }
    }
}
