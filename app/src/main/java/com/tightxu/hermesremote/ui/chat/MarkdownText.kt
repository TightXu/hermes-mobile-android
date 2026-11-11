package com.tightxu.hermesremote.ui.chat

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.tightxu.hermesremote.ui.theme.TextPrimary
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * One Markwon instance per chat page (NOT per message) — parsing spans is
 * expensive and every assistant bubble shares the configuration anyway.
 * Colors are baked in at creation; the accent rarely changes mid-chat and a
 * stale link tint is preferable to rebuilding the pipeline per message.
 */
@Composable
fun rememberMarkwon(context: Context): Markwon {
    val accent = MaterialTheme.colorScheme.primary
    return remember(context) { buildMarkwon(context, accent.toArgb()) }
}

private fun buildMarkwon(context: Context, accentColor: Int): Markwon =
    Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .linkColor(accentColor)
                    // Inline code: accent tint so it reads as "typed".
                    .codeTextColor(accentColor)
                    .codeBackgroundColor(accentColor and 0x00FFFFFF or 0x2A000000)
                    // Fenced code blocks: monospace on a dark plate, wraps
                    // (never truncated) because the TextView keeps word-wrap.
                    .codeBlockTextColor(TextPrimary.toArgb())
                    .codeBlockBackgroundColor(0xFF151922.toInt())
            }
        })
        .build()

/**
 * Assistant bubble content: a selectable TextView rendered through Markwon.
 * `update` re-renders only when the text actually changed (the LazyColumn
 * recomposes bubbles on every poll merge); a parse failure falls back to the
 * raw text so one pathological message can't blank the list.
 */
@Composable
fun MarkdownText(
    markwon: Markwon,
    text: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(true)
                linksClickable = true
                // AFTER setTextIsSelectable (which would otherwise install
                // ArrowKeyMovementMethod): links must stay clickable.
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(TextPrimary.toArgb())
                textSize = 14f
                val padH = (12 * resources.displayMetrics.density).toInt()
                val padV = (8 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
            }
        },
        update = { tv ->
            if (tv.tag != text) {
                tv.tag = text
                try {
                    markwon.setMarkdown(tv, text)
                } catch (_: Exception) {
                    tv.text = text // plain-text fallback, never crash the list
                }
            }
        },
        modifier = modifier,
    )
}
