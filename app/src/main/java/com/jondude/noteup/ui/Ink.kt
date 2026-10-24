package com.jondude.noteup.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/*
 * The e-ink look: pure black on white, hard 1.5 dp rules, square corners, serif type,
 * no ripples, no shadows, no animation. Selection is shown by inverting, like an e-reader.
 */
object Ink {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Gray = Color(0xFF6E6E6E)
    val Light = Color(0xFFE4E4E4)
    val Paper = Color(0xFFF7F7F4)
    val Rule = 1.5.dp
    val Serif = FontFamily.Serif
    val Sans = FontFamily.SansSerif
}

@Composable
fun InkTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Ink.Black, onPrimary = Ink.White, secondary = Ink.Black, onSecondary = Ink.White,
        background = Ink.White, onBackground = Ink.Black, surface = Ink.White, onSurface = Ink.Black,
        surfaceVariant = Ink.Light, onSurfaceVariant = Ink.Black, outline = Ink.Black,
    )
    val base = TextStyle(fontFamily = Ink.Serif, color = Ink.Black)
    val type = Typography(
        bodyLarge = base.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = base.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp),
        titleLarge = base.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        labelLarge = base.copy(fontFamily = Ink.Sans, fontSize = 15.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}

/** Click with no ripple — e-ink screens don't animate. */
fun Modifier.inkClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.inkClick(onLongClick: () -> Unit, onClick: () -> Unit): Modifier = composed {
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}

@Composable
fun T(
    text: String,
    size: TextUnit = 16.sp,
    bold: Boolean = false,
    italic: Boolean = false,
    sans: Boolean = false,
    color: Color = Ink.Black,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        modifier = modifier,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = if (sans) Ink.Sans else Ink.Serif,
        color = color,
        maxLines = maxLines,
        lineHeight = (size.value * 1.4f).sp,
    )
}

/** Outlined square button; [on] inverts it (selected/active). */
@Composable
fun InkButton(
    label: String,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    enabled: Boolean = true,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (on) Ink.Black else Ink.White
    val fg = when { !enabled -> Ink.Gray; on -> Ink.White; else -> Ink.Black }
    Box(
        modifier
            .border(Ink.Rule, if (enabled) Ink.Black else Ink.Gray)
            .background(bg)
            .inkClick(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 16.dp, vertical = if (small) 6.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(label, size = if (small) 14.sp else 16.sp, sans = true, bold = on, color = fg, maxLines = 1)
    }
}

/** Borderless toolbar item, inverted when active. */
@Composable
fun ToolItem(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (on) Ink.Black else Ink.White)
            .inkClick(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(label, size = 15.sp, sans = true, bold = on, maxLines = 1,
            color = when { !enabled -> Ink.Light; on -> Ink.White; else -> Ink.Black })
    }
}

@Composable
fun HRule(modifier: Modifier = Modifier, thickness: Dp = Ink.Rule) {
    Box(modifier.fillMaxWidth().height(thickness).background(Ink.Black))
}

@Composable
fun VRule(modifier: Modifier = Modifier) {
    Box(modifier.width(Ink.Rule).background(Ink.Black))
}

@Composable
fun InkField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Box(modifier.border(Ink.Rule, Ink.Black).background(Ink.White).padding(12.dp)) {
        if (value.isEmpty()) T(hint, color = Ink.Gray, italic = true)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = TextStyle(fontFamily = Ink.Serif, fontSize = 17.sp, color = Ink.Black),
            cursorBrush = SolidColor(Ink.Black),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    Row(Modifier.fillMaxWidth().inkClick { onChange(!value) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).border(Ink.Rule, Ink.Black).background(if (value) Ink.Black else Ink.White))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(label, size = 17.sp)
            if (note != null) T(note, size = 13.sp, color = Ink.Gray)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    T(text.uppercase(), size = 12.sp, sans = true, bold = true, color = Ink.Gray,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
fun InkDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth()
                .background(Ink.White).border(2.dp, Ink.Black).padding(24.dp)
        ) {
            T(title, size = 22.sp, bold = true)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** Full-screen black blink, like an e-ink full refresh on page turn. */
object Flash {
    var visible by mutableStateOf(false)

    suspend fun blink() {
        if (!com.jondude.noteup.data.Settings.refreshFlash) return
        visible = true
        kotlinx.coroutines.delay(110)
        visible = false
    }
}

@Composable
fun FlashOverlay() {
    if (Flash.visible) Box(Modifier.fillMaxSize().background(Ink.Black))
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
