package air.stemsync

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import air.stemsync.audio.Library
import air.stemsync.ui.*
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun homeAndActionsAccessible() {
        compose.onNodeWithText("STEMSYNC AIR").assertIsDisplayed()
        compose.onNodeWithText("Host Session").performScrollTo().assertHasClickAction()
        compose.onNodeWithText("Join Session").performScrollTo().assertHasClickAction()
    }
    @Test fun helpAndSettingsVisible() {
        compose.onNodeWithText("Help").performClick()
        compose.onNodeWithText("Wireless settings").performScrollTo().assertHasClickAction()
        compose.onNodeWithText("App settings & permissions").performScrollTo().assertHasClickAction()
    }
    @Test fun connectionErrorsAreVisible() {
        compose.runOnUiThread {
            androidx.lifecycle.ViewModelProvider(compose.activity)[SessionViewModel::class.java].failure("Connection lost. Reconnect when ready.")
        }
        compose.onNodeWithText("Connection lost. Reconnect when ready.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Connection lost. Reconnect when ready.").assertDoesNotExist()
    }
    @Test fun hostPlaybackPauseSeekAndStemSwitch() {
        lateinit var vm: SessionViewModel
        compose.runOnUiThread {
            vm=androidx.lifecycle.ViewModelProvider(compose.activity)[SessionViewModel::class.java]
            vm.hostSession()
        }
        compose.waitUntil(20000) { !vm.state.value.busy }
        compose.runOnUiThread { vm.loadDemo() }
        compose.waitUntil(20000) { vm.state.value.prepared && !vm.state.value.busy }
        compose.runOnUiThread { vm.volume(0f); vm.playPause() }
        compose.waitUntil(10000) { vm.state.value.playing && vm.state.value.position>500 }
        compose.runOnUiThread { vm.playPause() }
        compose.waitUntil(10000) { !vm.state.value.playing }
        // Query the stable paused control after scrolling; a live 32-second song
        // can end while an overloaded emulator waits for scroll animations.
        compose.onNodeWithText("Play together").performScrollTo()
        compose.onNodeWithText("Play together").assertHasClickAction()
        compose.runOnUiThread { vm.seek(10000) }
        compose.waitUntil(10000) { kotlin.math.abs(vm.state.value.position-10000)<100 }
        compose.runOnUiThread { vm.select("afterglow-drums") }
        compose.waitUntil(10000) { vm.state.value.prepared && vm.state.value.selected=="afterglow-drums" }
        Assert.assertTrue(kotlin.math.abs(vm.state.value.position-10000)<150)
        compose.runOnUiThread { vm.stop() }
        compose.waitUntil(10000) { vm.state.value.position<100 }
        compose.runOnUiThread { vm.seek(31000) }
        compose.waitUntil(10000) { vm.state.value.position>=31000 && vm.state.value.prepared }
        compose.runOnUiThread { vm.playPause() }
        try { compose.waitUntil(10000) { vm.state.value.position>=32000 && !vm.state.value.playing } }
        catch (failure: Throwable) { throw AssertionError("EOF state: ${vm.state.value}",failure) }
        compose.runOnUiThread { vm.playPause() }
        compose.waitUntil(10000) { vm.state.value.playing && vm.state.value.position<5000 }
        compose.runOnUiThread { vm.stop() }
    }
    @Test fun recreationPreservesPlaybackButTrueBackgroundPauses() {
        lateinit var vm: SessionViewModel
        compose.runOnUiThread { vm=androidx.lifecycle.ViewModelProvider(compose.activity)[SessionViewModel::class.java]; vm.hostSession() }
        compose.waitUntil(20000) { !vm.state.value.busy }
        compose.runOnUiThread { vm.loadDemo() }
        compose.waitUntil(20000) { vm.state.value.prepared && !vm.state.value.busy }
        compose.runOnUiThread { vm.volume(0f);vm.playPause() }
        compose.waitUntil(10000) { vm.state.value.playing && vm.state.value.position>500 }
        val before=vm.state.value.position
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread { Assert.assertSame(vm,androidx.lifecycle.ViewModelProvider(compose.activity)[SessionViewModel::class.java]) }
        compose.waitUntil(10000) { vm.state.value.playing && vm.state.value.position>before+1500 }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val deadline=System.nanoTime()+5_000_000_000L
        while (vm.state.value.playing && System.nanoTime()<deadline) Thread.sleep(50)
        Assert.assertFalse("Actual backgrounding must pause playback",vm.state.value.playing)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        // The host's scheduled pause must remain authoritative after returning.
        Thread.sleep(1500)
        Assert.assertFalse(vm.state.value.playing)
    }
    @Test fun mp3ContentUriImportsAndPlaysAsVerifiedSharedAudio() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val resolver=context.contentResolver
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"stemsync-original-test.mp3")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"audio/mpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
        }
        val uri=requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        val library=Library(context)
        try {
            resolver.openOutputStream(uri)!!.use { out -> instrument.context.assets.open("original-demo.mp3").use { it.copyTo(out) } }
            val track=library.import(uri)
            Assert.assertEquals("audio/wav",track.format)
            Assert.assertEquals(44100,track.sampleRate)
            Assert.assertTrue("Decoded duration: ${track.durationMs}",track.durationMs in 3900..4200)
            Assert.assertEquals(listOf(track),library.verifiedTracks(listOf(track)))
            Assert.assertThrows(IllegalArgumentException::class.java) { library.import(uri,allowMp3=false) }
            // Receiving the converted bytes exercises the same validation as Nearby FILE completion.
            val shared=java.io.File(context.cacheDir,"shared-test.wav")
            library.file(track).copyTo(shared,overwrite=true)
            library.file(track).delete()
            try { library.receive(android.net.Uri.fromFile(shared),track) } finally { shared.delete() }
            Assert.assertEquals(listOf(track),library.verifiedTracks(listOf(track)))
            val latch=CountDownLatch(1)
            var error: PlaybackException?=null
            lateinit var player: ExoPlayer
            instrument.runOnMainSync {
                player=ExoPlayer.Builder(context).build()
                player.volume=0f
                player.addListener(object: Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { if(playing) latch.countDown() }
                    override fun onPlayerError(failure: PlaybackException) { error=failure;latch.countDown() }
                })
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(library.file(track))))
                player.prepare();player.play()
            }
            try { Assert.assertTrue(latch.await(20,TimeUnit.SECONDS));Assert.assertNull(error) }
            finally { instrument.runOnMainSync { player.release() } }
        } finally { resolver.delete(uri,null,null) }
    }
    @Test fun stereoVariableBitrateMp3DecodesAndMalformedInputIsRejected() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val input=java.io.File.createTempFile("mp3-vbr", ".mp3",instrument.targetContext.cacheDir)
        val output=java.io.File.createTempFile("mp3-pcm", ".wav",instrument.targetContext.cacheDir)
        try {
            instrument.context.assets.open("original-stereo-vbr.mp3").use { source -> input.outputStream().use { source.copyTo(it) } }
            air.stemsync.audio.Mp3Import.decode(input,output)
            val audio=air.stemsync.core.Wav.inspect(output)
            Assert.assertEquals(32000,audio.sampleRate);Assert.assertEquals(2,audio.channels)
            Assert.assertTrue(audio.durationMs in 3900..4300)
            input.writeText("Not audio, despite the mp3 filename")
            Assert.assertThrows(Exception::class.java) { air.stemsync.audio.Mp3Import.decode(input,output) }
            Assert.assertFalse("Failed conversion must remove incomplete output",output.exists())
        } finally { input.delete();output.delete() }
    }
    @Test fun everyDemoStemDecodesWithMedia3() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val tracks=Library(instrument.targetContext).installDemo()
        tracks.forEach { track ->
            val latch=CountDownLatch(1)
            var failure: PlaybackException?=null
            var player: ExoPlayer?=null
            instrument.runOnMainSync {
                player=ExoPlayer.Builder(instrument.targetContext).build().also { p ->
                    p.addListener(object: Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) { if(state==Player.STATE_READY) latch.countDown() }
                        override fun onPlayerError(error: PlaybackException) { failure=error; latch.countDown() }
                    })
                    p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(Library(instrument.targetContext).file(track))))
                    p.prepare()
                }
            }
            Assert.assertTrue("Decoder timeout: ${track.stem}",latch.await(20,TimeUnit.SECONDS))
            Assert.assertNull("Decode failed: ${track.stem}",failure)
            instrument.runOnMainSync { Assert.assertEquals(32000,player!!.duration); player!!.release() }
        }
    }
}
