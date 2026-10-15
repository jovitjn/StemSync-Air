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
