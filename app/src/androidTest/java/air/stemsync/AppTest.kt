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
