package dev.padkeys

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray

/**
 * Clipboard history for the clipboard panel. PadKeys only looks at the clipboard while the keyboard is showing
 * ([start] / [stop], with [ClipboardManager.addPrimaryClipChangedListener]), as Android allows the active keyboard
 * to: text copied then is kept in memory while PadKeys runs (never written to storage), and items the user pins are
 * stored in the app's private settings. Clips the copying app marked sensitive (passwords, Android 13+) are skipped.
 */
object Clips {
    private const val MAX_RECENT = 20
    /** How long a fresh copy is offered as a chip in the suggestion strip. */
    private const val OFFER_MS = 60_000L

    private val recent = ArrayList<String>()
    private var pinned: MutableList<String>? = null

    /** The latest copied text and when it was copied (uptime), for the strip's paste chip. */
    var offer: String? = null
        private set
    private var offerAt = 0L

    /** Called when the history changes (an item was copied, pinned or removed). */
    var onChange: (() -> Unit)? = null

    private var cm: ClipboardManager? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { read(fresh = true) }

    /** The keyboard is showing: watch the clipboard, and pick up something copied just before. */
    fun start(ctx: Context) {
        if (!Prefs.clipboard) return
        val m = ctx.getSystemService(ClipboardManager::class.java) ?: return
        if (cm == null) { cm = m; m.addPrimaryClipChangedListener(listener) }
        // Copied in the app a moment ago (before the keyboard opened)? Only the description is read to decide.
        val d = try { m.primaryClipDescription } catch (_: Exception) { null } ?: return
        val age = System.currentTimeMillis() - d.timestamp
        if (age in 0..OFFER_MS) read(fresh = true, ageMs = age)
    }

    /** The keyboard went away: stop watching. */
    fun stop() {
        cm?.removePrimaryClipChangedListener(listener)
        cm = null
    }

    private fun read(fresh: Boolean, ageMs: Long = 0) {
        val m = cm ?: return
        if (!Prefs.clipboard) return
        val clip = try { m.primaryClip } catch (_: Exception) { null } ?: return
        val d = clip.description
        if (sensitive(d) || clip.itemCount == 0) return
        val text = clip.getItemAt(0).text?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (text.length > 20_000) return
        val isNew = recent.firstOrNull() != text
        if (isNew) {
            recent.remove(text)
            recent.add(0, text)
            while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
        }
        if (fresh && (isNew || offer == null) && text != lastOffered) {
            offer = text
            offerAt = SystemClock.uptimeMillis() - ageMs
            lastOffered = text
        }
        onChange?.invoke()
    }

    /** The same copy is offered once, not again each time the keyboard opens. */
    private var lastOffered: String? = null

    private fun sensitive(d: ClipDescription): Boolean =
        Build.VERSION.SDK_INT >= 33 && d.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true

    /** The offered text if it is still fresh. */
    fun currentOffer(): String? {
        val o = offer ?: return null
        if (SystemClock.uptimeMillis() - offerAt > OFFER_MS) { offer = null; return null }
        return o
    }

    /** Milliseconds until the offer expires (for scheduling the strip to hide it). */
    fun offerRemaining(): Long = (OFFER_MS - (SystemClock.uptimeMillis() - offerAt)).coerceAtLeast(0)

    fun dismissOffer() { offer = null }

    // ---------------------------------------------------------------- items

    class Item(val text: String, val pinned: Boolean)

    private fun pins(): MutableList<String> {
        pinned?.let { return it }
        val l = ArrayList<String>()
        try { val a = JSONArray(Prefs.pinnedClips); for (i in 0 until a.length()) l += a.getString(i) } catch (_: Exception) {}
        pinned = l
        return l
    }

    private fun savePins() { Prefs.pinnedClips = JSONArray(pins()).toString() }

    /** Pinned items first, then recent copies (newest first). */
    fun items(): List<Item> {
        val p = pins()
        return p.map { Item(it, true) } + recent.filter { it !in p }.map { Item(it, false) }
    }

    fun pin(text: String, on: Boolean) {
        val p = pins()
        if (on) { if (text !in p) p.add(0, text) } else p.remove(text)
        savePins()
        onChange?.invoke()
    }

    fun delete(text: String) {
        recent.remove(text)
        if (pins().remove(text)) savePins()
        if (offer == text) offer = null
        onChange?.invoke()
    }

    /** Forget the copies (not the pins), e.g. when the setting is turned off. */
    fun clearRecent() { recent.clear(); offer = null; onChange?.invoke() }
}
