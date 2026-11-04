package com.hhst.youtubelite.extractor

import android.content.Context
import android.os.Build
import androidx.javascriptengine.JavaScriptSandbox
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import java.io.IOException

/** In-process JavaScript runtime backed solely by the AndroidX JavaScript sandbox. */
internal class RemoteEjsRuntime(private val app: Context, private val userAgent: String) : JavascriptRuntime {
    private var delegate: SandboxRuntime? = null
    override val version: String get() = delegate?.version ?: "sandbox-pending"

    private fun runtime(context: ExtractionContext): SandboxRuntime {
        delegate?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !JavaScriptSandbox.isSupported()) {
            throw IOException("JS_RUNTIME_UNAVAILABLE")
        }
        return SandboxRuntime.create(app, context)?.also { delegate = it }
            ?: throw IOException("JS_RUNTIME_UNAVAILABLE")
    }

    override fun evaluate(code: String, timeoutMs: Long, context: ExtractionContext): String =
        runtime(context).evaluate(code, timeoutMs, context)

    override fun close() {
        delegate?.close()
        delegate = null
    }
}

internal object EjsRuntimeProcess {
    var ready = false
    fun canIsolate(context: Context): Boolean = false
    fun initialize(app: android.app.Application): Boolean = false
}
