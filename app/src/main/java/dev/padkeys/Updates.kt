package dev.padkeys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the updater is doing. Each flavor's [Updater] drives it; the UI only reads it. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val notes: String = "") : UpdateState
    /** progress in 0..1, or -1 when the size is unknown. */
    data class Downloading(val version: String, val progress: Float) : UpdateState
    /** Downloaded; the system installer (GitHub) or a restart (Play) finishes it. */
    data class Ready(val version: String) : UpdateState
    /** [openAppInfo]: the fix is a switch on PadKeys' App info page (e.g. network access turned off). */
    data class Failed(val message: String, val openAppInfo: Boolean = false) : UpdateState
}

object Updates {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)

    /** "1.10.2" > "1.9.0"; suffixes like "-dev" are ignored. */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
