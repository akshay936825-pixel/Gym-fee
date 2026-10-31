@file:OptIn(ExperimentalMaterial3Api::class)

package com.gym.hisab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gym.hisab.R
import com.gym.hisab.core.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import com.gym.hisab.ui.theme.sem

class Haptics(private val h: HapticFeedback) {
    fun tick() = h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun success() = h.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    return remember(h) { Haptics(h) }
}

/** Scaffold for detail screens: back arrow + title. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { actions() },
            )
        },
        content = content,
    )
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface, border = border) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface, border = border) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.onSurface) {
    AppCard(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = sem.muted)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = accent, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
fun BigButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, secondary: Boolean = false,
) {
    val hap = rememberHaptics()
    val click = { hap.tick(); onClick() }
    val m = modifier.fillMaxWidth().height(54.dp)
    if (secondary) {
        OutlinedButton(onClick = click, enabled = enabled, modifier = m, shape = MaterialTheme.shapes.medium) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Button(onClick = click, enabled = enabled, modifier = m, shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors()) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun Avatar(name: String, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold, fontSize = (size / 2.4).sp)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.16f)) {
        Text(text, color = color, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** Horizontal single-choice chips: pairs of (value, label). */
@Composable
fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val hap = rememberHaptics()
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            FilterChip(
                selected = value == selected,
                onClick = { hap.tick(); onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
fun MoneyInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.all { it.isDigit() || it == '.' || it == ',' }) onChange(v) },
        label = { Text(label) },
        prefix = { Text("₹ ") },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
fun TextInput(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, error: Boolean = false, singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, isError = error,
        singleLine = singleLine, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = sem.muted, textAlign = TextAlign.Center)
    }
}

/**
 * Lightweight grouped bar chart drawn on a Canvas (no chart library = smooth on cheap phones).
 * [groups] holds one list of values per label; [colors] has one colour per series.
 */
@Composable
fun BarChart(groups: List<List<Long>>, labels: List<String>, colors: List<Color>, modifier: Modifier = Modifier, height: Int = 150) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(groups) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(550))
    }
    val maxV = (groups.flatten().maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    val line = sem.line
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = groups.size.coerceAtLeast(1)
            val slot = size.width / n
            val series = (groups.firstOrNull()?.size ?: 1).coerceAtLeast(1)
            val barW = (slot * 0.62f) / series
            drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            groups.forEachIndexed { i, g ->
                val startX = i * slot + (slot - barW * series) / 2f
                g.forEachIndexed { s, v ->
                    val h = (v / maxV) * (size.height - 6f) * progress.value
                    drawRoundRect(
                        color = colors[s % colors.size],
                        topLeft = Offset(startX + s * barW, size.height - h),
                        size = Size(barW - 4f, h.coerceAtLeast(if (v > 0) 3f else 0f)),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = sem.muted)
            }
        }
    }
}

@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text("$label: ${Dates.pretty(date)}", maxLines = 1)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}
