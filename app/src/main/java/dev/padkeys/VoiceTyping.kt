package dev.padkeys

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Voice typing inside PadKeys, with the phone's speech recognizer (Google's on most phones, the same one Gboard's
 * voice typing uses). PadKeys only receives the text; the audio goes to the recognition service, not to PadKeys.
 * Words appear as they're recognized and are committed when the recognizer is sure.
 */
class VoiceTyping(private val ctx: Context, private val cb: Callback) {
    interface Callback {
        /** Words so far (not final): shown underlined in the field. */
        fun onVoicePartial(text: String)
        /** The final words of one utterance. */
        fun onVoiceFinal(text: String)
        /** The state or message changed (see [state], [message]). */
        fun onVoiceState()
        /** Loudness 0..1, for the mic's ring. */
        fun onVoiceLevel(level: Float)
    }

    enum class State { IDLE, STARTING, LISTENING, NEEDS_PERMISSION, UNAVAILABLE }

    var state = State.IDLE
        private set
    /** A short note for the panel: an error, or a hint. */
    var message: String? = null
        private set

    private var recognizer: SpeechRecognizer? = null

    val available get() = SpeechRecognizer.isRecognitionAvailable(ctx)
    val hasPermission get() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val listening get() = state == State.STARTING || state == State.LISTENING

    private fun set(s: State, msg: String? = null) { state = s; message = msg; cb.onVoiceState() }

    fun start() {
        if (!available) { set(State.UNAVAILABLE, "This phone has no speech recognition service. Install or turn on Google's speech services."); return }
        if (!hasPermission) { set(State.NEEDS_PERMISSION, "PadKeys needs the microphone for voice typing. Tap the mic to allow it."); return }
        val r = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener); recognizer = it }
        } catch (_: Exception) { set(State.UNAVAILABLE, "Speech recognition couldn't start."); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        set(State.STARTING)
        try { r.startListening(intent) } catch (_: Exception) { set(State.IDLE, "Speech recognition couldn't start. Try again.") }
    }

    /** Stop listening; what was heard so far is still recognized and delivered. */
    fun stop() {
        if (!listening) return
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    /** Stop at once and drop what wasn't recognized yet. */
    fun cancel() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        if (listening) set(State.IDLE)
    }

    fun destroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        state = State.IDLE
    }

    /** Back to idle with no message (the panel opened again). */
    fun reset() { if (!listening) { state = State.IDLE; message = null } }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = set(State.LISTENING)
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = cb.onVoiceLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partial: Bundle?) {
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) cb.onVoicePartial(t)
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            cb.onVoiceFinal(t)
            set(State.IDLE, if (t.isBlank()) "Didn't catch that. Tap the mic to try again." else null)
        }

        override fun onError(error: Int) {
            cb.onVoiceFinal("") // keep whatever partial text is showing
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    set(State.NEEDS_PERMISSION, "PadKeys needs the microphone for voice typing. Tap the mic to allow it.")
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    set(State.IDLE, "Didn't catch that. Tap the mic to try again.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    set(State.IDLE, "Voice typing needs the internet (or an offline speech pack for your language).")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> set(State.IDLE, "The speech service is busy. Try again.")
                SpeechRecognizer.ERROR_CLIENT -> set(State.IDLE)
                else -> set(State.IDLE, "Voice typing stopped (error $error). Tap the mic to try again.")
            }
        }
    }
}

/**
 * The voice typing panel, in the keys' place: a big mic (its ring follows your voice while listening), what's being
 * heard, and ABC, Space, Backspace, Enter, plus a switch to the phone's voice keyboard when there is one.
 */
class VoicePanel(ctx: Context) : PanelView(ctx) {
    /** What the mic and the voice-keyboard button do, implemented by the service. */
    interface VoiceListener {
        fun onMicTap()
        fun onVoiceKeyboard()
    }

    var voice: VoiceListener? = null
    /** The service's engine, read for the state and message. */
    var engine: VoiceTyping? = null
    /** Words heard so far in this utterance. */
    var heard = ""
        set(v) { field = v; invalidate() }
    /** Show the "Voice keyboard" button (the phone has a voice input method turned on). */
    var hasVoiceKeyboard = false
    private var level = 0f
    private var shownLevel = 0f
    private var lastFrame = 0L

    private val text = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).also { it.typeface = Fonts.regular; it.textSize = 17 * density }
    private val mic = RectF()

    fun setLevel(l: Float) { level = l; invalidate() }

    override fun open() { super.open(); heard = ""; level = 0f; shownLevel = 0f }

    private val bottomH get() = min(56 * density, height * 0.28f)

    private fun bottomButtons(): List<Pair<Int, Float>> =
        listOf(ABC to 1.4f) + (if (hasVoiceKeyboard) listOf(VKB to 1.8f) else emptyList()) +
            listOf(SPACE to 2.2f, BKSP to 1f, ENTER to 1.2f)

    private fun bottomRect(id: Int, out: RectF): RectF {
        val m = 6 * density
        val w = min(width.toFloat(), 720 * density)
        val x0 = (width - w) / 2 + m
        val list = bottomButtons()
        val unit = (w - 2 * m) / list.sumOf { it.second.toDouble() }.toFloat()
        var x = x0
        for ((bid, bw) in list) {
            if (bid == id) return out.apply { set(x + 3 * density, height - bottomH - m + 3 * density, x + bw * unit - 3 * density, height - m - 3 * density) }
            x += bw * unit
        }
        return out.apply { setEmpty() }
    }

    private fun micRect(out: RectF): RectF {
        val area = height - bottomH - 12 * density
        val r = min(area * 0.3f, 44 * density)
        val cy = area * 0.62f
        return out.apply { set(width / 2f - r, cy - r, width / 2f + r, cy + r) }
    }

    override fun hit(x: Float, y: Float): Int {
        micRect(tmp)
        val slop = 16 * density
        if (x in tmp.left - slop..tmp.right + slop && y in tmp.top - slop..tmp.bottom + slop) return MIC
        for ((id, _) in bottomButtons()) if (bottomRect(id, tmp).contains(x, y)) return id
        return NONE
    }

    override fun repeats(id: Int) = id == BKSP

    override fun click(id: Int) {
        val l = listener ?: return
        when (id) {
            MIC -> voice?.onMicTap()
            ABC -> l.onPanelClose()
            VKB -> voice?.onVoiceKeyboard()
            SPACE -> l.onPanelText(" ")
            BKSP -> l.onPanelKey(KeyEvent.KEYCODE_DEL, 0)
            ENTER -> l.onPanelKey(KeyEvent.KEYCODE_ENTER, 0)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        val e = engine
        val listening = e?.listening == true
        // The ring eases toward the voice level, so it breathes instead of flickering.
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(0, 64).toFloat()
        lastFrame = now
        val target = if (listening) max(level, 0.08f) else 0f
        shownLevel += (target - shownLevel) * min(1f, dt / 90f)

        micRect(mic)
        val r = mic.width() / 2
        if (shownLevel > 0.01f) {
            fill.color = (C.ACCENT and 0x00FFFFFF) or (0x40 shl 24)
            c.drawCircle(mic.centerX(), mic.centerY(), r * (1.15f + 0.55f * shownLevel), fill)
        }
        fill.color = when {
            pressed == MIC -> C.ACCENT_DIM
            listening -> C.ACTION
            else -> C.KEY_FN
        }
        c.drawCircle(mic.centerX(), mic.centerY(), r, fill)
        Icons.draw(c, Icons.Id.MIC, mic.centerX(), mic.centerY(), r * 0.9f,
            if (pressed == MIC) C.ON_ACCENT_DIM else if (listening) C.ON_ACTION else C.ON_KEY_FN)

        // What's heard, or the state, above the mic.
        val line = when {
            heard.isNotBlank() -> heard
            e?.message != null -> e.message!!
            e?.state == VoiceTyping.State.STARTING -> "Starting…"
            listening -> "Speak now"
            else -> "Tap the mic to speak"
        }
        text.color = if (heard.isNotBlank()) C.TEXT else C.MUTED
        val tw = (min(width.toFloat(), 720 * density) - 40 * density).toInt().coerceAtLeast(50)
        val lay = StaticLayout.Builder.obtain(line, 0, line.length, text, tw)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.START).build()
        val top = max(8 * density, mic.top - 16 * density - lay.height)
        c.save(); c.translate((width - tw) / 2f, top); lay.draw(c); c.restore()

        for ((id, _) in bottomButtons()) {
            bottomRect(id, tmp)
            when (id) {
                ABC -> button(c, tmp, Icons.Id.KEYBOARD, "ABC", pressed == id, pill = true)
                VKB -> button(c, tmp, Icons.Id.MIC, "Voice keyboard", pressed == id)
                SPACE -> button(c, tmp, null, null, pressed == id, function = false, pill = true)
                BKSP -> button(c, tmp, Icons.Id.BACKSPACE, null, pressed == id)
                ENTER -> button(c, tmp, Icons.Id.RETURN, null, pressed == id, on = true, pill = true)
            }
        }
        if (listening || shownLevel > 0.01f) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private companion object {
        const val MIC = 1; const val ABC = 2; const val VKB = 3; const val SPACE = 4; const val BKSP = 5; const val ENTER = 6
    }
}
