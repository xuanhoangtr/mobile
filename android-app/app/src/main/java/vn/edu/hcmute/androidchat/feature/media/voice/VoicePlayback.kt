package vn.edu.hcmute.androidchat.feature.media.voice

import android.media.MediaPlayer

interface VoicePlayback {
    fun play(path: String, onCompleted: () -> Unit)
    fun stop()
}

class AndroidVoicePlayback : VoicePlayback {
    private var player: MediaPlayer? = null

    override fun play(path: String, onCompleted: () -> Unit) {
        stop()
        val instance = MediaPlayer()
        try {
            instance.setDataSource(path)
            instance.setOnCompletionListener {
                player = null
                instance.release()
                onCompleted()
            }
            instance.prepare()
            instance.start()
            player = instance
        } catch (failure: Exception) {
            instance.release()
            throw failure
        }
    }

    override fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }
}
