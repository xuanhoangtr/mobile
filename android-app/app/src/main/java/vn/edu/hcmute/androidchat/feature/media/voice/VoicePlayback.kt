package vn.edu.hcmute.androidchat.feature.media.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

interface VoicePlayback {
    fun play(path: String, onCompleted: () -> Unit)
    fun pause() {}
    fun resume() {}
    fun stop()
    fun currentPositionMs(): Long = 0L
    fun totalDurationMs(): Long = 0L
}

class AndroidVoicePlayback(context: Context) : VoicePlayback {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            player?.pause()
        }
    }
    private val focusRequest = if (android.os.Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    } else null
    private var player: MediaPlayer? = null

    override fun play(path: String, onCompleted: () -> Unit) {
        stop()
        val focusGranted = if (android.os.Build.VERSION.SDK_INT >= 26) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        if (focusGranted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw IllegalStateException("Audio focus is unavailable")
        }
        val instance = MediaPlayer()
        try {
            instance.setDataSource(path)
            instance.setOnCompletionListener {
                player = null
                instance.release()
                abandonFocus()
                onCompleted()
            }
            instance.prepare()
            instance.start()
            player = instance
        } catch (failure: Exception) {
            instance.release()
            abandonFocus()
            throw failure
        }
    }

    override fun pause() {
        player?.pause()
    }

    override fun resume() {
        player?.start()
    }

    override fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        abandonFocus()
    }

    override fun currentPositionMs(): Long =
        runCatching { player?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)

    override fun totalDurationMs(): Long =
        runCatching { player?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)

    private fun abandonFocus() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }
}
