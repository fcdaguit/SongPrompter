package com.chordflow.prompter.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chordflow.prompter.core.ChordParser

/**
 * Second-screen output for a TV, projector or monitor (HDMI, wireless display / screen
 * mirroring, or a dual-screen setup).
 *
 * While the teleprompter is open and Android reports an external display, that display
 * stops mirroring the phone and shows the lyrics only: no chords, no HUD, no buttons.
 * The phone keeps its normal view, so the leader can still see chords and controls.
 * Leaving the teleprompter, or unplugging the display, puts things back as they were.
 *
 * This is output only. It reads the current lines and active line and never writes to
 * anything, so chord detection and auto-scroll are unaffected.
 */
@Composable
fun SecondScreenLyrics(
    lines: List<ChordParser.SongLine>,
    activeIndex: Int
): State<Boolean> {
    val context = LocalContext.current
    val controller = remember(context) { SecondScreenController(context) }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    SideEffect { controller.update(lines, activeIndex) }

    return controller.connected
}

private class SecondScreenController(private val context: Context) {

    val connected = mutableStateOf(false)

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var presentation: LyricsPresentation? = null
    private var lines: List<ChordParser.SongLine> = emptyList()
    private var activeIndex = 0
    private var started = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = attach()
        override fun onDisplayRemoved(displayId: Int) = attach()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    fun start() {
        if (started) return
        started = true
        try {
            displayManager?.registerDisplayListener(listener, handler)
        } catch (_: Exception) {
        }
        attach()
    }

    fun stop() {
        started = false
        try {
            displayManager?.unregisterDisplayListener(listener)
        } catch (_: Exception) {
        }
        dismiss()
    }

    fun update(newLines: List<ChordParser.SongLine>, newIndex: Int) {
        if (newLines === lines && newIndex == activeIndex) return
        lines = newLines
        activeIndex = newIndex
        presentation?.show(lines, activeIndex)
    }

    /** Show on the external display if there is one; otherwise make sure nothing is showing. */
    private fun attach() {
        if (!started) return
        val target: Display? = try {
            displayManager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        val current = presentation
        if (target == null) {
            dismiss()
            return
        }
        if (current != null && current.display.displayId == target.displayId && current.isShowing) return
        dismiss()
        try {
            val created = LyricsPresentation(context, target)
            created.show()
            created.show(lines, activeIndex)
            presentation = created
            connected.value = true
        } catch (_: Exception) {
            // The display went away between being listed and being used. The phone
            // screen is unaffected; the next display event will try again.
            presentation = null
            connected.value = false
        }
    }

    private fun dismiss() {
        try {
            presentation?.dismiss()
        } catch (_: Exception) {
        }
        presentation = null
        connected.value = false
    }
}

/** The lyrics-only page drawn on the external display. Plain views, no chords. */
private class LyricsPresentation(context: Context, display: Display) : Presentation(context, display) {

    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private var shownLines: List<ChordParser.SongLine>? = null
    private var rows: List<TextView?> = emptyList()
    private var baseSizePx = 48f

    private val green = Color.rgb(0x00, 0xFF, 0x41)
    private val greenBright = Color.rgb(0x39, 0xFF, 0x14)
    private val greenDim = Color.rgb(0x0A, 0x8F, 0x28)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setTitle(WINDOW_TITLE)

        val metrics = context.resources.displayMetrics
        // About nine lyric lines fit the height of the screen, whatever its resolution.
        baseSizePx = metrics.heightPixels / 13f
        val sidePadding = (metrics.widthPixels * 0.06f).toInt()

        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(sidePadding, metrics.heightPixels / 3, sidePadding, metrics.heightPixels)
        }
        scroll = ScrollView(context).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            addView(
                column,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        setContentView(scroll)
    }

    fun show(lines: List<ChordParser.SongLine>, activeIndex: Int) {
        if (!::column.isInitialized) return
        if (lines !== shownLines) rebuild(lines)
        highlight(activeIndex)
    }

    private fun rebuild(lines: List<ChordParser.SongLine>) {
        shownLines = lines
        column.removeAllViews()
        rows = lines.map { line ->
            when {
                // Chord-only and empty lines carry no lyric: just a gap.
                line.lyric.isBlank() -> {
                    column.addView(
                        View(context),
                        LinearLayout.LayoutParams(1, (baseSizePx * 0.6f).toInt())
                    )
                    null
                }

                else -> TextView(context).apply {
                    text = if (line.isSection) line.lyric.uppercase() else line.lyric
                    gravity = Gravity.CENTER_HORIZONTAL
                    typeface = Typeface.MONOSPACE
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        if (line.isSection) baseSizePx * 0.5f else baseSizePx
                    )
                    setTextColor(if (line.isSection) greenDim else green)
                    setPadding(0, (baseSizePx * 0.12f).toInt(), 0, (baseSizePx * 0.12f).toInt())
                    column.addView(
                        this,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            }
        }
    }

    private fun highlight(activeIndex: Int) {
        val lines = shownLines ?: return
        rows.forEachIndexed { index, row ->
            if (row == null || lines[index].isSection) return@forEachIndexed
            row.setTextColor(
                when {
                    index == activeIndex -> greenBright
                    index < activeIndex -> greenDim
                    else -> green
                }
            )
            row.setTypeface(Typeface.MONOSPACE, if (index == activeIndex) Typeface.BOLD else Typeface.NORMAL)
        }
        // Keep the active line a third of the way down the screen, like the phone does.
        val anchor = rows.getOrNull(activeIndex)
            ?: rows.drop(activeIndex.coerceAtLeast(0)).firstOrNull { it != null }
            ?: return
        scroll.post {
            val y = anchor.top - scroll.height / 3
            scroll.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    companion object {
        const val WINDOW_TITLE = "ChordFlowSecondScreen"
    }
}
