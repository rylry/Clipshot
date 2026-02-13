package dev.rylry.clip

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

final class MainViewModel : ViewModel() {
    var isMicOn by mutableStateOf(true)
    var isCameraOn by mutableStateOf(true)
    var isRecording by mutableStateOf(false)

    // Dummy clip list
    private val _clips = MutableStateFlow<List<File>>(listOf(
        File("audio_01.m4a"),
        File("video_01.mp4"),
        File("audio_02.m4a")
    ))
    val clips: StateFlow<List<File>> = _clips

    fun toggleMic() { isMicOn = !isMicOn }
    fun toggleCamera() { isCameraOn = !isCameraOn }
    fun clipMoment() { /* call NativeAudio.snapshot() later */ }

    fun playClip(file: File, context: Context) {
        if (file.extension == "mp4") {
            // Dummy video playback
            Toast.makeText(context, "Would play video: ${file.name}", Toast.LENGTH_SHORT).show()
        } else {
            // Dummy audio playback
            Toast.makeText(context, "Would play audio: ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }
}
