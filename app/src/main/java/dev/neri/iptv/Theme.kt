package dev.neri.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

// Tokens do tema escuro do IPTVnator (apps/web/src/m3-theme.scss).
object C {
    val Shell = Color(0xFF0D0F12)
    val Rail = Color(0xFF111318)
    val Content = Color(0xFF161A22)
    val Widget = Color(0xFF1E2330)
    val WidgetHeader = Color(0xFF232840)
    val CardHover = Color(0xFF252B3A)
    val OnSurface = Color(0xFFE6E1E5)
    val Heading = Color(0xFFD8DCE8)
    val Body = Color(0xFF8B93A8)
    val Muted = Color(0xFF5C6478)
    val Selection = Color(0xFF78ADFF)
    val SelectionSurface = Selection.copy(alpha = 0.18f)
    val SelectionBorder = Selection.copy(alpha = 0.62f)
    val FocusRing = Color(0xFF8CBAFF)
    val Live = Color(0xFFFF8F8F)
    val Success = Color(0xFF4ADE80)
    val Separator = Color(0x24FFFFFF)
    val HeaderBorder = Color(0x0DFFFFFF)
    val SearchBg = Color(0x0DFFFFFF)
    val SearchBorder = Color(0x17FFFFFF)
    val ProviderM3u = Color(0xFFF59E0B)
}

val TvColors = darkColorScheme(
    primary = C.Selection,
    onPrimary = Color(0xFF071225),
    background = C.Shell,
    onBackground = C.OnSurface,
    surface = C.Shell,
    onSurface = C.OnSurface,
    surfaceVariant = C.Widget,
    onSurfaceVariant = C.Body,
    border = C.Separator,
)

object T {
    val Title = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = C.Heading)
    val Subtitle = TextStyle(fontSize = 13.sp, color = C.Body)
    val Label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = C.Muted)
    val Row = TextStyle(fontSize = 14.sp, color = C.Body)
    val ChannelName = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Heading)
    val Meta = TextStyle(fontSize = 11.sp, color = C.Body)
    val Tag = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
}

/**
 * Linha focável no estilo das listas do IPTVnator: fundo transparente, tinta azul
 * quando selecionada e anel de foco claro (no lugar do :focus-visible).
 */
@Composable
fun TvRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    radius: Int = 6,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) C.SelectionSurface else Color.Transparent,
            contentColor = if (selected) C.Selection else C.Body,
            focusedContainerColor = if (selected) C.Selection.copy(alpha = 0.28f) else C.CardHover,
            focusedContentColor = C.Heading,
            pressedContainerColor = C.CardHover,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, C.SelectionBorder), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, C.FocusRing), shape = shape),
        ),
    ) {
        content()
    }
}

// Botão "pílula" usado nos cabeçalhos (como .g-jump / .g-iconbtn do EPG).
@Composable
fun Pill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    horizontalPadding: Dp = 14.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) C.SelectionSurface else C.WidgetHeader,
            contentColor = if (active) C.Selection else C.Body,
            focusedContainerColor = C.CardHover,
            focusedContentColor = C.Heading,
            disabledContainerColor = C.WidgetHeader.copy(alpha = 0.5f),
            disabledContentColor = C.Muted,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (active) C.SelectionBorder else C.Separator), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, C.FocusRing), shape = shape),
        ),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(PaddingValues(horizontal = horizontalPadding)).height(34.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            ProvideText(TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)) { content() }
        }
    }
}

@Composable
private fun ProvideText(style: TextStyle, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.tv.material3.LocalTextStyle provides style,
        content = content,
    )

// Campo de texto no estilo da busca do IPTVnator (fundo 5%, borda 9%, anel azul no foco).
// Na TV o teclado abre assim que um campo editável ganha foco e passa a capturar o direcional;
// por isso o campo fica "fechado" (um botão) e só vira editável quando o usuário aperta OK.
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(6.dp)
    val textStyle = TextStyle(fontSize = 15.sp, color = C.Heading)
    val finish = {
        returnFocus = true
        editing = false
    }

    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label.uppercase(), style = T.Label)
            Spacer(Modifier.height(6.dp))
        }
        if (!editing) {
            Surface(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth().focusRequester(boxFocus),
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = C.SearchBg,
                    contentColor = C.Heading,
                    focusedContainerColor = C.SearchBg,
                    focusedContentColor = C.Heading,
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, C.SearchBorder), shape = shape),
                    focusedBorder = Border(BorderStroke(2.dp, C.FocusRing), shape = shape),
                ),
            ) {
                Text(
                    value.ifEmpty { placeholder },
                    style = textStyle.copy(color = if (value.isEmpty()) C.Muted else C.Heading),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else {
            var hadFocus by remember { mutableStateOf(false) }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(C.Selection),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onDone = { finish(); onDone() },
                    onSearch = { finish(); onDone() },
                    onGo = { finish(); onDone() },
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    // Saiu do campo por qualquer caminho: volta a ser botão.
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true else if (hadFocus) editing = false
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            else -> false
                        }
                    }
                    .background(C.SearchBg, shape)
                    .border(2.dp, C.FocusRing, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = C.Muted))
                        inner()
                    }
                },
            )
            LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
        }
    }
    // Depois do OK/Buscar no teclado, o foco volta para o próprio campo.
    LaunchedEffect(editing) {
        if (!editing && returnFocus) {
            returnFocus = false
            withFrameNanos { }
            runCatching { boxFocus.requestFocus() }
        }
    }
}
