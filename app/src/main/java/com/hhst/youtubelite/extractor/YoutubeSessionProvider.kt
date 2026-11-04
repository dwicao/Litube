package com.hhst.youtubelite.extractor

import android.content.Context
import com.grack.nanojson.JsonWriter
import com.grack.nanojson.JsonParser as NanoParser
import java.util.concurrent.Semaphore
import java.util.concurrent.Executors
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import org.schabi.newpipe.extractor.services.youtube.streams.StreamHttpException
import org.schabi.newpipe.extractor.services.youtube.streams.SessionProvider
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.net.LinkProperties
import android.net.Proxy
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.grack.nanojson.JsonObject
import com.hhst.youtubelite.core.Constants
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Anonymous session provider; no login cookies are read or stored. */
class YoutubeSessionProvider(context: Context, private val http: OkHttpClient,
                             private val diagnostics: ExtractionDiagnostics? = null) : SessionProvider {
    private val networkGeneration = AtomicLong()
    private val lock = Any()
    private val capturePermit = Semaphore(1, true)
    private val cancellation = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "youtube-session-cancellation").apply { isDaemon = true }
    }
    private var identity = ""
    @Volatile private var generation = 0L
    private var config = JsonObject()
    private var configAt = 0L
    private var pageIdentity = ""
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var route = ""
    private val routeLock = Any()
    private var configSource = "same-session-watch"

    init {
        routeChanged()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = routeChanged()
            override fun onLost(network: Network) = routeChanged()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = routeChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = routeChanged()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivity.registerDefaultNetworkCallback(callback)
        } else {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        }
        ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { routeChanged() }
        }, IntentFilter(Proxy.PROXY_CHANGE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun routeChanged() = synchronized(routeLock) {
        val active = connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        val next = "$active:${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}:${connectivity.defaultProxy}"
        if (route != next) {
            route = next
            networkGeneration.incrementAndGet()
        }
    }

    fun capture(videoId: String): YoutubeSession = capture(videoId, false)

    override fun capture(videoId: String, fresh: Boolean): YoutubeSession =
        captureBounded(videoId, fresh, Long.MAX_VALUE, { true })

    /** The configured session when no page read is needed; never touches the network. */
    fun captureCached(): YoutubeSession? = try {
        captureBounded("", false, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), { true }, allowPage = false)
    } catch (_: IOException) { null }

    fun captureBounded(videoId: String, fresh: Boolean, deadline: Long, current: () -> Boolean,
                       allowPage: Boolean = true): YoutubeSession = withPermit(deadline, current) {
        // Snapshot the identity inputs ONCE. scopeHint()/digest recompute UA
        // and route on every call, so comparing digests across calls can
        // mismatch spuriously (UA captured at app start, route flaps) and
        // surface as SESSION_CHANGED_OR_CANCELLED on healthy extractions.
        val ua = Constants.userAgent()
        val capturedRoute = networkGeneration.get()
        val nextIdentity = identityHint("", ua, capturedRoute)
        var capturedGeneration: Long
        val fetchPage: Boolean
        synchronized(lock) {
            if (nextIdentity != identity) { identity = nextIdentity; generation++; configAt = 0 }
            if (fresh) { generation++; configAt = 0 }
            capturedGeneration = generation
            // Innertube account/visitor/player configuration belongs to the session.
            // A different video needs a different /player request, not another page
            // or minter. Browser identity changes and explicit recovery still refresh it.
            fetchPage = fresh || configAt == 0L || System.currentTimeMillis() - configAt >= CONFIG_TTL_MS
        }
        if (fetchPage && !allowPage) throw IOException("SESSION_PAGE_REQUIRED")
        if (fetchPage) {
            val html = readPage("$ORIGIN/watch?v=$videoId&app=desktop", ua, emptyMap(), deadline) {
                current() && generation == capturedGeneration && networkGeneration.get() == capturedRoute
            }
            val parseStarted = System.nanoTime()
            val parsed = parseConfig(html)
            diagnostics?.event("page-parse", "WEB", "configuration", (System.nanoTime() - parseStarted) / 1_000_000, 200)
            if (!parsed.has("INNERTUBE_CONTEXT") || !parsed.has("INNERTUBE_API_KEY")) {
                throw IOException("SESSION_CONFIG_EMPTY")
            }
            val js = parsed.getString("PLAYER_JS_URL") ?: Regex("\"jsUrl\"\\s*:\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (js != null) parsed["PLAYER_JS_URL"] = if (js.startsWith('/')) ORIGIN + js else js
            synchronized(lock) {
                if (generation != capturedGeneration) throw IOException("SESSION_CHANGED_OR_CANCELLED")
                if (configAt != 0L && configurationIdentity(config) != configurationIdentity(parsed)) {
                    generation++
                    capturedGeneration = generation
                }
                config = parsed; configAt = System.currentTimeMillis()
                pageIdentity = identity; configSource = "same-session-watch"
            }
        }
        synchronized(lock) {
        if (pageIdentity != identity || generation != capturedGeneration) throw IOException("SESSION_PAGE_MISMATCH")
        val visitor = config.getString("VISITOR_DATA") ?: config.getObject("INNERTUBE_CONTEXT").getObject("client").getString("visitorData")
        val account = YoutubeSession.Account.ANONYMOUS
        val scope = digest("$identity:$generation:$visitor::${accountIndex(config)}")
        YoutubeSession(scope, account, accountIndex(config), null, null,
            null, visitor, ua,
            capturedRoute, config.getString("PLAYER_JS_URL"), if (fetchPage) configSource else "config-cache:$configSource", config,
            YoutubeSession.Credentials { _ -> "" })
        }
    }

    private fun readPage(initialUrl: String, ua: String, cookies: Map<String, String>, deadline: Long, current: () -> Boolean): String {
        var url = initialUrl
        var transientRetried = false
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        // Consent cookie prevents consent-page redirects that return HTML
        // without any player config (empty ytcfg), which used to surface
        // as a generic playback failure.
        val consentCookies = cookies + mapOf(
            ORIGIN to listOf(cookies[ORIGIN].orEmpty(), "SOCS=CAE=").filter { it.isNotBlank() }.joinToString("; "),
            "https://youtube.com" to "SOCS=CAE=",
            "https://m.youtube.com" to "SOCS=CAE=",
        ).flatMap { (origin, value) -> listOf(origin to value) }.toMap()
        repeat(4) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0 || !current()) throw IOException("EXTRACTION_DEADLINE")
            val call = client.newCall(pageRequest(url, ua, consentCookies))
            call.timeout().timeout(minOf(10_000, remaining), TimeUnit.MILLISECONDS)
            val abort = cancellation.scheduleAtFixedRate({ if (System.nanoTime() >= deadline || !current()) call.cancel() }, 100, 100, TimeUnit.MILLISECONDS)
            val started = System.nanoTime()
            val html = try { call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    diagnostics?.event("request", "session-page", "REDIRECT", (System.nanoTime() - started) / 1_000_000, response.code)
                    url = pageRedirect(url, response.header("Location") ?: throw IOException("PAGE_REDIRECT_MISSING"))
                    null
                } else {
                    if (!response.isSuccessful) throw StreamHttpException("PAGE", response.code, StreamHttpException.parseRetryAfter(response.header("Retry-After"), System.currentTimeMillis()))
                    val source = response.body?.source() ?: throw IOException("PAGE_EMPTY")
                    if (source.request(8L * 1024 * 1024 + 1)) throw IOException("PAGE_TOO_LARGE")
                    source.readUtf8().also { diagnostics?.event("request", "session-page", "GET", (System.nanoTime() - started) / 1_000_000, response.code) }
                }
            } } catch (failure: IOException) {
                // One reset or connect/read timeout is not a session failure. The next iteration still
                // checks cancellation and the deadline, so this cannot outlive the extraction budget.
                val transient = failure is SocketTimeoutException || failure is SocketException
                if (!transient || transientRetried || !current() || System.nanoTime() >= deadline) throw failure
                transientRetried = true
                diagnostics?.event("request", "session-page", "TRANSIENT_RETRY", (System.nanoTime() - started) / 1_000_000, 0)
                null
            } finally { abort.cancel(false) }
            if (!current()) throw IOException("SESSION_CHANGED_OR_CANCELLED")
            if (html != null) return html
        }
        throw IOException("PAGE_REDIRECT_LIMIT")
    }

    private fun <T> withPermit(deadline: Long, current: () -> Boolean, block: () -> T): T {
        while (true) {
            if (System.nanoTime() >= deadline || !current() || Thread.currentThread().isInterrupted) throw IOException("SESSION_CHANGED_OR_CANCELLED")
            if (capturePermit.tryAcquire(100, TimeUnit.MILLISECONDS)) break
        }
        try { return block() } finally { capturePermit.release() }
    }

    override fun isCurrent(session: YoutubeSession): Boolean = synchronized(lock) {
        session.networkGeneration == networkGeneration.get() && session.key == currentKeyLocked() && identity == scopeHintLocked()
    }

    /**
     * Media bytes are fetched from CDN URLs that were already issued. A
     * page-configuration refresh therefore must not abort a read in flight;
     * only a different network route does, because those can invalidate the
     * issued URL or its audience.
     */
    fun isMediaCurrent(session: YoutubeSession): Boolean =
        mediaInvalidationReason(session) == null

    /** A safe reason code, never a cookie, account identifier or network address. */
    fun mediaInvalidationReason(session: YoutubeSession): String? = when {
        session.networkGeneration != networkGeneration.get() -> "network_route_changed"
        else -> null
    }

    fun scopeHint(): String = synchronized(lock) { scopeHintLocked() }

    private fun scopeHintLocked(): String {
        return identityHint("", Constants.userAgent(), networkGeneration.get())
    }

    private fun identityHint(cookies: String, ua: String, route: Long): String {
        return digest(":$ua:$route")
    }

    /** Includes visitor/configuration changes. */
    fun captureStamp(): String = synchronized(lock) { digest("${recoveryScopeLocked()}:$generation") }

    /** Refreshes themselves advance generation; concurrent consumers still share one recovery. */
    fun recoveryScope(): String = synchronized(lock) { recoveryScopeLocked() }

    private fun recoveryScopeLocked(): String {
        return digest("${scopeHintLocked()}:${configurationIdentity(config)}")
    }

    private fun currentKeyLocked(): String {
        val visitor = config.getString("VISITOR_DATA") ?: config.getObject("INNERTUBE_CONTEXT").getObject("client").getString("visitorData")
        return digest("$identity:$generation:$visitor:${config.getString("DATASYNC_ID", "")}:${accountIndex(config)}")
    }

    private fun currentKey(): String = synchronized(lock) { currentKeyLocked() }

    /** Persistent metadata is scoped to the anonymous session. */
    fun accountScope(session: YoutubeSession): String =
        digest("${session.account}:${session.accountIndex}::")

    companion object {
        private fun pageOrigin(url: String): String {
            val uri = runCatching { URI(url) }.getOrElse { throw IOException("PAGE_REDIRECT_INVALID") }
            if (uri.scheme != "https" || uri.host !in setOf("www.youtube.com", "youtube.com", "m.youtube.com", "music.youtube.com")
                || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) throw IOException("PAGE_REDIRECT_ORIGIN")
            return "https://${uri.host}"
        }

        internal fun pageRedirect(base: String, location: String): String =
            (base.toHttpUrlOrNull()?.resolve(location)?.toString() ?: throw IOException("PAGE_REDIRECT_INVALID"))
                .also { pageOrigin(it) }

        internal fun pageRequest(url: String, ua: String, cookies: Map<String, String>): Request = Request.Builder()
            .url(url).header("User-Agent", ua).header("Cookie", cookies[pageOrigin(url)].orEmpty()).build()

        internal fun configurationIdentity(value: JsonObject) = listOf(accountIndex(value),
            value.getString("DELEGATED_SESSION_ID", ""), value.getString("USER_SESSION_ID", ""),
            value.getString("PLAYER_JS_URL", ""), value.getObject("INNERTUBE_CONTEXT").getObject("client").getString("clientVersion", ""),
            value.getString("DATASYNC_ID", ""), value.getString("VISITOR_DATA") ?: value.getObject("INNERTUBE_CONTEXT").getObject("client").getString("visitorData"), value.getBoolean("LOGGED_IN", false), value.getBoolean("IS_PREMIUM", false),
            value.getObject("EXPERIMENT_FLAGS"), value.getObject("EXPERIMENTS_FORCED_FLAGS"),
            value.getObject("WEB_PLAYER_CONTEXT_CONFIGS").values.map { (it as? JsonObject)?.getString("serializedExperimentFlags", "") }.sortedBy { it.orEmpty() }).toString()
        const val ORIGIN = "https://www.youtube.com"

        /**
         * Visitor, player URL and experiment configuration belong to the session, not to one
         * video. Identity changes (UA, route) and explicit recovery already refresh
         * it earlier, so this is only the upper bound for an unchanged session.
         */
        private const val CONFIG_TTL_MS = 15 * 60_000L

        private val CONFIG_FIELDS = setOf("INNERTUBE_CONTEXT", "INNERTUBE_API_KEY", "SESSION_INDEX",
            "DATASYNC_ID", "DELEGATED_SESSION_ID", "USER_SESSION_ID", "VISITOR_DATA", "LOGGED_IN",
            "IS_PREMIUM", "PLAYER_JS_URL", "WEB_PLAYER_CONTEXT_CONFIGS", "EXPERIMENT_FLAGS",
            "EXPERIMENTS_FORCED_FLAGS", "EVENT_ID", "_BG_CHALLENGE", "_BG_CHALLENGE_RAW", "_IS_LIVE", "_VIDEO_ID")
        internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).hexString()

        internal fun accountIndex(config: JsonObject): Int = config["SESSION_INDEX"]?.let {
            it.toString().toIntOrNull()?.takeIf { index -> index >= 0 } ?: throw IOException("SESSION_ACCOUNT_INDEX_INVALID")
        } ?: 0

        internal fun parseConfig(html: String): JsonObject {
            if (html.contains("consent.youtube.com") || html.contains("consent_not_given")) {
                throw IOException("SESSION_CONSENT_REDIRECT")
            }
            val result = JsonObject()
            jsonObjects(html, Regex("ytcfg\\.set\\(\\s*\\{")).forEach(result::putAll)
            val player = jsonObjects(html, Regex("ytInitialPlayerResponse\\s*=\\s*\\{")).firstOrNull()
            if (player != null) {
                result["_IS_LIVE"] = player.getObject("videoDetails").getBoolean("isLiveContent", false)
                player.getObject("videoDetails").getString("videoId")?.let { result["_VIDEO_ID"] = it }
            }
            val initial = jsonObjects(html, Regex("ytInitialData\\s*=\\s*\\{")).firstOrNull()
            val logo = initial?.getObject("topbar")?.getObject("desktopTopbarRenderer")?.getObject("logo")?.getObject("topbarLogoRenderer")
            if (logo != null && (logo.getObject("iconImage").getString("iconType") == "YOUTUBE_PREMIUM_LOGO"
                    || logo.getString("tooltip", "").contains("Premium", true))) result["IS_PREMIUM"] = true
            val rawChallenge = objectPayloads(html, Regex("window\\.ytAtN\\(\\s*\\{")).firstOrNull()
            if (rawChallenge != null && rawChallenge.length <= 256 * 1024) {
                val challenge = runCatching { NanoParser.`object`().from(rawChallenge) }.getOrNull()
                    ?.getObject("R")?.getObject("bgChallenge")
                if (challenge != null && challenge.isNotEmpty()) result["_BG_CHALLENGE"] = challenge
                else result["_BG_CHALLENGE_RAW"] = rawChallenge
            }
            return JsonObject(result.filterKeys { it in CONFIG_FIELDS })
        }

        private fun jsonObjects(html: String, marker: Regex): Sequence<JsonObject> = objectPayloads(html, marker)
            .mapNotNull { runCatching { NanoParser.`object`().from(it) }.getOrNull() }

        // Only capture the argument, without evaluating page code. BgUtils parses
        // the loose JSON challenge in its isolated, network-disabled runtime.
        private fun objectPayloads(html: String, marker: Regex): Sequence<String> = sequence {
            for (match in marker.findAll(html)) {
                val start = html.indexOf('{', match.range.first)
                var depth = 0; var quote: Char? = null; var escaped = false
                for (end in start until html.length) {
                    val c = html[end]
                    if (quote != null) {
                        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
                    } else {
                        if (c == '"' || c == '\'') quote = c
                        if (c == '{') depth++
                        if (c == '}' && --depth == 0) {
                            yield(html.substring(start, end + 1))
                            break
                        }
                    }
                }
            }
        }
    }
}
