package dev.rylry.clip

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom
    ) {
        Box(modifier = Modifier
            .fillMaxWidth() .weight(1f)// fills space above bottom TabRow
        ) {
            when (selectedTab) {
                0 -> RecordTab(viewModel)
                1 -> PlaybackTab(viewModel)
                2 -> SettingsTab()
            }
        }

        // Bottom buttons
        TabRow (
            selectedTabIndex = selectedTab,
            indicator = {}, // remove default indicator
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val selectedColor = MaterialTheme.colorScheme.primary
            val unselectedColor = selectedColor.copy(alpha = 0.5f)

            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text(
                    "Record",
                    color = if (selectedTab == 0) selectedColor else unselectedColor,
                    modifier = Modifier.padding(16.dp).padding(bottom = 10.dp)
                )
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text(
                    "Playback",
                    color = if (selectedTab == 1) selectedColor else unselectedColor,
                    modifier = Modifier.padding(16.dp).padding(bottom = 10.dp)
                )
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text(
                    "Settings",
                    color = if (selectedTab == 2) selectedColor else unselectedColor,
                    modifier = Modifier.padding(16.dp).padding(bottom = 10.dp)
                )
            }
        }

    }
}
