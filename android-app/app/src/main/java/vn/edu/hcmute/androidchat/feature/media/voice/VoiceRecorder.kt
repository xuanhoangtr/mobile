package vn.edu.hcmute.androidchat.feature.media.voice

import android.media.MediaRecorder
import java.io.File

interface VoiceRecorder {
    fun start(output: File)
    fun stop(): Long
    fun cancel()
}

class AndroidVoiceRecorder : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    override fun start(output: File) {
        check(recorder == null) { "A recording is already active" }
        output.parentFile?.mkdirs()
        val instance = MediaRecorder()
        try {
            instance.setAudioSource(MediaRecorder.AudioSource.MIC)
            instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            instance.setOutputFile(output.absolutePath)
            instance.prepare()
            instance.start()
            recorder = instance
            this.output = output
        } catch (failure: Exception) {
            instance.release()
            output.delete()
            throw failure
        }
    }

    override fun stop(): Long {
        val instance = recorder ?: error("No active recording")
        val target = output ?: error("Recording output is missing")
        return try {
            instance.stop()
            target.length()
        } finally {
            instance.reset()
            instance.release()
            recorder = null
            output = null
        }
    }

    override fun cancel() {
        recorder?.let {
            runCatching { it.stop() }
            it.reset()
            it.release()
        }
        recorder = null
        output?.delete()
        output = null
    }
}
