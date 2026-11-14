package vn.edu.hcmute.androidchat.feature.media.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class CallAudioFocus(context: Context, private val onLost: () -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            onLost()
        }
    }
    private val request = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
    } else null

    fun request(): Boolean {
        val manager = audioManager ?: return false
        val result = if (Build.VERSION.SDK_INT >= 26) {
            manager.requestAudioFocus(request!!)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                listener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun setSpeakerphoneOn(enabled: Boolean) {
        val manager = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = manager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        manager.setCommunicationDevice(speaker)
                    }
                } else {
                    manager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = enabled
            }
        }
    }

    fun abandon() {
        val manager = audioManager ?: return
        setSpeakerphoneOn(false)
        runCatching { manager.mode = AudioManager.MODE_NORMAL }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.abandonAudioFocusRequest(request!!)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(listener)
        }
    }
}
