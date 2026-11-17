package com.chaoslab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Modifier.glass(radius: Int = 18, strong: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this
        .clip(shape)
        .background(if (strong) ChaosColors.GlassStrong else ChaosColors.Glass)
        .border(1.dp, ChaosColors.GlassBorder, shape)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = ChaosColors.Neon.copy(alpha = 0.85f),
        fontSize = 11.sp,
        letterSpacing = 2.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** Capsule button. `active` renders it highlighted. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = ChaosColors.Neon,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (active) accent.copy(alpha = 0.22f) else ChaosColors.Glass)
            .border(1.dp, if (active) accent.copy(alpha = 0.9f) else ChaosColors.GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) accent else ChaosColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
fun SurpriseButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(ChaosColors.Violet, ChaosColors.Neon)))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("✦ SURPRISE ME", color = Color(0xFF05060F), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = ChaosColors.Text, fontSize = 13.sp)
            Text(valueText, color = ChaosColors.Neon, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ChaosColors.Neon,
                activeTrackColor = ChaosColors.Neon,
                inactiveTrackColor = ChaosColors.GlassBorder,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = ChaosColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.padding(4.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF05060F),
                checkedTrackColor = ChaosColors.Neon,
                uncheckedThumbColor = ChaosColors.TextDim,
                uncheckedTrackColor = Color(0x22FFFFFF),
                uncheckedBorderColor = ChaosColors.GlassBorder,
            ),
        )
    }
}
