package dev.padkeys

import android.content.Context
import android.content.SharedPreferences

/**
 * PadKeys' settings, in one SharedPreferences file shared by the settings screen and the keyboard service (both run
 * in the app's process). The service listens for changes, so a setting changed while the keyboard is showing (the
 * test field on the settings screen) applies at once.
 */
object Prefs {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("padkeys", Context.MODE_PRIVATE)
    }

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = p.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = p.unregisterOnSharedPreferenceChangeListener(l)

    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

    // ---------------------------------------------------------------- layout

    /** Layout per orientation: Phone upright by default, Laptop sideways (a phone in landscape is ~15 keys wide). */
    fun layout(landscape: Boolean): KbLayout =
        KbLayout.from(p.getString(if (landscape) "layoutL" else "layoutP", null), if (landscape) KbLayout.LAPTOP else KbLayout.PHONE)
    fun setLayout(landscape: Boolean, l: KbLayout) = p.edit().putString(if (landscape) "layoutL" else "layoutP", l.name).apply()

    /** Keyboard height, as a multiple of the default (0.7–1.4). */
    var height: Float
        get() = p.getFloat("height", 1f)
        set(v) = p.edit().putFloat("height", v).apply()

    // ---------------------------------------------------------------- typing

    /** Flick up on a key (or hold it) to type its shifted character. */
    var flick: Boolean
        get() = bool("flick", true)
        set(v) = putBool("flick", v)

    /** Drag on the space bar to move the cursor. */
    var spaceCursor: Boolean
        get() = bool("spaceCursor", true)
        set(v) = putBool("spaceCursor", v)

    /** Enlarged label above the pressed key. */
    var keyPreview: Boolean
        get() = bool("keyPreview", true)
        set(v) = putBool("keyPreview", v)

    /** Shift on at the start of a sentence, where the field asks for it. */
    var autoCaps: Boolean
        get() = bool("autoCaps", true)
        set(v) = putBool("autoCaps", v)

    // ---------------------------------------------------------------- suggestions

    /** Word suggestions above the keys. */
    var suggestions: Boolean
        get() = bool("suggestions", true)
        set(v) = putBool("suggestions", v)

    /**
     * Remember words typed on this keyboard to suggest them later. Off by default and stored only in this app's
     * private storage; never for passwords or fields that ask for no learning.
     */
    var learn: Boolean
        get() = bool("learn", false)
        set(v) = putBool("learn", v)

    /** Learned words, one "word<TAB>count" per line. */
    var learnedWords: String
        get() = p.getString("learnedWords", "") ?: ""
        set(v) = p.edit().putString("learnedWords", v).apply()

    // ---------------------------------------------------------------- look and feel

    var haptics: Boolean
        get() = bool("haptics", true)
        set(v) = putBool("haptics", v)

    /** 0 = automatic (by what the motor supports), 1 = rich (preset effects), 2 = basic (plain pulses). */
    var hapticStyle: Int
        get() = p.getInt("hapticStyle", 0)
        set(v) = p.edit().putInt("hapticStyle", v).apply()

    /** Keys drawn as hardware: silver key caps in light mode, space black in dark. */
    var realistic: Boolean
        get() = bool("realistic", false)
        set(v) = putBool("realistic", v)
}
