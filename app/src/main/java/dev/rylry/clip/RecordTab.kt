package dev.rylry.clip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun RecordTab(viewModel: MainViewModel) {
    Box (
        modifier = Modifier.fillMaxSize(1f).padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(if (viewModel.isRecording) "Recording..." else "Idle")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.toggleMic() }) {
                Icon(
                    painter = painterResource(
                        if (viewModel.isMicOn) android.R.drawable.ic_btn_speak_now else android.R.drawable.ic_menu_add
                    ),
                    contentDescription = "Mic"
                )
            }

            IconButton(onClick = { viewModel.clipMoment() }) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_save),
                    contentDescription = "Clip"
                )
            }

            IconButton(onClick = { viewModel.toggleCamera() }) {
                Icon(
                    painter = painterResource(
                        if (viewModel.isCameraOn) android.R.drawable.ic_menu_camera else android.R.drawable.ic_menu_day
                    ),
                    contentDescription = "Camera"
                )
            }
        }
    }
}