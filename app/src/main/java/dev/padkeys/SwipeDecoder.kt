package dev.padkeys

import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Glide typing decoder, SHARK2-style template matching: every word has an ideal path, the straight lines through
 * the centers of its letters' keys. A gesture is resampled to [N] evenly spaced points and compared with the
 * resampled ideal path of each candidate word by
 *
 * - location: the mean distance between corresponding points (in key sizes), so the path has to go through the
 *   right part of the keyboard,
 * - shape: the same after scaling both to unit size around their centroids, so a sloppy but well-shaped path still
 *   matches,
 * - coverage: how far the gesture stays from each of the word's keys in order (a key it never came near is a
 *   strong sign against the word),
 *
 * plus a word-frequency prior (rank in the list, most common first; learned words get a boost).
 *
 * Candidates are pruned before any of that: the word's first key must be near where the gesture starts, its last
 * key near where it ends (a 26 × 26 index by first / last letter), and its ideal path must be roughly as long as
 * the gesture. That leaves a few hundred to a few thousand of the 40,000 words, so a decode takes milliseconds.
 *
 * Words are templated by their letters only (apostrophes skipped: "dont" and "don't" share a path) with doubled
 * letters collapsed ("hello" goes h-e-l-o), so doubled-letter and apostrophe words decode like any other and the
 * frequency prior picks between words that share a path. Pure Kotlin, no Android, so it runs in JVM unit tests.
 */
class SwipeDecoder(private val words: Array<String>) {

    /**
     * Where the letter keys are. Centers in pixels, indexed 0..25 for a..z (NaN for a letter the layout lacks);
     * [keyW] / [keyH] are a letter key's size, the unit distances are measured in.
     */
    class Keyboard(val cx: FloatArray, val cy: FloatArray, val keyW: Float, val keyH: Float) {
        init { require(cx.size == 26 && cy.size == 26) }
        internal val ux = FloatArray(26) { cx[it] / keyW }
        internal val uy = FloatArray(26) { cy[it] / keyH }
        internal fun has(l: Int) = !ux[l].isNaN()
    }

    class Candidate(val word: String, val score: Float) {
        override fun toString() = "$word(${"%.3f".format(score)})"
    }

    /** Per word: its letters as 0..25 with doubled letters collapsed (empty for words with fewer than two keys). */
    private val seqs: Array<ByteArray>
    /** Word indexes by first letter * 26 + last letter. */
    private val buckets: Array<IntArray>

    init {
        val lists = Array(26 * 26) { ArrayList<Int>() }
        seqs = Array(words.size) { i ->
            val s = sequence(words[i])
            if (s.size >= 2) lists[s[0] * 26 + s[s.size - 1]].add(i)
            s
        }
        buckets = Array(lists.size) { lists[it].toIntArray() }
    }

    /**
     * The best words for the gesture through ([xs], [ys]) (pixels, [n] points, in order) on [kb], best first.
     * [learned] maps learned words to how often they were typed; they're candidates too, with a boost.
     */
    fun decode(xs: FloatArray, ys: FloatArray, n: Int, kb: Keyboard, learned: Map<String, Int> = emptyMap(),
               max: Int = 4): List<Candidate> {
        if (n < 2) return emptyList()
        // Work in key units, so a key is 1 × 1 whatever its size in pixels.
        val px = FloatArray(n) { xs[it] / kb.keyW }
        val py = FloatArray(n) { ys[it] / kb.keyH }
        val len = pathLength(px, py, n)
        if (len < 0.5f) return emptyList()
        val gx = FloatArray(N); val gy = FloatArray(N)
        resample(px, py, n, gx, gy)
        val g = Shape(gx, gy)

        // Plausible first and last letters: keys near where the gesture starts and ends.
        val firsts = near(kb, px[0], py[0], START_RADIUS)
        val lasts = near(kb, px[n - 1], py[n - 1], END_RADIUS)
        if (firsts.isEmpty() || lasts.isEmpty()) return emptyList()

        val tx = FloatArray(N); val ty = FloatArray(N)
        val lx = FloatArray(MAX_SEQ); val ly = FloatArray(MAX_SEQ)
        // Keep the best [KEEP] by a first, cheap score (location + frequency); refine those with shape and coverage.
        val keep = PriorityQueue<Scored>(KEEP + 1, compareByDescending { it.cost })

        fun consider(word: String, seq: ByteArray, rank: Int) {
            if (seq.size < 2 || seq.size > MAX_SEQ) return
            // The list's tail has junk short "words" (hg, bok, boi): short words must be common to be swiped.
            if ((word.length <= 2 && rank > 1200) || (word.length == 3 && rank > 7000)) return
            var t = 0f
            for (j in seq.indices) {
                val l = seq[j].toInt()
                if (!kb.has(l)) return
                lx[j] = kb.ux[l]; ly[j] = kb.uy[l]
                if (j > 0) t += dist(lx[j - 1], ly[j - 1], lx[j], ly[j])
            }
            // Rough length: people overshoot and curve, rarely cut a path much shorter than its keys.
            if (len < t * 0.55f - 0.6f || len > t * 1.7f + 1.2f) return
            val prior = FREQ * ln(rank + RANK0)
            val cutoff = if (keep.size >= KEEP) keep.peek()!!.cost - prior else MAX_LOC
            if (cutoff <= 0f) return
            // Template points and the location distance, giving up as soon as it can't make the cut.
            resamplePolyline(lx, ly, seq.size, t, tx, ty)
            var sum = 0f
            val limit = cutoff / LOC * N
            for (i in 0 until N) {
                sum += dist(gx[i], gy[i], tx[i], ty[i])
                if (sum > limit) return
            }
            val loc = sum / N
            keep.add(Scored(word, seq, rank, LOC * loc + prior, loc))
            if (keep.size > KEEP) keep.poll()
        }

        for (f in firsts) for (l in lasts) {
            for (i in buckets[f * 26 + l]) consider(words[i], seqs[i], i)
        }
        for ((w, count) in learned) {
            val s = sequence(w)
            if (s.size < 2 || s[0].toInt() !in firsts || s[s.size - 1].toInt() !in lasts) continue
            consider(w, s, (LEARNED_RANK / count).toInt())
        }

        // Refine: shape and key coverage on the survivors.
        val best = HashMap<String, Float>()
        for (c in keep) {
            for (j in c.seq.indices) { val l = c.seq[j].toInt(); lx[j] = kb.ux[l]; ly[j] = kb.uy[l] }
            var t = 0f
            for (j in 1 until c.seq.size) t += dist(lx[j - 1], ly[j - 1], lx[j], ly[j])
            resamplePolyline(lx, ly, c.seq.size, t, tx, ty)
            val shape = g.distance(Shape(tx, ty))
            val cover = coverage(gx, gy, lx, ly, c.seq.size)
            val cost = LOC * c.loc + SHAPE * shape + COVER * cover + FREQ * ln(c.rank + RANK0)
            val k = c.word
            if ((best[k] ?: Float.MAX_VALUE) > cost) best[k] = cost
        }
        return best.entries.sortedBy { it.value }.take(max).map { Candidate(it.key, it.value) }
    }

    private class Scored(val word: String, val seq: ByteArray, val rank: Int, val cost: Float, val loc: Float)

    /** A resampled path normalized for shape matching: centroid at the origin, larger side of the box = 1. */
    private class Shape(x: FloatArray, y: FloatArray) {
        val nx = FloatArray(N); val ny = FloatArray(N)
        init {
            var mx = 0f; var my = 0f
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in 0 until N) {
                mx += x[i]; my += y[i]
                x0 = min(x0, x[i]); x1 = max(x1, x[i]); y0 = min(y0, y[i]); y1 = max(y1, y[i])
            }
            mx /= N; my /= N
            // Never blow a short, flat path (two neighboring keys) up to full size.
            val s = max(max(x1 - x0, y1 - y0), 1f)
            for (i in 0 until N) { nx[i] = (x[i] - mx) / s; ny[i] = (y[i] - my) / s }
        }
        fun distance(o: Shape): Float {
            var sum = 0f
            for (i in 0 until N) sum += dist(nx[i], ny[i], o.nx[i], o.ny[i])
            return sum / N
        }
    }

    companion object {
        /** Points per resampled path. */
        const val N = 32
        private const val MAX_SEQ = 24
        private const val KEEP = 60
        /** Gesture start / end to a word's first / last key center, in key sizes. */
        private const val START_RADIUS = 1.15f
        private const val END_RADIUS = 1.35f
        // Weights, tuned on synthetic gestures (see SwipeDecoderTest).
        private const val LOC = 1.0f
        private const val SHAPE = 1.0f
        private const val COVER = 0.8f
        private const val FREQ = 0.075f
        private const val RANK0 = 30f
        private const val MAX_LOC = 1.6f
        private const val COVER_TOL = 0.4f
        private const val LEARNED_RANK = 3000f

        /** [word]'s keys: letters a..z only, doubled letters collapsed. */
        fun sequence(word: String): ByteArray {
            val out = ByteArray(word.length)
            var n = 0
            for (ch in word) {
                val c = ch.lowercaseChar()
                if (c !in 'a'..'z') continue
                val b = (c - 'a').toByte()
                if (n > 0 && out[n - 1] == b) continue
                out[n++] = b
            }
            return out.copyOf(n)
        }

        private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val dx = x1 - x0; val dy = y1 - y0
            return sqrt(dx * dx + dy * dy)
        }

        private fun pathLength(x: FloatArray, y: FloatArray, n: Int): Float {
            var t = 0f
            for (i in 1 until n) t += dist(x[i - 1], y[i - 1], x[i], y[i])
            return t
        }

        /** Letters whose key center is within [r] key sizes of (x, y). */
        private fun near(kb: Keyboard, x: Float, y: Float, r: Float): Set<Int> {
            val out = HashSet<Int>()
            var best = -1; var bestD = Float.MAX_VALUE
            for (l in 0 until 26) {
                if (!kb.has(l)) continue
                val d = dist(x, y, kb.ux[l], kb.uy[l])
                if (d <= r) out += l
                if (d < bestD) { bestD = d; best = l }
            }
            // Off the letters (a gesture ending on the space bar): the nearest key still counts.
            if (out.isEmpty() && best >= 0 && bestD < r * 2) out += best
            return out
        }

        /** [n] raw points to [N] points evenly spaced along the path. */
        private fun resample(x: FloatArray, y: FloatArray, n: Int, ox: FloatArray, oy: FloatArray) =
            resamplePolyline(x, y, n, pathLength(x, y, n), ox, oy)

        /** The polyline through [n] points, [total] long, as [N] evenly spaced points. */
        private fun resamplePolyline(x: FloatArray, y: FloatArray, n: Int, total: Float, ox: FloatArray, oy: FloatArray) {
            if (total <= 0f) { ox.fill(x[0]); oy.fill(y[0]); return }
            val step = total / (N - 1)
            ox[0] = x[0]; oy[0] = y[0]
            var k = 1
            var seg = 1
            var segStart = 0f // distance along the path at point seg - 1
            var segLen = dist(x[0], y[0], x[1.coerceAtMost(n - 1)], y[1.coerceAtMost(n - 1)])
            while (k < N - 1) {
                val target = k * step
                while (seg < n - 1 && segStart + segLen < target) {
                    segStart += segLen; seg++
                    segLen = dist(x[seg - 1], y[seg - 1], x[seg], y[seg])
                }
                val t = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
                ox[k] = x[seg - 1] + (x[seg] - x[seg - 1]) * t
                oy[k] = y[seg - 1] + (y[seg] - y[seg - 1]) * t
                k++
            }
            ox[N - 1] = x[n - 1]; oy[N - 1] = y[n - 1]
        }

        /**
         * How badly the gesture misses the word's keys, in order: for each key, the closest the gesture comes to it
         * on its first pass near it (searching forward from where the previous key was passed), beyond a small
         * tolerance. Mean over keys.
         */
        private fun coverage(gx: FloatArray, gy: FloatArray, lx: FloatArray, ly: FloatArray, m: Int): Float {
            var from = 0
            var miss = 0f
            for (j in 0 until m) {
                var bestD = Float.MAX_VALUE; var at = from
                for (i in from until N) {
                    val d = dist(gx[i], gy[i], lx[j], ly[j])
                    if (d < bestD) { bestD = d; at = i }
                    // The first pass over the key, not a later visit (the second "e" of "people").
                    else if (bestD <= COVER_TOL && d > bestD + 0.3f) break
                }
                miss += max(0f, bestD - COVER_TOL)
                // A key the gesture never came near doesn't move the search on.
                if (bestD <= COVER_TOL * 2) from = at
            }
            return miss / m
        }
    }
}
