package air.stemsync.audio

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class PlaybackEngine(context: Context, val changed: () -> Unit, val failure: (String) -> Unit) {
    val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = changed()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = changed()
            override fun onPlayerError(error: PlaybackException) = failure("Audio could not play: ${error.errorCodeName}")
        })
    }
    var selectedId: String? = null; private set
    val ready get() = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
    fun load(id: String, file: File, positionMs: Long) {
        selectedId = id; player.pause(); player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file))); player.seekTo(positionMs); player.prepare()
    }
    fun release() = player.release()
}
