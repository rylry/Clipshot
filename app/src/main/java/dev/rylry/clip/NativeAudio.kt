package dev.rylry.clip

object NativeAudio {
    init {
        System.loadLibrary("clip")
    }

    fun start(
        sampleRate: Int = 44100,
        duration: Int = 30,
        channels: Int = 1
    ) {
        _start(sampleRate, duration, channels)
    }
    fun stop() { _stop() }
    fun copySnapshot(out: ByteArray) { _copySnapshot(out) }
    fun isRecordingActive() : Boolean { return _isRecordingActive() }

    external private fun _start(sampleRate: Int, durationSeconds: Int, channels: Int)
    external private fun _stop()
    external private fun _copySnapshot(out: ByteArray)
    external private fun _isRecordingActive() : Boolean
}