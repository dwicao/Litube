package com.hhst.youtubelite.extractor

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import java.io.IOException

/** In-process JavaScript runtime backed solely by the AndroidX JavaScript sandbox. */
internal class RemoteEjsRuntime(private val app: Context, private val userAgent: String) : JavascriptRuntime {
    private var delegate: SandboxRuntime? = null
    override val version: String get() = delegate?.version ?: "sandbox-pending"

    private fun runtime(context: ExtractionContext): SandboxRuntime {
        delegate?.let { return it }
        return createSandbox(context)?.also { delegate = it }
            ?: throw IOException("JS_RUNTIME_UNAVAILABLE")
    }

    @androidx.annotation.RequiresApi(26)
    private fun createSandbox(context: ExtractionContext): SandboxRuntime? {
        if (!JavaScriptSandbox.isSupported()) return null
        return SandboxRuntime.create(app, context)
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
