package dev.rylry.clip

import android.widget.NumberPicker
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * RecordTab - Compose UI:
 *
 * Top: animated waveform (fast)
 * Middle: clock-style scrollable inputs (hours / minutes / seconds)
 * Timer: countdown display (mm:ss:cs) counting hundredths (10ms ticks)
 * Bottom: Clip button + Start/Stop
 *
 * Accepts MainViewModel for actions (start/stop/clip). Replace calls as needed.
 */

@Composable
fun RecordTab(viewModel: MainViewModel) {
    // NumberPicker-backed state
    var hours by remember { mutableStateOf(0) }           // 0 .. 999
    var minutes by remember { mutableStateOf(0) }         // 0 .. 59
    var seconds by remember { mutableStateOf(0) }         // 0 .. 59

    // Timer state (centiseconds = 1/100 second)
    var remainingCentis by remember { mutableStateOf(0L) }   // centiseconds remaining
    var running by remember { mutableStateOf(false) }       // is countdown running

    // Waveform state: a list of amplitude floats, newer values added at right
    val waveformSize = 64
    val amplitudes = remember { mutableStateListOf<Float>().apply {
        repeat(waveformSize) { add(0.02f + Random.nextFloat() * 0.02f) }
    } }

    // When user toggles start: compute the total centiseconds from pickers
    fun computeTotalCentis(): Long {
        // clamp large values safely
        val h = max(0, min(999, hours))
        val m = max(0, min(59, minutes))
        val s = max(0, min(59, seconds))
        return (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 100L
    }

    // Start/stop actions
    fun start() {
        remainingCentis = computeTotalCentis()
        if (remainingCentis <= 0L) {
            // Nothing to do
            return
        }
        running = true
        // integrate with viewModel/native as needed
        // viewModel.startRecording() // implement in your ViewModel: start native capture
    }

    fun stop(saveClip: Boolean = false) {
        running = false
        // viewModel.stopRecording()
        if (saveClip) viewModel.clipMoment()
    }

    // Countdown coroutine: ticks every 10ms when running
    LaunchedEffect(running) {
        // high-resolution loop
        while (running && isActive) {
            val tickStart = System.nanoTime()
            if (remainingCentis <= 0L) {
                running = false
                break
            }
            // decrement by 1 centisecond (10 ms)
            remainingCentis = max(0L, remainingCentis - 1L)
            // sleep until next 10 ms tick, adjusting for work time
            val tickDurationNs = 10_000_000L
            val elapsedNs = System.nanoTime() - tickStart
            val sleepNs = tickDurationNs - elapsedNs
            if (sleepNs > 0) {
                delay(sleepNs / 1_000_000L) // convert to ms (coarse)
            } else {
                // busy schedule: yield briefly
                kotlinx.coroutines.yield()
            }
        }
        // stop hook
        if (!running) {
            // optionally call viewModel.stopRecording() here if not already stopped
        }
    }

    // Waveform animation coroutine: faster updates (~30-60 FPS)
    LaunchedEffect(Unit) {
        while (isActive) {
            // produce a new amplitude using a mix of sine and random
            val t = System.nanoTime() / 1e9
            val base = (sin(t * 8.0) * 0.5 + 0.5).toFloat() // oscillate faster
            val jitter = Random.nextFloat() * 0.3f
            val amp = 0.02f + base * 0.9f * (0.6f + jitter)
            // push and pop to simulate scrolling waveform
            if (amplitudes.size >= waveformSize) amplitudes.removeAt(0)
            amplitudes.add(amp.coerceIn(0.01f, 1.0f))
            // roughly 30-60 FPS; choose 20ms for 50 FPS -> smooth
            delay(16L)
        }
    }

    // UI layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (running) "Recording" else "Idle"
        )
        // Waveform canvas at top
        WaveformCanvas(amplitudes = amplitudes, modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color.White)
        )

        // Clock-style scrollable inputs (NumberPickers)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NumberPickerColumn(
                label = "Hours",
                value = hours,
                rangeStart = 0,
                rangeEnd = 999
            ) { new -> hours = new }

            Spacer(modifier = Modifier.width(8.dp))

            NumberPickerColumn(
                label = "Min",
                value = minutes,
                rangeStart = 0,
                rangeEnd = 59
            ) { new -> minutes = new }

            Spacer(modifier = Modifier.width(8.dp))

            NumberPickerColumn(
                label = "Sec",
                value = seconds,
                rangeStart = 0,
                rangeEnd = 59
            ) { new -> seconds = new }
        }

        // Visible countdown timer (mm:ss:cs)
        val totalSecondsDisplay = remainingCentis / 100L
        val displayMinutes = (totalSecondsDisplay / 60L).toLong()
        val displaySeconds = (totalSecondsDisplay % 60L).toLong()
        val centis = (remainingCentis % 100L).toInt()

        Text(
            text = String.format("%d:%02d.%02d", displayMinutes, displaySeconds, centis),
            fontSize = 36.sp,
            color = if (running) Color.Red else Color.White
        )

        // Buttons row: Clip, Start/Stop
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clip button: capture current buffer without stopping
            Button(onClick = { viewModel.clipMoment() }) {
                Text("Clip")
            }

            // Start/Stop button
            Button(
                onClick = {
                    if (running) {
                        stop(saveClip = false)
                    } else {
                        // avoid starting when time is zero
                        if (computeTotalCentis() > 0L) start()
                    }
                }
            ) {
                Text(if (running) "Stop" else "Start")
            }
        }
    }
}

/** Canvas-based waveform drawing */
@Composable
private fun WaveformCanvas(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cols = amplitudes.size.coerceAtLeast(1)
        val colWidth = w / cols
        val gap = colWidth * 0.15f
        val barWidth = (colWidth - gap).coerceAtLeast(1f)
        for (i in amplitudes.indices) {
            val amp = amplitudes[i].coerceIn(0f, 1f)
            val barH = amp * h
            val left = i * colWidth + gap * 0.5f
            val top = (h - barH) / 2f
            val right = left + barWidth
            val bottom = top + barH
            drawRect(Color.Blue, topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, barH), style = Fill)
        }
    }
}

/** NumberPicker wrapped for Compose via AndroidView */
@Composable
private fun NumberPickerColumn(
    label: String,
    value: Int,
    rangeStart: Int,
    rangeEnd: Int,
    onValueChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 12.sp, color = Color.Gray)
        AndroidView(
            factory = { ctx ->
                NumberPicker(ctx).apply {
                    minValue = rangeStart
                    maxValue = rangeEnd
                    wrapSelectorWheel = true
                    setOnValueChangedListener { _, _, newVal -> onValueChange(newVal) }
                    // visual tuning (optional)
                    descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { np ->
                if (np.value != value) np.value = value
            },
            modifier = Modifier
                .width(88.dp)
                .height(120.dp)
        )
    }
}