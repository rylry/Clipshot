package dev.rylry.clip

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.items

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackTab(viewModel: MainViewModel) {
    val clips by viewModel.clips.collectAsState()
    val context = LocalContext.current

    LazyColumn () {
        items(clips) { clip ->
            Row (
                modifier = Modifier
                    .clickable() { viewModel.playClip(clip, context) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(
                        if (clip.extension == "mp4") android.R.drawable.ic_menu_camera else android.R.drawable.ic_btn_speak_now
                    ),
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(clip.name)
            }
        }
    }
}
