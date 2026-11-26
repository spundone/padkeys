package dev.padkeys

import android.annotation.SuppressLint
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
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
    SharedPreferences.OnSharedPreferenceChangeListener, KeyActions, ToolbarListener, PanelListener {

    private var root: KeyboardRoot? = null
    private var host: FloatHost? = null
    /** The heights the input view's ancestors had before floating mode stretched them. */
    private val docked = HashMap<View, Int>()
    private var keyboard: KeyboardView? = null
    private var strip: SuggestionStrip? = null
    private var emojiPanel: EmojiPanel? = null
    private var clipPanel: ClipboardPanel? = null
    private var editPanel: EditPanel? = null
    private var voicePanel: VoicePanel? = null
    /** In-keyboard voice typing (the phone's speech recognizer). */
    private lateinit var voice: VoiceTyping
    /** The panel shown in the keys' place, by its toolbar button (null: the keys). */
    private var openTool: Tool? = null
    /** The editor's selection, as last reported. */
    private var selStart = 0
    private var selEnd = 0
    /** When the last space was typed (uptime), for double-space → ". ". */
    private var lastSpaceAt = 0L
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
        voice = VoiceTyping(this, voiceCallback)
    }

    override fun onDestroy() {
        Prefs.unlisten(this)
        voice.destroy()
        Predict.listener = null
        Predict.close()
        super.onDestroy()
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreateInputView(): View {
        val kb = KeyboardView(this).also { it.listener = this; it.actions = this; it.glide.listener = glide }
        val st = SuggestionStrip(this).also { it.listener = this; it.tools = this }
        val em = EmojiPanel(this).also { it.listener = this }
        val cp = ClipboardPanel(this).also { it.listener = this }
        val ed = EditPanel(this).also { it.listener = this }
        val vp = VoicePanel(this).also { it.listener = this; it.voice = voiceButtons; it.engine = voice }
        val bar = OneHandBar(this, onExpand = { Prefs.oneHanded = false }, onSwap = { Prefs.oneHandLeft = !Prefs.oneHandLeft })
        lateinit var h: FloatHost
        val handle = FloatHandle(this,
            onMove = { dx, dy -> h.moveBy(dx, dy) },
            onResize = { dx -> h.resizeBy(dx) },
            onDone = { Prefs.setFloatPlace(landscape, h.fx, h.fy, h.widthPx) },
            onDock = { Prefs.floating = false })
        val r = KeyboardRoot(this, st, kb, listOf(em, cp, ed, vp), bar, handle)
        h = FloatHost(this, r)
        host = h
        root = r; keyboard = kb; strip = st
        emojiPanel = em; clipPanel = cp; editPanel = ed; voicePanel = vp
        openTool = null
        applySettings()
        return h
    }

    /** The keyboard stays below the app in landscape too (no full-screen text box). */
    override fun onEvaluateFullscreenMode() = false

    /**
     * Showing just the bar of extra keys: a hardware keyboard is typing and Android would hide the on-screen keyboard
     * (its "show virtual keyboard" setting is off; with it on, the full keyboard shows as usual).
     */
    private var barMode = false

    /**
     * Android hides the on-screen keyboard while a hardware keyboard is attached; with [Prefs.hardwareBar] PadKeys
     * stays, as a bar of the keys hardware keyboards often lack.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        val shown = super.onEvaluateInputViewShown()
        val bar = !shown && Prefs.hardwareBar
        if (bar != barMode) { barMode = bar; applySettings() }
        return shown || bar
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySettings()
        updateInputViewShown() // a hardware keyboard came or went
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        if (!restarting) realDown.clear() // a new field: nothing is held there
        info = editorInfo
        field = Field.of(editorInfo)
        hasSelection = editorInfo.initialSelStart != editorInfo.initialSelEnd
        selStart = editorInfo.initialSelStart; selEnd = editorInfo.initialSelEnd
        autoSpace = false
        AutoCorrect.undo = null
        lastSpaceAt = 0
        if (!restarting) closePanel()
        glide.clear()
        applySettings()
        keyboard?.let { kb ->
            if (!restarting) kb.releaseEverything()
            kb.showSymbols(field.numeric)
            kb.showPad(if (Prefs.numberPad) field.pad else Field.PAD_NONE)
            kb.enterLabel = field.enterLabel
        }
        Predict.listener = Predict.Listener { _, list -> if (!glide.showing) strip?.show(if (suggestionsOn) list else emptyList()) }
        Predict.reset()
        Clips.onChange = { clipPanel?.reload(); showClipOffer() }
        Clips.start(this)
        showClipOffer()
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboard?.releaseEverything()
        strip?.show(emptyList())
        strip?.offerClip(null)
        closePanel()
        Clips.stop()
        Predict.close()
        super.onFinishInputView(finishingInput)
    }

    // ------------------------------------------------------------------ clipboard chip

    private val hideOffer = Runnable { showClipOffer() }

    /** Offer what was just copied as a chip in the strip (for a minute after the copy, like Gboard). */
    private fun showClipOffer() {
        val st = strip ?: return
        st.removeCallbacks(hideOffer)
        val offer = if (field.raw || field.pad != Field.PAD_NONE && Prefs.numberPad) null else Clips.currentOffer()
        st.offerClip(offer)
        if (offer != null) st.postDelayed(hideOffer, Clips.offerRemaining() + 50)
    }

    override fun onClipChip(text: String) {
        currentInputConnection?.commitText(text, 1)
        Clips.dismissOffer()
        showClipOffer()
        Haptics.soft(0.4f)
    }

    // ------------------------------------------------------------------ toolbar and panels

    /** The toolbar's buttons: voice only when the phone has a voice typing keyboard turned on. */
    private fun toolset(): List<Tool> = Tool.entries.filter {
        when (it) {
            Tool.VOICE -> Prefs.voiceKey && (voice.available || Voice.find(this) != null)
            Tool.ONE_HAND -> !Prefs.floating // a floating keyboard is already small
            Tool.FLOAT -> !barMode
            else -> true
        }
    }

    override fun onTool(tool: Tool) {
        when (tool) {
            Tool.EMOJI, Tool.CLIPBOARD, Tool.EDIT -> if (openTool == tool) closePanel() else openPanel(tool)
            Tool.ONE_HAND -> Prefs.oneHanded = !Prefs.oneHanded
            Tool.FLOAT -> Prefs.floating = !Prefs.floating
            Tool.VOICE -> when {
                openTool == Tool.VOICE -> closePanel()
                voice.available -> {
                    openPanel(Tool.VOICE)
                    voice.reset()
                    voicePanel?.hasVoiceKeyboard = Voice.find(this) != null
                    voice.start()
                }
                else -> { closePanel(); Voice.start(this) } // no recognizer: the phone's voice keyboard
            }
        }
    }

    override fun onEmojiKey() = openPanel(Tool.EMOJI)

    private fun panelFor(tool: Tool?): PanelView? = when (tool) {
        Tool.EMOJI -> emojiPanel
        Tool.CLIPBOARD -> clipPanel
        Tool.EDIT -> editPanel
        Tool.VOICE -> voicePanel
        else -> null
    }

    private fun openPanel(tool: Tool) {
        val p = panelFor(tool) ?: return
        if (openTool == Tool.VOICE && tool != Tool.VOICE) stopVoice()
        keyboard?.releaseEverything()
        openTool = tool
        p.open()
        root?.showPanel(p)
        strip?.activeTool = tool
    }

    private fun closePanel() {
        if (openTool == null) return
        if (openTool == Tool.VOICE) stopVoice()
        openTool = null
        root?.showPanel(null)
        strip?.activeTool = null
        refresh()
    }

    override fun onPanelText(text: String) {
        val ic = currentInputConnection ?: return
        AutoCorrect.undo = null
        autoSpace = false
        ic.commitText(text, 1)
        if (openTool == Tool.CLIPBOARD) closePanel()
    }

    override fun onPanelKey(code: Int, meta: Int) = onKey(code, meta)

    override fun onPanelShortcut(c: Char) {
        val ic = currentInputConnection ?: return
        AutoCorrect.undo = null
        shortcut(ic, c.toString(), KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
    }

    override fun onPanelClose() = closePanel()

    // ------------------------------------------------------------------ voice typing

    /** Text of the current utterance is in the field as composing (underlined) text. */
    private var voiceComposing = false
    /** For the current utterance: a space before it, and a capital first letter. */
    private var voiceLead = ""
    private var voiceCap = false

    /** Decided once per utterance, before its composing text is in the field. */
    private fun beginUtterance(ic: InputConnection) {
        if (voiceComposing) return
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        voiceLead = if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in "([{\"'/-") " " else ""
        val end = before.trimEnd().lastOrNull()
        voiceCap = Prefs.autoCaps && field.autoCap && (end == null || end in ".!?")
    }

    private fun voiceText(t: String) = voiceLead + if (voiceCap) t.replaceFirstChar { it.uppercase() } else t

    private fun stopVoice() {
        voice.cancel()
        if (voiceComposing) { currentInputConnection?.finishComposingText(); voiceComposing = false }
        voicePanel?.heard = ""
    }

    private val voiceCallback = object : VoiceTyping.Callback {
        override fun onVoicePartial(text: String) {
            val ic = currentInputConnection ?: return
            beginUtterance(ic)
            ic.setComposingText(voiceText(text), 1)
            voiceComposing = true
            voicePanel?.heard = text
        }

        override fun onVoiceFinal(text: String) {
            val ic = currentInputConnection ?: return
            if (text.isNotBlank()) {
                beginUtterance(ic)
                ic.commitText(voiceText(text), 1)
                autoSpace = false
                AutoCorrect.undo = null
            } else if (voiceComposing) ic.finishComposingText()
            voiceComposing = false
            voicePanel?.heard = ""
        }

        override fun onVoiceState() { voicePanel?.invalidate() }
        override fun onVoiceLevel(level: Float) { voicePanel?.setLevel(level) }
    }

    private val voiceButtons = object : VoicePanel.VoiceListener {
        override fun onMicTap() {
            when {
                voice.listening -> voice.stop()
                voice.available && !voice.hasPermission -> {
                    // A keyboard can't ask for permissions itself: the app asks, then closes back to where you were.
                    try {
                        startActivity(Intent(this@PadKeysService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ASK_MIC, true)
                            // Typing in another app: go back to it afterwards (not when typing in PadKeys' own field).
                            .putExtra(MainActivity.EXTRA_RETURN, info?.packageName != packageName))
                    } catch (_: Exception) {}
                }
                else -> voice.start()
            }
        }

        override fun onVoiceKeyboard() {
            closePanel()
            Voice.start(this@PadKeysService)
        }
    }

    // ------------------------------------------------------------------ delete-key swipe

    /** Where the cursor was when the swipe started, the text before it, and how much is selected now. */
    private var swipeAnchor = -1
    private var swipeBefore: CharSequence = ""
    private var swipeLen = 0

    override fun onDeleteSwipe(words: Int) {
        val ic = currentInputConnection ?: return
        if (swipeAnchor < 0) {
            swipeAnchor = selStart
            swipeBefore = ic.getTextBeforeCursor(2000, 0) ?: ""
        }
        var len = 0
        repeat(words) {
            if (len < swipeBefore.length) len += Words.deleteWordLength(swipeBefore.subSequence(0, swipeBefore.length - len))
        }
        swipeLen = len
        ic.setSelection(swipeAnchor - len, swipeAnchor)
    }

    override fun onDeleteSwipeEnd(delete: Boolean) {
        val ic = currentInputConnection
        if (ic != null && swipeAnchor >= 0) {
            ic.beginBatchEdit()
            ic.setSelection(swipeAnchor, swipeAnchor)
            if (delete && swipeLen > 0) ic.deleteSurroundingText(swipeLen, 0)
            ic.endBatchEdit()
        }
        swipeAnchor = -1; swipeLen = 0; swipeBefore = ""
        AutoCorrect.undo = null
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        hasSelection = newSelStart != newSelEnd
        selStart = newSelStart; selEnd = newSelEnd
        if (autoSpace) {
            if (autoSpaceAt == -1) autoSpaceAt = newSelEnd else if (newSelEnd != autoSpaceAt) autoSpace = false
        }
        glide.onSelection(newSelStart, newSelEnd)
        refresh()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == "learnedWords" || key == "recentEmoji" || key == "pinnedClips") return // written while typing
        if (key == "lastUpdateCheck" || key == "skippedUpdate" || key == "autoUpdateCheck") return // the settings app
        if (key != null && key.startsWith("float") && key != "floating") return // dragged into place
        if (key == "clipboard" && !Prefs.clipboard) { Clips.clearRecent(); Clips.stop(); showClipOffer() }
        applySettings()
        if (key == "hardwareBar") updateInputViewShown()
        if (key == "suggestions") { Predict.reset(); refresh() }
    }

    /** Floating mode is on and applies (not in bar mode, where only a row of keys shows). */
    private val floatingNow get() = Prefs.floating && !barMode

    /**
     * Floating mode: the input method window covers the screen (its height and the input view's ancestors stretched to
     * fill it) so the keyboard can sit anywhere in it; docked, they go back to wrapping the keyboard.
     */
    private fun applyFloating() {
        val h = host ?: return
        val on = floatingNow
        if (on) {
            val (fx, fy, w) = Prefs.floatPlace(landscape)
            h.fx = fx; h.fy = fy; h.widthPx = w
        }
        if (on == h.floating) { h.requestLayout(); return }
        window?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
            if (on) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
        stretchAncestors(h, on, docked)
        h.setFloating(on)
    }

    private val floatRect = android.graphics.Rect()

    /**
     * Floating: the app keeps the whole screen (insets at the bottom of the window), and only the keyboard and the
     * navigation bar's strip take touches; the rest of the window lets them through to the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val h = host ?: return
        if (!h.floating || h.height == 0) return
        val loc = IntArray(2)
        h.getLocationInWindow(loc)
        val bottom = loc[1] + h.height
        outInsets.contentTopInsets = bottom
        outInsets.visibleTopInsets = bottom
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        h.keyboardRect(floatRect)
        floatRect.offset(loc[0], loc[1])
        outInsets.touchableRegion.set(floatRect)
        if (h.navStrip > 0) outInsets.touchableRegion.union(android.graphics.Rect(loc[0], bottom - h.navStrip, loc[0] + h.width, bottom))
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
        kb.accents = Prefs.accents
        kb.deleteSwipe = Prefs.deleteSwipe
        strip?.toolset = toolset()
        applyFloating()
        root?.oneHanded(Prefs.oneHanded && !floatingNow, Prefs.oneHandLeft)
        kb.glide.enabled = Prefs.glide && GlideInput.allowed(info)
        kb.glide.showTrail = Prefs.glideTrail
        kb.fnRow = Prefs.fnRow
        kb.trackPoint = Prefs.trackPoint
        kb.trackPointSpeed = Prefs.trackPointSpeed
        val bar = barMode
        if (bar && !kb.hardwareBar) closePanel()
        kb.hardwareBar = bar
        root?.barMode(bar)
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
        if (actionShortcut(meta) && when (text.lowercase()) {
                " " -> systemKey(KeyEvent.KEYCODE_ASSIST)
                "h" -> systemKey(KeyEvent.KEYCODE_HOME)
                else -> false
            }) return
        val ic = currentInputConnection ?: return
        val shortcut = meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) != 0
        val afterPick = autoSpace
        autoSpace = false
        AutoCorrect.undo = null
        val now = SystemClock.uptimeMillis()
        val lastSpace = lastSpaceAt
        lastSpaceAt = 0
        if (shortcut) { shortcut(ic, text, meta); return }
        if (afterPick && text.length == 1 && text[0] in ".,!?;:)" && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
            ic.deleteSurroundingText(1, 0)
        }
        if (text == " " && doubleSpace(ic, now - lastSpace)) return
        if (text == " ") lastSpaceAt = now
        if (text.length == 1 && text[0] in SEPARATORS && autocorrect(ic, text)) return
        // A space or punctuation ends the word before the cursor: learn it (only if the user opted in).
        if (learning && text.isNotEmpty() && !text[0].isLetterOrDigit() && text[0] != '\'') {
            Predict.learn(Words.ending(ic.getTextBeforeCursor(48, 0)))
        }
        // Tapping letters right after a glided word starts a new word: space first, like Gboard.
        if (text.isNotEmpty() && text[0].isLetterOrDigit() && glide.startsNewWord()) ic.commitText(" ", 1)
        if (field.raw) typeAsKeys(ic, text) else ic.commitText(text, 1)
    }

    /** Two quick spaces after a word: ". " (and Shift for the next sentence, via the field's caps mode). */
    private fun doubleSpace(ic: InputConnection, sinceLastSpace: Long): Boolean {
        if (!Prefs.doubleSpace || !field.suggest || hasSelection || sinceLastSpace > DOUBLE_SPACE_MS) return false
        val b = ic.getTextBeforeCursor(2, 0) ?: return false
        if (b.length != 2 || b[1] != ' ' || !(b[0].isLetterOrDigit() || b[0] in ")\"'’")) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)
        ic.endBatchEdit()
        return true
    }

    /**
     * A space or punctuation ([separator]) ends the word before the cursor: if it is misspelled and a close correction
     * exists, type the correction instead. Backspace right after undoes it ([backspace]).
     */
    private fun autocorrect(ic: InputConnection, separator: String): Boolean {
        if (!Prefs.autocorrect || !field.autocorrect || hasSelection) return false
        val word = Words.ending(ic.getTextBeforeCursor(48, 0))
        if (word.isEmpty()) return false
        // In the middle of a word (the cursor was moved into it): leave it.
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && after[0].isLetterOrDigit()) return false
        val fix = AutoCorrect.correction(word) ?: return false
        if (fix == word) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fix + separator, 1)
        ic.endBatchEdit()
        AutoCorrect.undo = AutoCorrect.Undo(word, fix, separator)
        if (learning) Predict.learn(fix)
        return true
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
        if (systemKey(code) || (actionShortcut(meta) && code == KeyEvent.KEYCODE_DPAD_LEFT && systemKey(KeyEvent.KEYCODE_BACK))) return
        val ic = currentInputConnection ?: return
        autoSpace = false
        lastSpaceAt = 0
        if (code != KeyEvent.KEYCODE_DEL) AutoCorrect.undo = null
        when (code) {
            KeyEvent.KEYCODE_DEL -> backspace(ic, meta)
            KeyEvent.KEYCODE_ENTER -> enter(ic, meta)
            else -> sendKey(ic, code, meta)
        }
    }

    /**
     * The Googlebook action row (and Action / Win shortcuts): Android's Back and Home, media, volume, the assistant
     * and PadKeys' own panels. A keyboard app can't press the system's keys, so each does the same thing through an
     * API instead (Back goes to the app, as Back from a keyboard does). False for every other key.
     */
    private fun systemKey(code: Int): Boolean {
        val audio = getSystemService(AudioManager::class.java)
        fun volume(dir: Int) = audio?.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                val ic = currentInputConnection ?: return true
                val t = SystemClock.uptimeMillis()
                for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                    ic.sendKeyEvent(KeyEvent(t, t, a, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
            }
            KeyEvent.KEYCODE_HOME -> launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            KeyEvent.KEYCODE_ASSIST ->
                if (!launch(Intent(Intent.ACTION_ASSIST))) launch(Intent(Intent.ACTION_VOICE_COMMAND))
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                val t = SystemClock.uptimeMillis()
                audio?.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                audio?.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            KeyEvent.KEYCODE_VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
            KeyEvent.KEYCODE_VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
            KeyboardView.CODE_HIDE -> requestHideSelf(0)
            KeyboardView.CODE_VOICE -> onTool(Tool.VOICE)
            KeyboardView.CODE_EMOJI -> onTool(Tool.EMOJI)
            KeyboardView.CODE_CLIPBOARD -> onTool(Tool.CLIPBOARD)
            else -> return false
        }
        return true
    }

    /** Action (or Win) held in an ordinary field: Googlebook's Action + Space (assistant), + H (home), + Left (back). */
    private fun actionShortcut(meta: Int) = meta and KeyEvent.META_META_ON != 0 && !field.raw

    private fun launch(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    /** Modifier key codes sent down as real key presses, so each gets its up even if the setting changes meanwhile. */
    private val realDown = HashSet<Int>()

    /**
     * Modifiers as hardware keys: a real down when Ctrl / Alt / Win is held or latched and a real up when it's let go,
     * so apps that read raw keys (remote desktops, terminals, VMs) see Alt held across several Tabs. Shift only goes
     * to those apps: in text fields it's carried by each key instead (a lone Shift press confuses Android's editors).
     */
    override fun onModifier(code: Int, down: Boolean, meta: Int) {
        val ic = currentInputConnection
        if (down) {
            val real = when (Prefs.realModifiers) { 1 -> true; 2 -> false; else -> field.raw }
            val shift = code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT
            if (!real || (shift && !field.raw) || ic == null) return
            realDown += code
        } else if (!realDown.remove(code) || ic == null) return
        val t = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        if (BuildConfig.DEBUG) android.util.Log.d("PadKeys", "modifier ${KeyEvent.keyCodeToString(code)} ${if (down) "down" else "up"}")
        ic.sendKeyEvent(KeyEvent(t, t, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
    }

    override fun onCursor(code: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        autoSpace = false
        AutoCorrect.undo = null
        lastSpaceAt = 0
        sendKey(ic, code, meta)
    }

    private fun backspace(ic: InputConnection, meta: Int) {
        if (field.raw) { sendKey(ic, KeyEvent.KEYCODE_DEL, meta); return }
        // Right after an autocorrection: put back what was typed (once), and leave that word alone from now on.
        val undo = AutoCorrect.undo
        AutoCorrect.undo = null
        if (undo != null && !hasSelection && meta and KeyEvent.META_CTRL_ON == 0) {
            val done = undo.fixed + undo.separator
            if (ic.getTextBeforeCursor(done.length, 0)?.toString() == done) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(done.length, 0)
                ic.commitText(undo.typed + undo.separator, 1)
                ic.endBatchEdit()
                AutoCorrect.reject(undo.typed)
                return
            }
        }
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
        if (BuildConfig.DEBUG) android.util.Log.d("PadKeys", "key ${KeyEvent.keyCodeToString(code)} meta=0x${Integer.toHexString(meta)}")
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
        AutoCorrect.undo = null
        autoSpace = true; autoSpaceAt = -1
        Haptics.soft(0.4f)
    }

    /** Next layout for this orientation: Phone → Laptop → 65% → Full → Split → Googlebook. */
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

    private companion object {
        /** Characters that end a word, for autocorrect. */
        const val SEPARATORS = " .,!?;:"
        /** Two spaces this close together type ". ". */
        const val DOUBLE_SPACE_MS = 1100L
    }
}

/**
 * The input view: suggestion strip over the keys. It draws the keys' preview bubbles last, over both children, so a
 * bubble for the top row can rise into the strip instead of being cut off at the keyboard's edge. It also keeps the
 * keys clear of the navigation bar on devices that draw the keyboard behind it.
 *
 * Under the strip, a [deck] holds the keys and the [panels] (emoji, clipboard, editing) that take their place at the
 * same size; in one-handed mode the deck narrows to one side, with the [bar]'s buttons beside it.
 */
@SuppressLint("ViewConstructor")
class KeyboardRoot(
    ctx: Context, private val strip: SuggestionStrip, private val keyboard: KeyboardView,
    private val panels: List<View> = emptyList(), private val bar: View? = null, private val handle: View? = null,
) : LinearLayout(ctx) {
    private var isFloating = false
    private val deck = object : FrameLayout(ctx) {
        // The keys ask their parent to redraw for preview bubbles and popups, which this root draws.
        override fun invalidate() { super.invalidate(); this@KeyboardRoot.invalidate() }

        // Panels take exactly the keys' size (the keys stay measured while hidden).
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measureChild(keyboard, widthMeasureSpec, heightMeasureSpec)
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = keyboard.measuredHeight
            for (p in panels) if (p.visibility != GONE) {
                p.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(w, h)
        }
    }
    private val row = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        handle?.let { it.visibility = GONE; addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        deck.addView(keyboard, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        for (p in panels) {
            p.visibility = GONE
            deck.addView(p, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        row.addView(deck, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnApplyWindowInsetsListener { v, insets ->
            // Android 13+ draws its own back / switch-keyboard bar inside the keyboard's window and reports it to the
            // keyboard as a caption bar, not a navigation bar.
            val bottom = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, if (isFloating) 0 else bottom)
            lastBottom = bottom
            insets
        }
    }

    private var lastBottom = 0

    /** Floating mode: the drag [handle] on top, rounded corners and a shadow, no navigation bar padding. */
    fun floating(on: Boolean) {
        isFloating = on
        handle?.visibility = if (on) VISIBLE else GONE
        setPadding(0, 0, 0, if (on) 0 else lastBottom)
        val d = resources.displayMetrics.density
        outlineProvider = if (on) RoundOutline(16 * d) else ViewOutlineProvider.BACKGROUND
        clipToOutline = on
        elevation = if (on) 8 * d else 0f
    }

    /** Bar mode (hardware keyboard): just the keys, no suggestion strip. */
    fun barMode(on: Boolean) {
        strip.visibility = if (on) GONE else VISIBLE
    }

    fun invalidateAll() {
        invalidate(); strip.invalidate(); keyboard.invalidate()
        panels.forEach { it.invalidate() }; bar?.invalidate()
    }

    /** Show [panel] in the keys' place (null: the keys). The keys stay laid out, so the panel gets their size. */
    fun showPanel(panel: View?) {
        for (p in panels) p.visibility = if (p === panel) VISIBLE else GONE
        keyboard.visibility = if (panel == null) VISIBLE else INVISIBLE
        invalidate()
    }

    private var oneHand: Boolean? = null
    private var oneHandLeft = false

    /** One-handed mode: the keys about three quarters wide (at most 420dp) on one side, the [bar] on the other. */
    fun oneHanded(on: Boolean, left: Boolean) {
        val b = bar ?: return
        if (oneHand == on && oneHandLeft == left) return
        oneHand = on; oneHandLeft = left
        row.removeView(b)
        if (on) {
            val dm = resources.displayMetrics
            val w = minOf(dm.widthPixels * 0.78f, 420 * dm.density).toInt()
            deck.layoutParams = LayoutParams(w, LayoutParams.WRAP_CONTENT)
            row.addView(b, if (left) 1 else 0, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            (b as? OneHandBar)?.keysRight = !left
        } else {
            deck.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    override fun onDraw(canvas: Canvas) = canvas.drawColor(C.BG)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (keyboard.visibility == VISIBLE) keyboard.drawBubbles(canvas,
            (row.left + deck.left + keyboard.left).toFloat(), (row.top + deck.top + keyboard.top).toFloat())
    }
}
