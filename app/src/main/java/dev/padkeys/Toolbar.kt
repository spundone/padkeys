package dev.padkeys

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** The tools in the suggestion strip when there are no suggestions (Gboard's toolbar). */
enum class Tool(val icon: Icons.Id, val label: String) {
    EMOJI(Icons.Id.EMOJI, "Emoji"),
    CLIPBOARD(Icons.Id.CLIPBOARD, "Clipboard"),
    EDIT(Icons.Id.EDIT, "Text editing"),
    ONE_HAND(Icons.Id.ONE_HAND, "One-handed mode"),
    VOICE(Icons.Id.MIC, "Voice typing"),
}

/** What the strip's toolbar and paste chip do, implemented by the input method service. */
interface ToolbarListener {
    fun onTool(tool: Tool)
    /** The "paste what you just copied" chip was tapped. */
    fun onClipChip(text: String)
}

/**
 * Line icons drawn on a 24-unit grid (like Material Symbols), so the keyboard's chrome doesn't depend on what the
 * font has. Shared by the toolbar and the emoji, clipboard and editing panels.
 */
object Icons {
    enum class Id {
        EMOJI, CLIPBOARD, EDIT, ONE_HAND, MIC, BACKSPACE, PIN, PINNED, CLOSE, LEFT, RIGHT, UP, DOWN, EXPAND, SWAP,
        CLOCK, KEYBOARD, SELECT_ALL, COPY, CUT, PASTE, HOME, END, FORWARD_DELETE, RETURN,
    }

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val oval = RectF()

    /** Draws [id] centered at ([cx], [cy]), [size] px across. */
    fun draw(c: Canvas, id: Id, cx: Float, cy: Float, size: Float, color: Int) {
        val p = path
        p.reset()
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        var fill = false
        when (id) {
            Id.RETURN -> {
                p.moveTo(19f, 5f); p.lineTo(19f, 11f); p.quadTo(19f, 15f, 15f, 15f); p.lineTo(5f, 15f)
                p.moveTo(9.5f, 10.5f); p.lineTo(5f, 15f); p.lineTo(9.5f, 19.5f)
            }
            Id.EMOJI -> {
                p.addCircle(12f, 12f, 9f, Path.Direction.CW)
                p.addCircle(9f, 10f, 0.6f, Path.Direction.CW); p.addCircle(15f, 10f, 0.6f, Path.Direction.CW)
                oval.set(7.5f, 9.5f, 16.5f, 17f); p.addArc(oval, 25f, 130f)
            }
            Id.CLIPBOARD -> {
                p.moveTo(9f, 4.5f); p.lineTo(6.5f, 4.5f); p.quadTo(5f, 4.5f, 5f, 6f); p.lineTo(5f, 19.5f)
                p.quadTo(5f, 21f, 6.5f, 21f); p.lineTo(17.5f, 21f); p.quadTo(19f, 21f, 19f, 19.5f); p.lineTo(19f, 6f)
                p.quadTo(19f, 4.5f, 17.5f, 4.5f); p.lineTo(15f, 4.5f)
                oval.set(9f, 3f, 15f, 6.5f); p.addRoundRect(oval, 1.2f, 1.2f, Path.Direction.CW)
            }
            Id.PASTE -> {
                p.moveTo(9f, 4.5f); p.lineTo(6.5f, 4.5f); p.quadTo(5f, 4.5f, 5f, 6f); p.lineTo(5f, 19.5f)
                p.quadTo(5f, 21f, 6.5f, 21f); p.lineTo(11f, 21f)
                p.moveTo(15f, 4.5f); p.lineTo(17.5f, 4.5f); p.quadTo(19f, 4.5f, 19f, 6f); p.lineTo(19f, 10f)
                oval.set(9f, 3f, 15f, 6.5f); p.addRoundRect(oval, 1.2f, 1.2f, Path.Direction.CW)
                oval.set(13f, 12f, 21f, 21f); p.addRoundRect(oval, 1.2f, 1.2f, Path.Direction.CW)
            }
            Id.EDIT -> {
                // A text cursor (I-beam) between two small arrows.
                p.moveTo(12f, 5f); p.lineTo(12f, 19f)
                p.moveTo(9.5f, 4f); p.quadTo(12f, 4f, 12f, 6f); p.quadTo(12f, 4f, 14.5f, 4f)
                p.moveTo(9.5f, 20f); p.quadTo(12f, 20f, 12f, 18f); p.quadTo(12f, 20f, 14.5f, 20f)
                p.moveTo(6f, 9f); p.lineTo(3f, 12f); p.lineTo(6f, 15f)
                p.moveTo(18f, 9f); p.lineTo(21f, 12f); p.lineTo(18f, 15f)
            }
            Id.ONE_HAND -> {
                // A screen with the keyboard pulled to one side.
                oval.set(4f, 3f, 20f, 21f); p.addRoundRect(oval, 2.5f, 2.5f, Path.Direction.CW)
                oval.set(10f, 12f, 17.5f, 18.5f); p.addRoundRect(oval, 1.2f, 1.2f, Path.Direction.CW)
                p.moveTo(8.5f, 13.5f); p.lineTo(6.5f, 15.25f); p.lineTo(8.5f, 17f)
            }
            Id.MIC -> {
                oval.set(9f, 3f, 15f, 15f); p.addRoundRect(oval, 3f, 3f, Path.Direction.CW)
                oval.set(5.5f, 5f, 18.5f, 17.5f); p.addArc(oval, 0f, 180f)
                p.moveTo(12f, 17.5f); p.lineTo(12f, 21f)
            }
            Id.BACKSPACE, Id.FORWARD_DELETE -> {
                p.moveTo(8f, 5f); p.lineTo(19f, 5f); p.quadTo(21f, 5f, 21f, 7f); p.lineTo(21f, 17f)
                p.quadTo(21f, 19f, 19f, 19f); p.lineTo(8f, 19f); p.lineTo(2.5f, 12f); p.close()
                p.moveTo(11.5f, 9.5f); p.lineTo(16.5f, 14.5f); p.moveTo(16.5f, 9.5f); p.lineTo(11.5f, 14.5f)
                if (id == Id.FORWARD_DELETE) { c.save(); c.scale(-1f, 1f, cx, cy) }
                stroke(c, cx, cy, size)
                if (id == Id.FORWARD_DELETE) c.restore()
                return
            }
            Id.PIN, Id.PINNED -> {
                p.moveTo(9f, 4f); p.lineTo(15f, 4f); p.moveTo(10f, 4f); p.lineTo(10f, 10f); p.lineTo(7f, 14f)
                p.lineTo(17f, 14f); p.lineTo(14f, 10f); p.lineTo(14f, 4f)
                p.moveTo(12f, 14f); p.lineTo(12f, 20.5f)
                fill = id == Id.PINNED
            }
            Id.CLOSE -> { p.moveTo(6f, 6f); p.lineTo(18f, 18f); p.moveTo(18f, 6f); p.lineTo(6f, 18f) }
            Id.LEFT -> { p.moveTo(20f, 12f); p.lineTo(4f, 12f); p.moveTo(10f, 6f); p.lineTo(4f, 12f); p.lineTo(10f, 18f) }
            Id.RIGHT -> { p.moveTo(4f, 12f); p.lineTo(20f, 12f); p.moveTo(14f, 6f); p.lineTo(20f, 12f); p.lineTo(14f, 18f) }
            Id.UP -> { p.moveTo(12f, 20f); p.lineTo(12f, 4f); p.moveTo(6f, 10f); p.lineTo(12f, 4f); p.lineTo(18f, 10f) }
            Id.DOWN -> { p.moveTo(12f, 4f); p.lineTo(12f, 20f); p.moveTo(6f, 14f); p.lineTo(12f, 20f); p.lineTo(18f, 14f) }
            Id.HOME -> { p.moveTo(4f, 5f); p.lineTo(4f, 19f); p.moveTo(20f, 12f); p.lineTo(8f, 12f); p.moveTo(13f, 7f); p.lineTo(8f, 12f); p.lineTo(13f, 17f) }
            Id.END -> { p.moveTo(20f, 5f); p.lineTo(20f, 19f); p.moveTo(4f, 12f); p.lineTo(16f, 12f); p.moveTo(11f, 7f); p.lineTo(16f, 12f); p.lineTo(11f, 17f) }
            Id.EXPAND -> {
                p.moveTo(14f, 4f); p.lineTo(20f, 4f); p.lineTo(20f, 10f); p.moveTo(20f, 4f); p.lineTo(13.5f, 10.5f)
                p.moveTo(10f, 20f); p.lineTo(4f, 20f); p.lineTo(4f, 14f); p.moveTo(4f, 20f); p.lineTo(10.5f, 13.5f)
            }
            Id.SWAP -> {
                p.moveTo(4f, 8f); p.lineTo(19f, 8f); p.moveTo(15f, 4f); p.lineTo(19f, 8f); p.lineTo(15f, 12f)
                p.moveTo(20f, 16f); p.lineTo(5f, 16f); p.moveTo(9f, 12f); p.lineTo(5f, 16f); p.lineTo(9f, 20f)
            }
            Id.CLOCK -> { p.addCircle(12f, 12f, 9f, Path.Direction.CW); p.moveTo(12f, 7f); p.lineTo(12f, 12f); p.lineTo(15.5f, 14f) }
            Id.KEYBOARD -> {
                oval.set(3f, 6f, 21f, 18f); p.addRoundRect(oval, 2f, 2f, Path.Direction.CW)
                for (x in listOf(7f, 10f, 13f, 16f)) { p.addCircle(x, 9.5f, 0.4f, Path.Direction.CW); p.addCircle(x, 12.3f, 0.4f, Path.Direction.CW) }
                p.moveTo(8.5f, 15f); p.lineTo(15.5f, 15f)
            }
            Id.SELECT_ALL -> {
                oval.set(4f, 4f, 20f, 20f); p.addRoundRect(oval, 2f, 2f, Path.Direction.CW)
                oval.set(8f, 8f, 16f, 16f); p.addRect(oval, Path.Direction.CW)
            }
            Id.COPY -> {
                oval.set(8f, 8f, 20f, 20f); p.addRoundRect(oval, 2f, 2f, Path.Direction.CW)
                p.moveTo(16f, 5.5f); p.quadTo(16f, 4f, 14.5f, 4f); p.lineTo(5.5f, 4f); p.quadTo(4f, 4f, 4f, 5.5f)
                p.lineTo(4f, 14.5f); p.quadTo(4f, 16f, 5.5f, 16f)
            }
            Id.CUT -> {
                p.addCircle(6.5f, 17.5f, 2.8f, Path.Direction.CW); p.addCircle(17.5f, 17.5f, 2.8f, Path.Direction.CW)
                p.moveTo(8.5f, 15.5f); p.lineTo(18f, 4f); p.moveTo(15.5f, 15.5f); p.lineTo(6f, 4f)
            }
        }
        if (fill) paint.style = Paint.Style.FILL_AND_STROKE
        stroke(c, cx, cy, size)
    }

    private fun stroke(c: Canvas, cx: Float, cy: Float, size: Float) {
        c.save()
        c.translate(cx - size / 2, cy - size / 2)
        c.scale(size / 24f, size / 24f)
        c.drawPath(path, paint)
        c.restore()
    }
}
