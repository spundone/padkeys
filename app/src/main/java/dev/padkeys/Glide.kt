package dev.padkeys

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How a glided word is capitalized, from Shift / Caps Lock when the gesture started. */
enum class GlideCase { LOWER, CAPITALIZED, UPPER }

/**
 * Glide (swipe) typing on the letter keys, Gboard-style. The [KeyboardView] hands a touch that starts on a letter
 * to [start] and its moves to [move]; once the finger has left the first key for another letter (sideways or down
 * by half a key, or up by most of a key so a short flick up still types the shifted letter), the touch is a gesture:
 * [move] returns true and the view stops treating it as a tap or flick. On lift the view calls [finish], which decodes
 * the path ([SwipeDecoder]) and reports the best words to [listener].
 *
 * It also draws the fading trail over the keys ([draw]) and a floating preview of the current best guess above the
 * finger ([drawPreview], decoded every [PREVIEW_MS] off the main thread).
 */
class Glide(private val view: KeyboardView, private val keys: () -> List<KeyboardView.Key>) {

    /** Receives the decoded words (best first, already capitalized). Called on the main thread. */
    fun interface Listener { fun onGlide(words: List<String>) }

    var listener: Listener? = null
    /** Glide typing is on (setting, and the field isn't a password). */
    var enabled = false
        set(v) { field = v; if (v) GlideWords.warm() else cancel() }
    var showTrail = true

    /** A touch is being watched (it started on a letter). */
    var tracking = false
        private set
    /** That touch became a gesture. */
    var active = false
        private set

    private val density = view.resources.displayMetrics.density
    private var startKey: KeyboardView.Key? = null
    private var case = GlideCase.LOWER
    private var kb: SwipeDecoder.Keyboard? = null

    // The path, in view pixels.
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var n = 0

    // ---------------------------------------------------------------- touch

    fun start(x: Float, y: Float, k: KeyboardView.Key, c: GlideCase) {
        if (!enabled || !k.isLetter) return
        tracking = true; active = false
        startKey = k; case = c; n = 0
        preview = null; previewAsked = 0L; generation++
        trailN = 0
        add(x, y)
    }

    /** The finger moved; true once (and while) the touch is a gesture. */
    fun move(x: Float, y: Float): Boolean {
        if (!tracking) return false
        add(x, y)
        if (!active && becomesGesture(x, y)) {
            active = true
            kb = keyboard()
            GlideWords.warm()
            // The trail starts at the touch-down point.
            for (i in 0 until n) trailAdd(xs[i], ys[i])
        }
        if (active) {
            trailAdd(x, y)
            askPreview()
        }
        return active
    }

    /** The finger has slid a third of a key or more from where it landed. */
    fun moved(): Boolean {
        val s = startKey ?: return false
        if (n < 2) return false
        return abs(xs[n - 1] - xs[0]) > s.rect.width() / 3 || abs(ys[n - 1] - ys[0]) > s.rect.height() / 3
    }

    private fun becomesGesture(x: Float, y: Float): Boolean {
        val s = startKey ?: return false
        val k = keys().firstOrNull { it.isLetter && it.rect.contains(x, y) } ?: return false
        if (k === s) return false
        val dx = x - xs[0]; val dy = y - ys[0]
        val w = s.rect.width(); val h = s.rect.height()
        return abs(dx) >= w * 0.5f || dy >= h * 0.5f || -dy >= h * 0.9f
    }

    /** The finger lifted after a gesture: decode and report. */
    fun finish() {
        if (!active) { cancel(); return }
        tracking = false; active = false
        generation++
        preview = null
        trailEnd = SystemClock.uptimeMillis()
        view.postInvalidateOnAnimation()
        val d = GlideWords.decoder ?: return
        val k = kb ?: return
        val t0 = SystemClock.elapsedRealtimeNanos()
        val found = d.decode(xs, ys, n, k, Predict.learnedCounts())
        if (BuildConfig.DEBUG) Log.d("PadKeysGlide", "decode %.1f ms, %d points: %s".format(
            (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, n, found))
        val words = found.map { cased(it.word, case) }.distinct()
        if (words.isNotEmpty()) listener?.onGlide(words)
    }

    /** Forget the touch (not a gesture after all, or the keyboard was reset). */
    fun cancel() {
        if (active) { trailEnd = SystemClock.uptimeMillis(); view.postInvalidateOnAnimation() }
        tracking = false; active = false; preview = null; generation++
    }

    private fun add(x: Float, y: Float) {
        if (n > 0 && abs(xs[n - 1] - x) < 0.5f && abs(ys[n - 1] - y) < 0.5f) return
        if (n == xs.size) { xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2) }
        xs[n] = x; ys[n] = y; n++
    }

    /** The letter keys' centers and size, for the decoder. */
    private fun keyboard(): SwipeDecoder.Keyboard? {
        val cx = FloatArray(26) { Float.NaN }; val cy = FloatArray(26) { Float.NaN }
        var w = 0f; var h = 0f; var count = 0
        for (k in keys()) {
            if (!k.isLetter) continue
            val c = k.label[0].lowercaseChar()
            if (c !in 'a'..'z') continue
            cx[c - 'a'] = k.rect.centerX(); cy[c - 'a'] = k.rect.centerY()
            w += k.rect.width(); h += k.rect.height(); count++
        }
        if (count < 20) return null
        return SwipeDecoder.Keyboard(cx, cy, w / count, h / count)
    }

    // ---------------------------------------------------------------- preview of the best guess

    @Volatile private var preview: String? = null
    private var previewAsked = 0L
    @Volatile private var previewBusy = false
    /** Bumped for each new gesture, so a late preview from an older one is dropped. */
    @Volatile private var generation = 0

    private fun askPreview() {
        val now = SystemClock.uptimeMillis()
        if (previewBusy || now - previewAsked < PREVIEW_MS || n < 4) return
        val d = GlideWords.decoder ?: return
        val k = kb ?: return
        previewAsked = now
        previewBusy = true
        val px = xs.copyOf(n); val py = ys.copyOf(n); val count = n
        val gen = generation
        val c = case
        val learned = HashMap(Predict.learnedCounts())
        GlideWords.exec.execute {
            val best = try { d.decode(px, py, count, k, learned, 1).firstOrNull()?.word } catch (_: Exception) { null }
            view.post {
                previewBusy = false
                if (gen == generation && active) {
                    preview = best?.let { cased(it, c) }
                    view.invalidate(); (view.parent as? View)?.invalidate()
                }
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var tx = FloatArray(512)
    private var ty = FloatArray(512)
    private var tt = LongArray(512)
    private var trailN = 0
    private var trailEnd = 0L

    private fun trailAdd(x: Float, y: Float) {
        if (trailN == tx.size) {
            // Keep the newer half; older points have faded anyway.
            val h = trailN / 2
            System.arraycopy(tx, h, tx, 0, trailN - h); System.arraycopy(ty, h, ty, 0, trailN - h)
            System.arraycopy(tt, h, tt, 0, trailN - h)
            trailN -= h
        }
        tx[trailN] = x; ty[trailN] = y; tt[trailN] = SystemClock.uptimeMillis(); trailN++
    }

    /**
     * The trail: a stroke in the theme's primary color (grey in the Realistic look), thick and solid at the finger,
     * thinning and fading toward the tail, and fading out entirely after the finger lifts.
     */
    fun draw(c: Canvas) {
        if (!showTrail || trailN < 2) return
        val now = SystemClock.uptimeMillis()
        // After lift the whole trail fades out quickly.
        val endFade = if (!active && trailEnd > 0) 1f - (now - trailEnd) / END_FADE_MS else 1f
        if (endFade <= 0f) { trailN = 0; return }
        val color = C.ACCENT and 0x00FFFFFF
        val maxW = 6 * density
        for (i in 1 until trailN) {
            val age = (now - tt[i]).toFloat()
            val life = 1f - age / TRAIL_MS
            if (life <= 0f) continue
            val a = (min(1f, life * 1.4f) * endFade * 230).toInt().coerceIn(0, 255)
            trailPaint.color = color or (a shl 24)
            trailPaint.strokeWidth = max(1.5f * density, maxW * life)
            c.drawLine(tx[i - 1], ty[i - 1], tx[i], ty[i], trailPaint)
        }
        view.postInvalidateOnAnimation()
    }

    private val bubble = RectF()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.medium }

    /** The best guess so far, in a pill above the finger; drawn by the parent like the key previews. */
    fun drawPreview(c: Canvas, dx: Float, dy: Float) {
        val word = preview ?: return
        if (!active || n == 0) return
        val s = startKey ?: return
        bubbleText.textSize = min(s.rect.height() * 0.4f, 22 * density)
        val w = bubbleText.measureText(word) + 32 * density
        val h = bubbleText.textSize * 2.1f
        val x = xs[n - 1]; val y = ys[n - 1]
        val top = max(-dy, y - s.rect.height() * 0.9f - h)
        val left = (x - w / 2).coerceIn(0f, max(0f, view.width - w))
        bubble.set(left + dx, top + dy, left + w + dx, top + h + dy)
        val r = h / 2
        bubblePaint.color = 0x33000000
        c.drawRoundRect(bubble.left, bubble.top + 2 * density, bubble.right, bubble.bottom + 2 * density, r, r, bubblePaint)
        bubblePaint.color = C.ACCENT_DIM
        c.drawRoundRect(bubble, r, r, bubblePaint)
        bubbleText.color = C.ON_ACCENT_DIM
        c.drawText(word, bubble.centerX(), bubble.centerY() - (bubbleText.ascent() + bubbleText.descent()) / 2, bubbleText)
    }

    companion object {
        private const val PREVIEW_MS = 90L
        /** How long a point of the trail stays visible. */
        private const val TRAIL_MS = 420f
        private const val END_FADE_MS = 180f

        fun cased(word: String, c: GlideCase): String = when {
            word == "i" || word.startsWith("i'") -> "I" + word.substring(1).let { if (c == GlideCase.UPPER) it.uppercase() else it }
            c == GlideCase.UPPER -> word.uppercase()
            c == GlideCase.CAPITALIZED -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }
}

/** The decoder over the bundled word list, built once in the background (the list loads in the background too). */
object GlideWords {
    @Volatile var decoder: SwipeDecoder? = null
        private set
    val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "PadKeysGlide").apply { isDaemon = true } }
    @Volatile private var building = false

    /** Build the decoder if it isn't yet (no-op while the word list is still loading; tried again later). */
    fun warm() {
        if (decoder != null || building) return
        building = true
        exec.execute {
            try {
                val w = Predict.wordList()
                if (w.isNotEmpty()) {
                    val d = SwipeDecoder(w)
                    // A few throwaway decodes, so the first real gesture doesn't pay for the JIT warming up.
                    val cx = FloatArray(26) { (it % 10) + 0.5f }; val cy = FloatArray(26) { (it / 10) + 0.5f }
                    val k = SwipeDecoder.Keyboard(cx, cy, 1f, 1f)
                    repeat(8) { d.decode(floatArrayOf(0.5f, 5.5f, 3.5f, 8.5f), floatArrayOf(0.5f, 1.5f, 2.5f, 0.5f), 4, k) }
                    decoder = d
                }
            } catch (_: Exception) {
            } finally { building = false }
        }
    }
}
