package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.Effects
import io.github.akrishna87.mymusic.MusicViewModel
import kotlin.math.roundToInt

private fun hzLabel(hz: Int): String = when {
    hz >= 1000 -> {
        val k = hz / 1000f
        if (k >= 10 || k == k.roundToInt().toFloat()) "${k.roundToInt()}k" else "%.1fk".format(k)
    }
    else -> "$hz"
}

private fun dbLabel(mb: Int): String {
    val db = mb / 100f
    return when {
        db > 0.05f -> "+%.1f".format(db)
        db < -0.05f -> "%.1f".format(db)
        else -> "0"
    }
}

@Composable
fun EqualizerScreen(vm: MusicViewModel) {
    val info = vm.eqInfo
    val settings = vm.eqSettings
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.Violet.deep(0.45f), Palette.Background, Palette.Background)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showEqualizer = false }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close equaliser", modifier = Modifier.size(32.dp))
                }
                Text("Equalizer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = 4.dp))
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = vm::setEqEnabled,
                    enabled = info?.available == true,
                    modifier = Modifier.semantics { contentDescription = "Equalizer on/off" },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Coral, checkedThumbColor = Color.White),
                )
            }

            when {
                info == null -> Note("Starting the player…")
                !info.available -> Note(
                    "This phone doesn't let apps use its built-in equaliser. " +
                        "If it has its own sound settings (often under Settings → Sound → Sound quality and effects), those still apply to My Music.",
                )
                else -> EqControls(vm, info, settings)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(top = 32.dp, start = 8.dp, end = 8.dp), color = Palette.SubText, textAlign = TextAlign.Center)
}

@Composable
private fun ColumnScope.EqControls(vm: MusicViewModel, info: Effects.EqInfo, settings: Effects.Settings) {
    val levels = vm.eqLevels()
    val active = settings.enabled
    val accent = if (active) Palette.Coral else Palette.Faint

    Text(
        if (active) "Shape the sound to your taste. Changes apply straight away." else "Turn the equaliser on to change the sound.",
        color = Palette.SubText,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 16.dp),
    )

    // Presets
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        item {
            PresetChip("Custom", selected = settings.preset !in info.presets.indices, enabled = active) {
                vm.setEqBand(0, levels.firstOrNull() ?: 0)
            }
        }
        itemsIndexed(info.presets) { i, (name, _) ->
            PresetChip(name, selected = settings.preset == i, enabled = active) { vm.selectEqPreset(i) }
        }
    }

    Spacer(Modifier.height(24.dp))

    // dB readout above each band
    Row(Modifier.fillMaxWidth()) {
        levels.forEach { level ->
            Text(dbLabel(level), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = if (active) Palette.Text else Palette.Faint, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(6.dp))
    EqCurve(
        levels = levels,
        min = info.minLevel,
        max = info.maxLevel,
        accent = accent,
        onChange = { band, level -> vm.setEqBand(band, level) },
        modifier = Modifier.fillMaxWidth().height(260.dp),
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        info.bandsHz.forEach { hz ->
            Text(hzLabel(hz) + "Hz", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = Palette.SubText)
        }
    }

    if (info.bassBoost) {
        Spacer(Modifier.height(32.dp))
        Text("Bass boost", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        var dragging by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = dragging ?: (settings.bass / 1000f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { vm.setBassBoost((it * 1000).roundToInt()) }
                dragging = null
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Palette.Highlight),
            modifier = Modifier.semantics { contentDescription = "Bass boost" },
        )
    }

    Spacer(Modifier.height(16.dp))
    TextButton(onClick = vm::resetEq, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Reset to flat", color = Palette.SubText)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PresetChip(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected && enabled) Palette.Coral else Palette.Elevated2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(name, color = if (selected && enabled) Color.Black else Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/**
 * All the bands in one picture: a smooth curve through each band's level with a handle per band.
 * Drag (or tap) anywhere in a band's column to set its level.
 */
@Composable
private fun EqCurve(
    levels: List<Int>,
    min: Int,
    max: Int,
    accent: Color,
    onChange: (band: Int, level: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val n = levels.size.coerceAtLeast(1)
    val span = (max - min).coerceAtLeast(1)
    fun levelAt(y: Float, height: Float, pad: Float): Int {
        val f = ((y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
        // Snap to whole dB steps.
        return (((max - f * span) / 100f).roundToInt() * 100).coerceIn(min, max)
    }
    Canvas(
        modifier
            .semantics { contentDescription = "Equalizer bands" }
            .pointerInput(n, min, max) {
                val pad = 14.dp.toPx()
                detectTapGestures { o ->
                    val band = (o.x / (size.width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                    change(band, levelAt(o.y, size.height.toFloat(), pad))
                }
            }
            .pointerInput(n, min, max) {
                val pad = 14.dp.toPx()
                var band = 0
                detectDragGestures(
                    onDragStart = { o -> band = (o.x / (size.width.toFloat() / n)).toInt().coerceIn(0, n - 1) },
                ) { changeEvent, _ ->
                    change(band, levelAt(changeEvent.position.y, size.height.toFloat(), pad))
                }
            },
    ) {
        val pad = 14.dp.toPx()
        val colW = size.width / n
        fun x(i: Int) = colW * (i + 0.5f)
        fun y(level: Int) = pad + (max - level).toFloat() / span * (size.height - 2 * pad)

        // Zero line and band guides.
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())
        for (i in 0 until n) {
            drawLine(Color.White.copy(alpha = 0.08f), Offset(x(i), pad), Offset(x(i), size.height - pad), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        if (levels.isEmpty()) return@Canvas

        // Smooth curve through the band levels, with a soft fill under it.
        val points = levels.mapIndexed { i, l -> Offset(x(i), y(l)) }
        val curve = Path().apply {
            moveTo(0f, points.first().y)
            lineTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val mid = (a.x + b.x) / 2
                cubicTo(mid, a.y, mid, b.y, b.x, b.y)
            }
            lineTo(size.width, points.last().y)
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(curve, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { p ->
            drawCircle(Color.White, radius = 9.dp.toPx(), center = p)
            drawCircle(accent, radius = 5.dp.toPx(), center = p)
        }
    }
}
