package air.stemsync.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ManifestAndCacheTest {
    private val tracks = Protocol.json.decodeFromString<List<Track>>(File("src/main/assets/demo/manifest.json").readText())
    @Test fun delayedScanCannotPublishAfterNewManifest() {
        val gate=ManifestScope(); val old=gate.begin("session",1,listOf(tracks[0])); val latest=gate.begin("session",2,listOf(tracks[1]))
        var ready=emptyList<Track>()
        if (gate.accepts(latest)) ready=latest.tracks
        // The older disk scan finishes last. Its result must not replace current readiness.
        if (gate.accepts(old)) ready=old.tracks
        assertEquals(listOf(tracks[1]),ready)
    }
    @Test fun oldTransferCannotMarkNewSongReady() {
        val gate=ManifestScope(); val transfer=gate.begin("session",1,listOf(tracks[0]))
        gate.begin("session",2,listOf(tracks[1]))
        assertFalse(gate.accepts(transfer,tracks[0]))
    }
    @Test fun reconnectInvalidatesPendingWorkEvenForSameSessionAndSequence() {
        val gate=ManifestScope(); val old=gate.begin("session",1,tracks); gate.clear(); val next=gate.begin("session",1,tracks)
        assertFalse(gate.accepts(old)); assertTrue(gate.accepts(next))
    }
    @Test fun capturedManifestDoesNotFollowMutableCallerList() {
        val gate=ManifestScope(); val input=tracks.take(1).toMutableList(); val snapshot=gate.begin("session",1,input)
        input.clear(); assertEquals(listOf(tracks[0]),snapshot.tracks); assertTrue(gate.accepts(snapshot,tracks[0])); assertFalse(gate.accepts(snapshot,tracks[1]))
    }
    @Test fun validCachedContentNeedsNoIndexTrust() {
        val t=tracks[0]; assertEquals(listOf(t),RequiredAudioCache.verified(listOf(t)){File("src/main/assets/demo",it.filename)})
    }
    @Test fun onlyRequiredFilesAreRead() {
        val needed=tracks[1]; val visited=mutableListOf<Track>()
        assertEquals(listOf(needed),RequiredAudioCache.verified(listOf(needed)){visited+=it;File("src/main/assets/demo",it.filename)})
        assertEquals(listOf(needed),visited)
    }
    @Test fun sameLengthCorruptedCacheIsRejectedOnNextVerification() {
        val t=tracks[0]; val temp=File.createTempFile("cache-test", ".wav")
        try {
            File("src/main/assets/demo",t.filename).copyTo(temp,overwrite=true)
            assertEquals(listOf(t),RequiredAudioCache.verified(listOf(t)){temp})
            val bytes=temp.readBytes(); bytes[100]=(bytes[100].toInt() xor 1).toByte(); temp.writeBytes(bytes)
            assertTrue(RequiredAudioCache.verified(listOf(t)){temp}.isEmpty())
        } finally { temp.delete() }
    }
    @Test fun missingRequiredFileIsNotReady() {
        assertTrue(RequiredAudioCache.verified(tracks.take(1)){File("missing-cache-file.wav")}.isEmpty())
    }
}
