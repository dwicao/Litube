package com.hhst.youtubelite.player.engine

import android.os.Build
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PlaybackFailureReport(
    val videoId: String?,
    val at: String,
    val appVersion: String,
    val device: String,
    val causes: List<String>,
    val streamSummary: String?,
    val diagnostics: String?,
) {
    fun shortMessage(base: String): String {
        val detail = causes.firstOrNull() ?: return base
        return "$base ($detail)"
    }

    fun fullText(): String = buildString {
        appendLine("videoId=$videoId")
        appendLine("at=$at")
        appendLine("app=$appVersion")
        appendLine("device=$device")
        appendLine("causes:")
        if (causes.isEmpty()) appendLine("  (none)")
        causes.forEach { appendLine("  - $it") }
        appendLine("stream=${streamSummary ?: "(none)"}")
        appendLine("diagnostics=${diagnostics ?: "(none)"}")
    }

    companion object {
        fun build(videoId: String?, failure: Throwable, streamSummary: String? = null, diagnostics: String? = null, appVersion: String = "unknown"): PlaybackFailureReport {
            val chain = generateSequence(failure) { it.cause }.take(12).toList()
            val causes = chain.mapNotNull { t ->
                val name = t.javaClass.simpleName.ifBlank { "Throwable" }
                val msg = t.message?.trim()?.take(160)?.replace('\n', ' ')
                val http = (t as? org.schabi.newpipe.extractor.services.youtube.streams.StreamHttpException)
                    ?.let { " http=${it.status}" }.orEmpty()
                val io = if (t is IOException && msg.isNullOrBlank()) " (IOException)" else ""
                "$name$http$io${if (!msg.isNullOrBlank()) ": $msg" else ""}"
            }.distinct().take(8)
            val at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
            val device = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT} / ${Build.VERSION.RELEASE})"
            return PlaybackFailureReport(videoId, at, appVersion, device, causes, streamSummary, diagnostics)
        }
    }
}
