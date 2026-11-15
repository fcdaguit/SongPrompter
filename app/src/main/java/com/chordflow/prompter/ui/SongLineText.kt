package com.chordflow.prompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chordflow.prompter.core.ChordParser

/**
 * Draws one song line in any of the three display modes. Shared by the teleprompter
 * and the Song Editor preview so both always agree.
 *
 *  - showChords = false            Mode A, lyrics only
 *  - showChords = true, !inline    Mode B, chords above the syllable they land on
 *  - showChords = true, inline     Mode C, [G]inline chords
 *
 * This is rendering only. It reads the line and never changes it, so chord detection,
 * trigger mapping and auto-scroll behave the same whichever mode is on screen.
 * The chords it draws are whatever is in [line] — already in the transposed key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongLineText(
    line: ChordParser.SongLine,
    showChords: Boolean,
    inline: Boolean,
    lyricSp: Float,
    chordSp: Float,
    lyricColor: Color,
    chordColor: Color,
    modifier: Modifier = Modifier,
    lyricWeight: FontWeight = FontWeight.Normal,
    centered: Boolean = false
) {
    // --- Mode A, or a line that simply has no chords --------------------------
    if (!showChords || line.chords.isEmpty()) {
        Text(
            text = line.lyric,
            fontSize = lyricSp.sp,
            lineHeight = (lyricSp * 1.33f).sp,
            fontWeight = lyricWeight,
            color = lyricColor,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = modifier
        )
        return
    }

    // --- Instrumental run: chords with no lyric underneath --------------------
    if (line.lyric.isBlank()) {
        Text(
            text = line.chords.joinToString("   "),
            fontSize = chordSp.sp,
            lineHeight = (chordSp * 1.33f).sp,
            fontWeight = FontWeight.Bold,
            color = chordColor,
            modifier = modifier
        )
        return
    }

    // --- Mode C: [G]Amazing [D]grace ------------------------------------------
    if (inline) {
        val text = remember(line, chordSp, chordColor) {
            buildAnnotatedString {
                ChordParser.segments(line).forEach { seg ->
                    if (seg.chord.isNotEmpty()) {
                        withStyle(
                            SpanStyle(
                                color = chordColor,
                                fontSize = chordSp.sp,
                                fontWeight = FontWeight.Bold
                            )
                        ) { append("[" + seg.chord + "]") }
                    }
                    append(seg.text)
                }
            }
        }
        Text(
            text = text,
            fontSize = lyricSp.sp,
            lineHeight = (maxOf(lyricSp, chordSp) * 1.33f).sp,
            fontWeight = lyricWeight,
            color = lyricColor,
            modifier = modifier
        )
        return
    }

    // --- Mode B: each chord sits over the syllable it lands on ----------------
    // Built from word blocks rather than padded spaces, so chords stay over the right
    // syllable at any pair of font sizes and when a long line wraps on a phone.
    val words = remember(line) { ChordParser.words(line) }
    val wordGap = with(LocalDensity.current) { (lyricSp * 0.6f).sp.toDp() }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(wordGap),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        words.forEach { word ->
            Row(verticalAlignment = Alignment.Bottom) {
                word.forEach { piece ->
                    Column {
                        Text(
                            text = piece.chord.ifEmpty { " " },
                            fontSize = chordSp.sp,
                            lineHeight = (chordSp * 1.15f).sp,
                            fontWeight = FontWeight.Bold,
                            color = chordColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = if (piece.chord.isEmpty()) Modifier else Modifier.padding(end = 4.dp)
                        )
                        Text(
                            text = piece.text.ifEmpty { " " },
                            fontSize = lyricSp.sp,
                            lineHeight = (lyricSp * 1.2f).sp,
                            fontWeight = lyricWeight,
                            color = lyricColor,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
