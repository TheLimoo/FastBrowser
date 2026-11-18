package com.fastbrowser.gologin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Last-crash recorder (v1.0.5).
 *
 * Why: the v1.0.4 omnibox crash killed the app with no visible cause — the
 * user could only report "it crashes when I press the link bar". This
 * installs a global uncaught-exception handler that saves the stack trace
 * to a private on-device file (no upload, nothing leaves the phone) and
 * exposes it to Settings → Diagnostics as "Last crash", so the next crash
 * reports its exact cause instead of a blank screen.
 *
 * The previous handler is always chained: the app still crashes visibly
 * (system crash dialog, process dies as normal) — this only RECORDS.
 */
data class CrashInfo(
    val atMillis: Long,
    val exceptionName: String,
    val message: String?,
    val stackLines: List<String>
)

object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_STACK_LINES = 25

    private val _lastCrash = MutableStateFlow<CrashInfo?>(null)
    val lastCrash: StateFlow<CrashInfo?> = _lastCrash.asStateFlow()

    fun install(context: Context) {
        // A crash from a previous run is already on disk — surface it first.
        _lastCrash.value = readCrashFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val info = CrashInfo(
                    atMillis = System.currentTimeMillis(),
                    exceptionName = throwable.javaClass.name,
                    message = throwable.message,
                    stackLines = throwable.stackTrace.take(MAX_STACK_LINES).map { it.toString() }
                )
                writeCrashFile(context, info)
                _lastCrash.value = info
            } catch (_: Exception) {
                // Never let the reporter itself break the crash path.
            }
            // Chain: the app still dies exactly as it would without us.
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
        _lastCrash.value = null
    }

    private fun writeCrashFile(context: Context, info: CrashInfo) {
        val text = buildString {
            appendLine(info.atMillis)
            appendLine(info.exceptionName)
            appendLine(info.message ?: "")
            info.stackLines.forEach { appendLine(it) }
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    private fun readCrashFile(context: Context): CrashInfo? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val lines = file.readLines()
            if (lines.size < 3) return null
            CrashInfo(
                atMillis = lines[0].toLongOrNull() ?: 0L,
                exceptionName = lines[1],
                message = lines[2].ifBlank { null },
                stackLines = lines.drop(3)
            )
        } catch (_: Exception) {
            null
        }
    }
}
