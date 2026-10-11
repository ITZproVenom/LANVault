package com.lanvault.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ring buffer of recent lines shown on the TV screen and in the web UI. */
class EventLog(private val capacity: Int = 400) {

    data class Line(val timestamp: Long, val level: Level, val message: String) {
        enum class Level { INFO, WARN, ERROR, HTTP }
    }

    private val lock = Any()
    private val lines = ArrayDeque<Line>()

    private val state = MutableStateFlow<List<Line>>(emptyList())

    val snapshot: StateFlow<List<Line>> = state.asStateFlow()

    fun info(message: String) = append(Line.Level.INFO, message)

    fun warn(message: String) = append(Line.Level.WARN, message)

    fun error(message: String) = append(Line.Level.ERROR, message)

    fun http(message: String) = append(Line.Level.HTTP, message)

    fun clear() {
        synchronized(lock) { lines.clear() }
        state.value = emptyList()
    }

    private fun append(level: Line.Level, message: String) {
        val line = Line(System.currentTimeMillis(), level, message)
        synchronized(lock) {
            // Collapse a repeated failure (for example a retry loop hitting the same busy port)
            // so the log stays readable on the TV screen.
            val previous = lines.lastOrNull()
            if (previous != null && previous.level == level && previous.message == message) {
                lines.removeLast()
            }
            lines.addLast(line)
            while (lines.size > capacity) lines.removeFirst()
            state.value = lines.toList()
        }
    }

    companion object {
        val shared = EventLog()
    }
}
