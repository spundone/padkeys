package dev.padkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The glide decoder on a synthetic Phone layout (keys 100 × 140 px, QWERTY rows offset like the real one) with
 * gestures generated through the key centers, with and without a human-like wobble.
 */
class SwipeDecoderTest {

    private val words: Array<String> = File("src/main/assets/words_en.txt").readLines().filter { it.isNotBlank() }.toTypedArray()
    private val decoder = SwipeDecoder(words)

    private val kb: SwipeDecoder.Keyboard = run {
        val cx = FloatArray(26) { Float.NaN }; val cy = FloatArray(26) { Float.NaN }
        val w = 100f; val h = 140f
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, off) ->
            letters.forEachIndexed { i, c -> cx[c - 'a'] = (off + i + 0.5f) * w; cy[c - 'a'] = (row + 0.5f) * h }
        }
        SwipeDecoder.Keyboard(cx, cy, w, h)
    }

    /** A gesture through [word]'s keys; [wobble] (key sizes) misses each key center by up to that much. */
    private fun gesture(word: String, wobble: Float, rnd: Random): Pair<FloatArray, FloatArray> {
        val seq = SwipeDecoder.sequence(word)
        val kx = seq.map { kb.cx[it.toInt()] + (rnd.nextFloat() * 2 - 1) * wobble * kb.keyW }
        val ky = seq.map { kb.cy[it.toInt()] + (rnd.nextFloat() * 2 - 1) * wobble * kb.keyH }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (j in 0 until kx.size - 1) {
            // A gentle bow between keys, like a thumb's arc.
            val bow = (rnd.nextFloat() * 2 - 1) * wobble * 60f
            val steps = 12
            for (s in 0 until steps) {
                val t = s / steps.toFloat()
                val nx = -(ky[j + 1] - ky[j]); val ny = kx[j + 1] - kx[j]
                val nl = kotlin.math.sqrt(nx * nx + ny * ny).coerceAtLeast(1f)
                val b = 4 * t * (1 - t) * bow
                xs += kx[j] + (kx[j + 1] - kx[j]) * t + nx / nl * b
                ys += ky[j] + (ky[j + 1] - ky[j]) * t + ny / nl * b
            }
        }
        xs += kx.last(); ys += ky.last()
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun decode(word: String, wobble: Float, rnd: Random): List<String> {
        val (xs, ys) = gesture(word, wobble, rnd)
        return decoder.decode(xs, ys, xs.size, kb).map { it.word }
    }

    private val common = listOf(
        "hello", "the", "keyboard", "quick", "book", "world", "typing", "android", "don't", "people", "because",
        "little", "phone", "message", "tomorrow", "beautiful", "letter", "school", "about", "think", "really",
        "where", "night", "house", "brown", "jumps", "over", "lazy", "good", "morning", "thanks", "you", "and",
        "with", "have", "this", "from", "they", "would", "there", "their", "what", "when", "your", "which",
    )

    @Test fun exactPathsDecode() {
        val rnd = Random(1)
        val wrong = common.mapNotNull { w -> decode(w, 0f, rnd).firstOrNull().takeIf { it != w }?.let { "$w→$it" } }
        println("exact misses: $wrong")
        assertTrue("exact misses: $wrong", wrong.size <= 2)
        assertEquals("hello", decode("hello", 0f, rnd).first())
        assertEquals("the", decode("the", 0f, rnd).first())
        assertEquals("keyboard", decode("keyboard", 0f, rnd).first())
        assertEquals("quick", decode("quick", 0f, rnd).first())
    }

    @Test fun sloppyPathsDecode() {
        val rnd = Random(7)
        var top1 = 0; var top4 = 0; var total = 0
        val misses = ArrayList<String>()
        repeat(4) {
            for (w in common) {
                val got = decode(w, 0.3f, rnd)
                total++
                if (got.firstOrNull() == w) top1++ else misses += "$w→${got.firstOrNull()}"
                if (w in got) top4++
            }
        }
        println("sloppy: top1 $top1/$total, top4 $top4/$total; misses $misses")
        assertTrue(top1 >= total * 0.75)
        assertTrue(top4 >= total * 0.9)
    }

    @Test fun fastEnough() {
        val rnd = Random(3)
        val gs = common.map { gesture(it, 0.25f, rnd) }
        repeat(3) { for ((xs, ys) in gs) decoder.decode(xs, ys, xs.size, kb) } // warm up
        val t0 = System.nanoTime()
        for ((xs, ys) in gs) decoder.decode(xs, ys, xs.size, kb)
        val ms = (System.nanoTime() - t0) / 1e6 / gs.size
        println("decode: %.2f ms per gesture".format(ms))
        assertTrue(ms < 20)
    }

    @Test fun learnedWordsCount() {
        val (xs, ys) = gesture("padkeys", 0f, Random(5))
        val got = decoder.decode(xs, ys, xs.size, kb, mapOf("padkeys" to 3)).map { it.word }
        assertEquals("padkeys", got.first())
    }

    @Test fun sequencesCollapseDoublesAndSkipApostrophes() {
        assertEquals("helo", SwipeDecoder.sequence("hello").map { 'a' + it.toInt() }.joinToString(""))
        assertEquals("dont", SwipeDecoder.sequence("don't").map { 'a' + it.toInt() }.joinToString(""))
    }
}
