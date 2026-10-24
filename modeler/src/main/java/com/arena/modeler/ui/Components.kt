package com.arena.modeler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlin.math.roundToInt

/** The palette: Blender-ish greys with a blue accent, readable on OLED screens. */
object Palette {
    val bg = Color(0xFF0D0F14)
    val panel = Color(0xFF171A21)
    val panelHigh = Color(0xFF1E222B)
    val stroke = Color(0xFF2A2F3A)
    val text = Color(0xFFE6E9EF)
    val muted = Color(0xFF98A0AE)
    val accent = Color(0xFF4C8DFF)
    val accentSoft = Color(0x334C8DFF)
    val good = Color(0xFF57D18B)
    val warn = Color(0xFFF2B33D)
    val bad = Color(0xFFF0666B)
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text.uppercase(), color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (trailing != null) {
            Text(
                "  $trailing", color = Palette.muted.copy(alpha = 0.7f), fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
        } else {
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
fun ChipButton(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.accentSoft else Palette.panelHigh)
            .border(1.dp, if (selected) Palette.accent else Palette.stroke, RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (selected) Palette.text else Palette.muted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (!enabled) Palette.panelHigh.copy(alpha = 0.5f)
                else if (primary) Palette.accent else Palette.panelHigh
            )
            .border(
                1.dp,
                if (primary && enabled) Palette.accent else Palette.stroke,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (!enabled) Palette.muted.copy(alpha = 0.6f) else Palette.text,
            fontSize = 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String = { "%.2f".format(it) },
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.width(62.dp))
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier.weight(1f).height(30.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Palette.accent,
                    activeTrackColor = Palette.accent,
                    inactiveTrackColor = Palette.stroke
                )
            )
            Text(
                format(value), color = Palette.text, fontSize = 12.sp,
                modifier = Modifier.width(58.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (checked) Palette.accent else Palette.stroke)
                .clickable { onChange(!checked) }
        ) {
            Box(
                Modifier
                    .padding(3.dp)
                    .size(16.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    password: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
    onValueChange: (String) -> Unit
) {
    val placeholderContent: (@Composable () -> Unit)? = if (placeholder != null) {
        { Text(placeholder, color = Palette.muted.copy(alpha = 0.6f)) }
    } else null
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            placeholder = placeholderContent,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 13.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.panelHigh,
                unfocusedContainerColor = Palette.panelHigh,
                disabledContainerColor = Palette.panelHigh,
                focusedIndicatorColor = Palette.accent,
                unfocusedIndicatorColor = Palette.stroke,
                focusedTextColor = Palette.text,
                unfocusedTextColor = Palette.text,
                cursorColor = Palette.accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Numeric field that accepts a decimal point and only commits valid numbers. */
@Composable
fun NumberField(label: String, value: Float, onChange: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else "%.3f".format(value)) }
    Column(Modifier.width(96.dp)) {
        Text(label, color = Palette.muted, fontSize = 11.sp)
        TextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                raw.replace(',', '.').toFloatOrNull()?.let(onChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 13.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.panelHigh,
                unfocusedContainerColor = Palette.panelHigh,
                focusedIndicatorColor = Palette.accent,
                unfocusedIndicatorColor = Palette.stroke,
                focusedTextColor = Palette.text,
                unfocusedTextColor = Palette.text
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(3.dp)
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else Palette.stroke,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
    )
}

@Composable
fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Palette.stroke.copy(alpha = 0f), Palette.stroke, Palette.stroke.copy(alpha = 0f))
                )
            )
    )
}

@Composable
fun Hint(text: String) {
    Text(text, color = Palette.muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun RowWrap(horizontalGap: Int = 6, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(horizontalGap.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
