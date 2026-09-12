package com.wactab.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wactab.app.net.PenServer
import com.wactab.app.ui.PenSurfaceView
import kotlinx.coroutines.delay

private fun onColorFor(bg: Color): Color = if (bg.luminance() > 0.5f) Color.Black else Color.White

private fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/**
 * Every color role that a default M3 component might reach for (FilterChip and
 * FilledTonalButton both default to secondaryContainer, for instance) needs to be derived
 * from the chosen accent — otherwise stock Material's baseline purple leaks through on
 * exactly the controls a custom accent is supposed to reach.
 */
val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private fun wactabColorScheme(settings: WactabSettings, context: Context): ColorScheme {
    if (settings.useDynamicColor && dynamicColorAvailable) {
        val base = if (settings.themeMode == ThemeMode.LIGHT) {
            dynamicLightColorScheme(context)
        } else {
            dynamicDarkColorScheme(context)
        }
        return if (settings.themeMode == ThemeMode.DARK && settings.oledMode) {
            base.copy(background = Color(0xFF000000), surface = Color(0xFF000000))
        } else {
            base
        }
    }

    val accent = settings.accentColor
    val onAccent = onColorFor(accent)

    return if (settings.themeMode == ThemeMode.LIGHT) {
        val surface = Color(0xFFF2F2F2)
        val container = mix(accent, surface, 0.72f)
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = container,
            onPrimaryContainer = onColorFor(container),
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = container,
            onSecondaryContainer = onColorFor(container),
            tertiary = accent,
            onTertiary = onAccent,
            tertiaryContainer = container,
            onTertiaryContainer = onColorFor(container),
            background = Color(0xFFFAFAFA),
            onBackground = Color(0xFF1B1B1B),
            surface = surface,
            onSurface = Color(0xFF1B1B1B),
            surfaceVariant = Color(0xFFE6E6E6),
            onSurfaceVariant = Color(0xFF444444),
            outline = Color(0xFF787878),
        )
    } else {
        val bg = if (settings.oledMode) Color(0xFF000000) else Color(0xFF121212)
        val surface = if (settings.oledMode) Color(0xFF000000) else Color(0xFF1C1C1E)
        val container = mix(accent, surface, 0.62f)
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = container,
            onPrimaryContainer = onColorFor(container),
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = container,
            onSecondaryContainer = onColorFor(container),
            tertiary = accent,
            onTertiary = onAccent,
            tertiaryContainer = container,
            onTertiaryContainer = onColorFor(container),
            background = bg,
            onBackground = Color(0xFFE3E3E3),
            surface = surface,
            onSurface = Color(0xFFE3E3E3),
            surfaceVariant = Color(0xFF2A2A2C),
            onSurfaceVariant = Color(0xFFC4C7C6),
            outline = Color(0xFF8A8F8D),
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersive()
        setContent {
            var settings by remember { mutableStateOf(SettingsStore.load(this)) }
            MaterialTheme(colorScheme = wactabColorScheme(settings, this)) {
                WactabApp(
                    settings = settings,
                    onSettingsChange = {
                        settings = it
                        SettingsStore.save(this, it)
                    },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setImmersive()
    }

    private fun setImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
fun WactabApp(settings: WactabSettings, onSettingsChange: (WactabSettings) -> Unit) {
    var port by remember { mutableStateOf(7912) }
    var running by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var barVisible by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    // left, top, right, bottom as fractions of the screen (0f..1f) — full screen by default.
    var rect by remember { mutableStateOf(floatArrayOf(0f, 0f, 1f, 1f)) }

    val server = remember(port) {
        PenServer(port) { isConnected -> connected = isConnected }
    }

    var surfaceView by remember { mutableStateOf<PenSurfaceView?>(null) }
    LaunchedEffect(rect) { surfaceView?.activeRect = rect }
    LaunchedEffect(running, calibrating) {
        surfaceView?.onPenEvent = if (running && !calibrating) {
            { event -> server.send(event) }
        } else null
    }
    LaunchedEffect(settings.penOnlyMode) { surfaceView?.penOnlyMode = settings.penOnlyMode }
    LaunchedEffect(settings.canvasColorHex) { surfaceView?.canvasColor = settings.canvasColor.toArgb() }
    LaunchedEffect(barVisible, calibrating) {
        if (barVisible && !calibrating) {
            delay(4000)
            barVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PenSurfaceView(ctx).also {
                    it.activeRect = rect
                    it.penOnlyMode = settings.penOnlyMode
                    it.canvasColor = settings.canvasColor.toArgb()
                    surfaceView = it
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (calibrating) {
            CalibrationOverlay(rect = rect, onRectChange = { rect = it })
        }

        if (!barVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .clickable { barVisible = true },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                )
            }
        }

        AnimatedVisibility(
            visible = barVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        ) {
            ControlBar(
                modifier = Modifier.padding(top = 12.dp),
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
                onOpenSettings = { showSettings = true },
                showUndoRedo = settings.showUndoRedo,
                onUndo = { server.send(com.wactab.app.net.PenEvent.undo()) },
                onRedo = { server.send(com.wactab.app.net.PenEvent.redo()) },
            )
        }

        if (showSettings) {
            SettingsDialog(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onDismiss = { showSettings = false },
            )
        }
    }
}

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
    onOpenSettings: () -> Unit,
    showUndoRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Surface(
        modifier = modifier.padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (showUndoRedo) {
                OutlinedButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("↶ Undo")
                }
                OutlinedButton(onClick = onRedo, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("Redo ↷")
                }
            }
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
            IconlessButton(onClick = onOpenSettings, label = "⚙")
        }
    }
}

@Composable
private fun IconlessButton(onClick: () -> Unit, label: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
fun SettingsDialog(
    settings: WactabSettings,
    onSettingsChange: (WactabSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingRow(
                    title = "Pen-only mode",
                    subtitle = "Ignore finger/palm touches entirely",
                ) {
                    Switch(
                        checked = settings.penOnlyMode,
                        onCheckedChange = { onSettingsChange(settings.copy(penOnlyMode = it)) },
                    )
                }

                SettingRow(
                    title = "Undo/redo buttons",
                    subtitle = "Sends Ctrl+Z / Ctrl+Y to the focused PC app",
                ) {
                    Switch(
                        checked = settings.showUndoRedo,
                        onCheckedChange = { onSettingsChange(settings.copy(showUndoRedo = it)) },
                    )
                }

                Column {
                    Text("Appearance", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.DARK,
                            onClick = { onSettingsChange(settings.copy(themeMode = ThemeMode.DARK)) },
                            label = { Text("Dark") },
                        )
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.LIGHT,
                            onClick = { onSettingsChange(settings.copy(themeMode = ThemeMode.LIGHT, oledMode = false)) },
                            label = { Text("Light") },
                        )
                    }
                }

                SettingRow(
                    title = "OLED mode",
                    subtitle = "True black background (dark mode only)",
                    enabled = settings.themeMode == ThemeMode.DARK,
                ) {
                    Switch(
                        checked = settings.oledMode && settings.themeMode == ThemeMode.DARK,
                        enabled = settings.themeMode == ThemeMode.DARK,
                        onCheckedChange = { onSettingsChange(settings.copy(oledMode = it)) },
                    )
                }

                Column {
                    Text("Accent colour", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    if (dynamicColorAvailable) {
                        SettingRow(
                            title = "Use device theme",
                            subtitle = "Material You — matches your wallpaper",
                        ) {
                            Switch(
                                checked = settings.useDynamicColor,
                                onCheckedChange = { onSettingsChange(settings.copy(useDynamicColor = it)) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.alpha(if (settings.useDynamicColor && dynamicColorAvailable) 0.4f else 1f),
                    ) {
                        items(Palettes.accents.size) { i ->
                            ColorSwatch(
                                color = Palettes.accents[i],
                                selected = !settings.useDynamicColor && settings.accentIndex == i,
                                onClick = {
                                    onSettingsChange(settings.copy(accentIndex = i, useDynamicColor = false))
                                },
                            )
                        }
                    }
                }

                Column {
                    Text("Touch surface colour", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(Palettes.canvasBackgrounds.size) { hex ->
                            ColorSwatch(
                                color = parseHexColor(Palettes.canvasBackgrounds[hex]) ?: Color.Black,
                                selected = settings.canvasColorHex.equals(Palettes.canvasBackgrounds[hex], ignoreCase = true),
                                onClick = { onSettingsChange(settings.copy(canvasColorHex = Palettes.canvasBackgrounds[hex])) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    var hexText by remember(settings.canvasColorHex) { mutableStateOf(settings.canvasColorHex) }
                    var hexError by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { input ->
                            val cleaned = input.removePrefix("#").take(6)
                            hexText = cleaned
                            val parsed = parseHexColor(cleaned)
                            hexError = parsed == null
                            if (parsed != null) onSettingsChange(settings.copy(canvasColorHex = cleaned))
                        },
                        label = { Text("Hex") },
                        leadingIcon = { Text("#", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        isError = hexError,
                        singleLine = true,
                        modifier = Modifier.width(160.dp),
                    )
                }

                TextButton(onClick = { onSettingsChange(WactabSettings()) }) {
                    Text("Reset settings to default")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    control: @Composable () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.alpha(alpha)) { control() }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.3f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
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
