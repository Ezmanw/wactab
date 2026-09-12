package com.wactab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wactab.app.net.PenServer
import com.wactab.app.ui.PenSurfaceView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WactabApp()
            }
        }
    }
}

@Composable
fun WactabApp() {
    var port by remember { mutableStateOf(7912) }
    var running by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }

    // left, top, right, bottom as fractions of the screen (0f..1f)
    var rect by remember { mutableStateOf(floatArrayOf(0.05f, 0.05f, 0.95f, 0.95f)) }

    val server = remember(port) {
        PenServer(port) { isConnected -> connected = isConnected }
    }

    var surfaceView by remember { mutableStateOf<PenSurfaceView?>(null) }
    LaunchedEffect(rect) { surfaceView?.activeRect = rect }
    LaunchedEffect(running) {
        surfaceView?.onPenEvent = if (running && !calibrating) {
            { event -> server.send(event) }
        } else null
    }
    LaunchedEffect(calibrating) {
        surfaceView?.onPenEvent = if (running && !calibrating) {
            { event -> server.send(event) }
        } else null
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PenSurfaceView(ctx).also {
                    it.activeRect = rect
                    surfaceView = it
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (calibrating) {
            CalibrationOverlay(rect = rect, onRectChange = { rect = it })
        }

        ControlBar(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPad(),
            port = port,
            onPortChange = { port = it },
            running = running,
            connected = connected,
            calibrating = calibrating,
            onToggleCalibrate = { calibrating = !calibrating },
            onToggleRunning = {
                if (running) {
                    server.stop()
                    running = false
                } else {
                    server.start()
                    running = true
                }
            },
        )
    }
}

@Composable
private fun Modifier.statusBarsPad(): Modifier = this.padding(top = 16.dp)

@Composable
fun ControlBar(
    modifier: Modifier = Modifier,
    port: Int,
    onPortChange: (Int) -> Unit,
    running: Boolean,
    connected: Boolean,
    calibrating: Boolean,
    onToggleCalibrate: () -> Unit,
    onToggleRunning: () -> Unit,
) {
    Surface(
        modifier = modifier.padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(connected = connected, running = running)
            Text(
                text = when {
                    !running -> "Stopped"
                    connected -> "Connected"
                    else -> "Waiting for PC (adb forward tcp:$port tcp:$port)…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = port.toString(),
                onValueChange = { it.toIntOrNull()?.let(onPortChange) },
                label = { Text("Port") },
                enabled = !running,
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
            FilledTonalButton(onClick = onToggleCalibrate) {
                Text(if (calibrating) "Done" else "Calibrate area")
            }
            Button(onClick = onToggleRunning) {
                Text(if (running) "Stop" else "Start")
            }
        }
    }
}

@Composable
private fun StatusDot(connected: Boolean, running: Boolean) {
    val color = when {
        !running -> Color.Gray
        connected -> Color(0xFF4CAF50)
        else -> Color(0xFFFFC107)
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

/** Draggable rectangle the user resizes to define the active drawing area. */
@Composable
fun CalibrationOverlay(rect: FloatArray, onRectChange: (FloatArray) -> Unit) {
    val handleRadiusDp = 14.dp
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val handleRadiusPx = with(density) { handleRadiusDp.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val l = rect[0] * widthPx
            val t = rect[1] * heightPx
            val r = rect[2] * widthPx
            val b = rect[3] * heightPx
            drawRect(
                color = Color(0x66000000),
                size = this.size,
            )
            drawRect(
                color = Color.Transparent,
                topLeft = Offset(l, t),
                size = androidx.compose.ui.geometry.Size(r - l, b - t),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
            )
            drawRect(
                color = Color(0xFF64B5F6),
                topLeft = Offset(l, t),
                size = androidx.compose.ui.geometry.Size(r - l, b - t),
                style = Stroke(width = 4f),
            )
        }

        val currentRect = rememberUpdatedState(rect)
        val corners = listOf(
            "tl" to Offset(rect[0] * widthPx, rect[1] * heightPx),
            "tr" to Offset(rect[2] * widthPx, rect[1] * heightPx),
            "bl" to Offset(rect[0] * widthPx, rect[3] * heightPx),
            "br" to Offset(rect[2] * widthPx, rect[3] * heightPx),
        )

        corners.forEach { (key, pos) ->
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (pos.x - handleRadiusPx).toDp() },
                        y = with(density) { (pos.y - handleRadiusPx).toDp() },
                    )
                    .size(handleRadiusDp * 2)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF64B5F6))
                    .pointerInput(key) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val r = currentRect.value
                            val newRect = r.copyOf()
                            val curX = (if (key == "tl" || key == "bl") r[0] else r[2]) * widthPx
                            val curY = (if (key == "tl" || key == "tr") r[1] else r[3]) * heightPx
                            val fx = ((curX + dragAmount.x) / widthPx).coerceIn(0f, 1f)
                            val fy = ((curY + dragAmount.y) / heightPx).coerceIn(0f, 1f)
                            when (key) {
                                "tl" -> { newRect[0] = fx; newRect[1] = fy }
                                "tr" -> { newRect[2] = fx; newRect[1] = fy }
                                "bl" -> { newRect[0] = fx; newRect[3] = fy }
                                "br" -> { newRect[2] = fx; newRect[3] = fy }
                            }
                            onRectChange(newRect)
                        }
                    }
            )
        }
    }
}
