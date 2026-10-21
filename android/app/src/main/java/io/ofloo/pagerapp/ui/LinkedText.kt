package io.ofloo.pagerapp.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val linkPattern = Regex(
    """(?:https?://|mailto:|tel:)[^\s<>"']+""",
    RegexOption.IGNORE_CASE,
)

/**
 * Text that makes http(s), mailto and tel URLs tappable instead of showing them as plain text.
 * Trailing sentence punctuation is left outside the link.
 */
@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    textAlign: TextAlign? = null,
    style: TextStyle = TextStyle.Default,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            for (match in linkPattern.findAll(text)) {
                val start = match.range.first
                val end = match.range.last + 1
                val raw = match.value
                val url = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                append(text.substring(cursor, start))
                if (url.isNotEmpty()) {
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) { append(url) }
                    append(text.substring(start + url.length, end))
                } else {
                    append(raw)
                }
                cursor = end
            }
            append(text.substring(cursor))
        }
    }
    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        style = style,
    )
}

/** Same text, but selectable; used in the page detail dialog. */
@Composable
fun SelectableLinkedText(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = TextUnit.Unspecified) {
    SelectionContainer {
        LinkedText(text = text, modifier = modifier, fontSize = fontSize.ifSpecified())
    }
}

private fun TextUnit.ifSpecified(): TextUnit = if (this == TextUnit.Unspecified) 16.sp else this
