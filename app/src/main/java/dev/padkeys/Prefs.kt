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

    /** One-handed mode: the keys shrink toward one side, with buttons to switch sides or go back to full width. */
    var oneHanded: Boolean
        get() = bool("oneHanded", false)
        set(v) = putBool("oneHanded", v)

    /** Which side the one-handed keyboard sits on. */
    var oneHandLeft: Boolean
        get() = bool("oneHandLeft", false)
        set(v) = putBool("oneHandLeft", v)

    /** A number pad (or phone pad) instead of the full keyboard in number, phone and date fields. */
    var numberPad: Boolean
        get() = bool("numberPad", true)
        set(v) = putBool("numberPad", v)

    /**
     * A slim row of Esc and F1–F12 above the keys on every layout (Full has its own). F-keys then don't need Fn, like
     * a hardware keyboard's top row.
     */
    var fnRow: Boolean
        get() = bool("fnRow", false)
        set(v) = putBool("fnRow", v)

    /**
     * With a hardware keyboard attached, PadKeys stays on screen as a bar of the keys it may lack (Esc, F1–F12, Del,
     * Home, End, PgUp, PgDn) instead of hiding.
     */
    var hardwareBar: Boolean
        get() = bool("hardwareBar", true)
        set(v) = putBool("hardwareBar", v)

    /**
     * Ctrl, Alt, Win (and Shift in terminals) sent as real key presses, down while held or latched and up when let
     * go, like a hardware keyboard, so Alt+Tab or Ctrl+Alt+Del reach remote desktops and terminals.
     * 0 automatic (terminals, remote desktops and other apps that take raw keys), 1 always, 2 never.
     */
    var realModifiers: Int
        get() = p.getInt("realModifiers", 0)
        set(v) = p.edit().putInt("realModifiers", v).apply()

    /** A TrackPoint between G, H and B that moves the text cursor (Shift held: selects). */
    var trackPoint: Boolean
        get() = bool("trackPoint", true)
        set(v) = putBool("trackPoint", v)

    /** TrackPoint speed, a multiple of the default (0.4–2.5). */
    var trackPointSpeed: Float
        get() = p.getFloat("trackPointSpeed", 1f)
        set(v) = p.edit().putFloat("trackPointSpeed", v).apply()

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

    /** Hold a letter for its accented forms, the period for punctuation, the comma (Phone) for emoji. */
    var accents: Boolean
        get() = bool("accents", true)
        set(v) = putBool("accents", v)

    /** Drag left from Backspace to select words before the cursor; lifting deletes them. */
    var deleteSwipe: Boolean
        get() = bool("deleteSwipe", true)
        set(v) = putBool("deleteSwipe", v)

    /** Two quick spaces after a word type ". ". */
    var doubleSpace: Boolean
        get() = bool("doubleSpace", true)
        set(v) = putBool("doubleSpace", v)
    /** Glide typing: slide across the letters to type a word. */
    var glide: Boolean
        get() = bool("glide", true)
        set(v) = putBool("glide", v)

    /** Draw the glide-typing trail under the finger. */
    var glideTrail: Boolean
        get() = bool("glideTrail", true)
        set(v) = putBool("glideTrail", v)

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

    /** Replace a misspelled word with its correction when a space or punctuation ends it. */
    var autocorrect: Boolean
        get() = bool("autocorrect", true)
        set(v) = putBool("autocorrect", v)

    // ---------------------------------------------------------------- toolbar

    /**
     * Clipboard history: text copied while the keyboard is showing (kept in memory only) plus pinned items, and the
     * latest copy offered as a chip when the keyboard opens.
     */
    var clipboard: Boolean
        get() = bool("clipboard", true)
        set(v) = putBool("clipboard", v)

    /** Pinned clipboard items, a JSON array of strings (stored only on the device). */
    var pinnedClips: String
        get() = p.getString("pinnedClips", "[]") ?: "[]"
        set(v) = p.edit().putString("pinnedClips", v).apply()

    /** Recently used emoji, most recent first, separated by spaces. */
    var recentEmoji: String
        get() = p.getString("recentEmoji", "") ?: ""
        set(v) = p.edit().putString("recentEmoji", v).apply()

    /** Show the microphone button when the phone has a voice typing keyboard. */
    var voiceKey: Boolean
        get() = bool("voiceKey", true)
        set(v) = putBool("voiceKey", v)

    // ---------------------------------------------------------------- look and feel

    var haptics: Boolean
        get() = bool("haptics", true)
        set(v) = putBool("haptics", v)

    /** 0 = automatic (by what the motor supports), 1 = rich (preset effects), 2 = basic (plain pulses). */
    var hapticStyle: Int
        get() = p.getInt("hapticStyle", 0)
        set(v) = p.edit().putInt("hapticStyle", v).apply()

    /** 0 follow the system, 1 light, 2 dark. */
    var themeMode: Int
        get() = p.getInt("themeMode", 0)
        set(v) = p.edit().putInt("themeMode", v).apply()

    /**
     * Colors: 0 PadKeys' blue palette, 1 wallpaper (Android 12+), 2 a palette grown from [seedColor], 3 two-tone
     * ([twoToneBg] and [twoToneFg] only). Wallpaper by default where the phone has it.
     */
    var palette: Int
        get() = p.getInt("palette", if (android.os.Build.VERSION.SDK_INT >= 31) 1 else 0)
        set(v) = p.edit().putInt("palette", v).apply()

    var seedColor: Int
        get() = p.getInt("seedColor", 0xFF3E64E5.toInt())
        set(v) = p.edit().putInt("seedColor", v).apply()

    var twoToneBg: Int
        get() = p.getInt("twoToneBg", 0xFF0B1F33.toInt())
        set(v) = p.edit().putInt("twoToneBg", v).apply()

    var twoToneFg: Int
        get() = p.getInt("twoToneFg", 0xFFFFC857.toInt())
        set(v) = p.edit().putInt("twoToneFg", v).apply()

    /** True black behind the keys in dark mode (OLED). */
    var pureBlack: Boolean
        get() = bool("pureBlack", false)
        set(v) = putBool("pureBlack", v)

    // ---------------------------------------------------------------- updates

    /** Look for a new GitHub release at most once a day when the settings app opens (never from the keyboard). */
    var autoUpdateCheck: Boolean
        get() = bool("autoUpdateCheck", true)
        set(v) = putBool("autoUpdateCheck", v)

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0L)
        set(v) = p.edit().putLong("lastUpdateCheck", v).apply()

    /** A version the user dismissed; the banner stays hidden until a newer one appears. */
    var skippedUpdate: String?
        get() = p.getString("skippedUpdate", null)
        set(v) = p.edit().putString("skippedUpdate", v).apply()

    /** Keys drawn as hardware: silver key caps in light mode, space black in dark. */
    var realistic: Boolean
        get() = bool("realistic", false)
        set(v) = putBool("realistic", v)
}
