package dev.padkeys

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import dev.padkeys.ui.applyKeyboardColors

/**
 * The system keyboard. PadBridge's keyboard view sent USB HID reports to a computer; here the same view reports keys
 * to this service, which turns them into edits of the focused app's text through the [InputConnection]:
 *
 * - characters are committed as text (Shift, Caps Lock and flick already applied by the view),
 * - Backspace deletes the selection or the character (grapheme) before the cursor, Ctrl + Backspace the word,
 * - Enter runs the field's editor action ("Go", "Send"…) when it has one, otherwise types a newline,
 * - arrows, Home / End, Tab, Esc, Delete, F-keys and Ctrl / Alt shortcuts are sent as key events with their meta
 *   state (Ctrl + A / C / X / V use the editor's context-menu actions, which work in more apps than key events).
 *
 * Suggestions follow the editor's text (the word before the cursor), not the keys pressed, so they stay right when
 * the cursor moves or the app edits the text itself.
 */
class PadKeysService : InputMethodService(), KeyboardView.Listener, SuggestionStrip.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var root: KeyboardRoot? = null
    private var keyboard: KeyboardView? = null
    private var strip: SuggestionStrip? = null
    private var field = Field.NONE
    private var info: EditorInfo? = null
    /** The selection is a range (not just a cursor): no suggestions, Backspace deletes it. */
    private var hasSelection = false
    /**
     * A picked suggestion added a space after the word: punctuation typed right after it replaces that space
     * ("word!" rather than "word !"). [autoSpaceAt] is the cursor position after the pick (-1 until the editor reports
     * it); any other cursor move cancels.
     */
    private var autoSpace = false
    private var autoSpaceAt = -1
    /** Glide typing: the glided word's spacing, alternatives and Backspace (GlideInput.kt). */
    private val glide = GlideInput({ currentInputConnection }, { strip?.show(if (suggestionsOn) it else emptyList()) }, { learning })

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Haptics.init(this)
        Predict.init(this)
        Prefs.listen(this)
    }

    override fun onDestroy() {
        Prefs.unlisten(this)
        Predict.listener = null
        Predict.close()
        super.onDestroy()
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreateInputView(): View {
        val kb = KeyboardView(this).also { it.listener = this; it.glide.listener = glide }
        val st = SuggestionStrip(this).also { it.listener = this }
        val r = KeyboardRoot(this, st, kb)
        root = r; keyboard = kb; strip = st
        applySettings()
        return r
    }

    /** The keyboard stays below the app in landscape too (no full-screen text box). */
    override fun onEvaluateFullscreenMode() = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySettings()
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        info = editorInfo
        field = Field.of(editorInfo)
        hasSelection = editorInfo.initialSelStart != editorInfo.initialSelEnd
        autoSpace = false
        glide.clear()
        applySettings()
        keyboard?.let { kb ->
            if (!restarting) kb.releaseEverything()
            kb.showSymbols(field.numeric)
            kb.enterLabel = field.enterLabel
        }
        Predict.listener = Predict.Listener { _, list -> if (!glide.showing) strip?.show(if (suggestionsOn) list else emptyList()) }
        Predict.reset()
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboard?.releaseEverything()
        strip?.show(emptyList())
        Predict.close()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        hasSelection = newSelStart != newSelEnd
        if (autoSpace) {
            if (autoSpaceAt == -1) autoSpaceAt = newSelEnd else if (newSelEnd != autoSpaceAt) autoSpace = false
        }
        glide.onSelection(newSelStart, newSelEnd)
        refresh()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == "learnedWords") return // written while typing; nothing to redraw
        applySettings()
        if (key == "suggestions") { Predict.reset(); refresh() }
    }

    /** Applies settings and the system's colors to the views. */
    private fun applySettings() {
        val kb = keyboard ?: return
        applyKeyboardColors(this)
        kb.configure(Prefs.layout(landscape))
        kb.flick = Prefs.flick
        kb.spaceCursor = Prefs.spaceCursor
        kb.keyPreview = Prefs.keyPreview
        kb.heightScale = Prefs.height
        kb.glide.enabled = Prefs.glide && GlideInput.allowed(info)
        kb.glide.showTrail = Prefs.glideTrail
        root?.invalidateAll()
    }

    private val suggestionsOn get() = Prefs.suggestions && field.suggest
    private val learning get() = Prefs.learn && field.learn

    /** After any change of text or cursor: Shift for a new sentence, and suggestions for the word before the cursor. */
    private fun refresh() {
        val ic = currentInputConnection ?: return
        val kb = keyboard ?: return
        val ei = info
        kb.autoShift = Prefs.autoCaps && field.autoCap && !hasSelection && ei != null &&
            ic.getCursorCapsMode(ei.inputType) != 0
        if (glide.showing) return
        if (!suggestionsOn || hasSelection) { Predict.reset(); strip?.show(emptyList()); return }
        Predict.lookup(Words.ending(ic.getTextBeforeCursor(48, 0)))
    }

    // ------------------------------------------------------------------ keys

    override fun onText(text: String, meta: Int) {
        val ic = currentInputConnection ?: return
        val shortcut = meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) != 0
        val afterPick = autoSpace
        autoSpace = false
        if (shortcut) { shortcut(ic, text, meta); return }
        if (afterPick && text.length == 1 && text[0] in ".,!?;:)" && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
            ic.deleteSurroundingText(1, 0)
        }
        // A space or punctuation ends the word before the cursor: learn it (only if the user opted in).
        if (learning && text.isNotEmpty() && !text[0].isLetterOrDigit() && text[0] != '\'') {
            Predict.learn(Words.ending(ic.getTextBeforeCursor(48, 0)))
        }
        if (field.raw) typeAsKeys(ic, text) else ic.commitText(text, 1)
    }

    /** Ctrl / Alt + a character key. */
    private fun shortcut(ic: InputConnection, text: String, meta: Int) {
        val c = text.lowercase().firstOrNull() ?: return
        val ctrlOnly = meta and KeyEvent.META_CTRL_ON != 0 && meta and (KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        // The editor's own clipboard actions work in more apps (WebViews, custom editors) than Ctrl key events.
        if (ctrlOnly && !shift && !field.raw) {
            val action = when (c) {
                'a' -> android.R.id.selectAll
                'c' -> android.R.id.copy
                'x' -> android.R.id.cut
                'v' -> android.R.id.paste
                else -> 0
            }
            if (action != 0) { ic.performContextMenuAction(action); return }
            // Redo: Android's text fields know Ctrl + Shift + Z, not Ctrl + Y.
            if (c == 'y') { sendKey(ic, KeyEvent.KEYCODE_Z, meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON); return }
        }
        val code = keyCodeFor(c) ?: return
        sendKey(ic, code, meta)
    }

    override fun onKey(code: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        autoSpace = false
        when (code) {
            KeyEvent.KEYCODE_DEL -> backspace(ic, meta)
            KeyEvent.KEYCODE_ENTER -> enter(ic, meta)
            else -> sendKey(ic, code, meta)
        }
    }

    override fun onCursor(code: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        autoSpace = false
        sendKey(ic, code, meta)
    }

    private fun backspace(ic: InputConnection, meta: Int) {
        if (field.raw) { sendKey(ic, KeyEvent.KEYCODE_DEL, meta); return }
        if (hasSelection || !ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return }
        if (meta and KeyEvent.META_CTRL_ON == 0 && glide.backspace()) return
        val before = ic.getTextBeforeCursor(64, 0)
        // Nothing before the cursor: let the app see the key (chips, "delete the empty item", etc.).
        if (before.isNullOrEmpty()) { sendKey(ic, KeyEvent.KEYCODE_DEL, meta); return }
        val n = if (meta and KeyEvent.META_CTRL_ON != 0) Words.deleteWordLength(before) else lastGraphemeLength(before)
        ic.deleteSurroundingText(n.coerceAtLeast(1), 0)
    }

    /** Length of the last user-perceived character (an emoji with skin tone or a flag is several chars). */
    private fun lastGraphemeLength(s: CharSequence): Int {
        val it = BreakIterator.getCharacterInstance()
        it.setText(s.toString())
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE) 1 else end - start
    }

    private fun enter(ic: InputConnection, meta: Int) {
        val ei = info
        val action = ei?.let { Field.editorAction(it) }
        if (learning) Predict.learn(Words.ending(ic.getTextBeforeCursor(48, 0)))
        when {
            // Shift + Enter: always a new line.
            meta and KeyEvent.META_SHIFT_ON != 0 && field.multiLine -> ic.commitText("\n", 1)
            action != null && meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) == 0 &&
                meta and KeyEvent.META_SHIFT_ON == 0 -> ic.performEditorAction(action)
            field.multiLine -> ic.commitText("\n", 1)
            else -> sendKey(ic, KeyEvent.KEYCODE_ENTER, meta)
        }
    }

    /** A key press and release, marked as coming from a soft keyboard. */
    private fun sendKey(ic: InputConnection, code: Int, meta: Int) {
        val t = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
    }

    /** Fields without editable text (terminals): type characters as the key events that produce them. */
    private fun typeAsKeys(ic: InputConnection, text: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        if (events == null) { ic.commitText(text, 1); return }
        for (e in events) ic.sendKeyEvent(KeyEvent.changeFlags(e, e.flags or KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
    }

    private fun keyCodeFor(c: Char): Int? = when (c) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
        ' ' -> KeyEvent.KEYCODE_SPACE
        '-' -> KeyEvent.KEYCODE_MINUS
        '=' -> KeyEvent.KEYCODE_EQUALS
        '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
        ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
        '\\' -> KeyEvent.KEYCODE_BACKSLASH
        ';' -> KeyEvent.KEYCODE_SEMICOLON
        '\'' -> KeyEvent.KEYCODE_APOSTROPHE
        '`' -> KeyEvent.KEYCODE_GRAVE
        ',' -> KeyEvent.KEYCODE_COMMA
        '.' -> KeyEvent.KEYCODE_PERIOD
        '/' -> KeyEvent.KEYCODE_SLASH
        else -> null
    }

    // ------------------------------------------------------------------ strip

    /** Replaces the word at the cursor (the part before it and any rest of it after it) with [word] and a space. */
    override fun onPick(word: String) {
        val ic = currentInputConnection ?: return
        if (glide.pick(word)) return
        val before = Words.ending(ic.getTextBeforeCursor(48, 0))
        val afterText = ic.getTextAfterCursor(48, 0)
        val after = Words.starting(afterText)
        // An existing space after the word is reused, so picking never leaves a double space.
        val spaceAfter = afterText?.getOrNull(after.length) == ' '
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length, after.length + if (spaceAfter) 1 else 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (learning) Predict.learn(word)
        autoSpace = true; autoSpaceAt = -1
        Haptics.soft(0.4f)
    }

    /** Next layout for this orientation: Phone → Laptop → 65% → Full → Split. */
    override fun onLayoutButton() {
        val all = KbLayout.entries
        val next = all[(all.indexOf(Prefs.layout(landscape)) + 1) % all.size]
        Prefs.setLayout(landscape, next) // the preference listener applies it
    }

    override fun onLayoutButtonLong() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun onSettingsButton() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The input view: suggestion strip over the keys. It draws the keys' preview bubbles last, over both children, so a
 * bubble for the top row can rise into the strip instead of being cut off at the keyboard's edge. It also keeps the
 * keys clear of the navigation bar on devices that draw the keyboard behind it.
 */
@SuppressLint("ViewConstructor")
class KeyboardRoot(ctx: Context, private val strip: SuggestionStrip, private val keyboard: KeyboardView) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnApplyWindowInsetsListener { v, insets ->
            // Android 13+ draws its own back / switch-keyboard bar inside the keyboard's window and reports it to the
            // keyboard as a caption bar, not a navigation bar.
            val bottom = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
    }

    fun invalidateAll() { invalidate(); strip.invalidate(); keyboard.invalidate() }

    override fun onDraw(canvas: Canvas) = canvas.drawColor(C.BG)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        keyboard.drawBubbles(canvas, keyboard.left.toFloat(), keyboard.top.toFloat())
    }
}
