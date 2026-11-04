package com.hhst.youtubelite.ui.about

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val sha256: String = "",
    val size: Long = 0L,
)

object UpdateChecker {
    private const val UPDATE_URL = "https://raw.githubusercontent.com/dwicao/Litube/master/update.json"
    private const val FALLBACK_API = "https://api.github.com/repos/dwicao/Litube/releases/latest"

    suspend fun check(client: OkHttpClient, currentVersion: String): UpdateResult {
        val info = fetchUpdateJson(client) ?: fetchFallback(client)
        ?: return UpdateResult.Failure("Empty update response")
        return when {
            StableVersion.isDevelopment(currentVersion) ->
                UpdateResult.Development(info.version)
            StableVersion.isNewer(currentVersion, info.version) ->
                UpdateResult.Available(info)
            else -> UpdateResult.UpToDate(info.version)
        }
    }

    private suspend fun fetchUpdateJson(client: OkHttpClient): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(UPDATE_URL).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body?.string() ?: error("Empty update response")
                val json = JsonParser.parseString(body).asJsonObject
                UpdateInfo(
                    version = json.get("version").asString,
                    apkUrl = json.get("apkUrl").asString,
                    sha256 = json.get("sha256")?.asString.orEmpty(),
                    size = json.get("size")?.asLong ?: 0L,
                )
            }
        }.getOrNull()
    }

    private suspend fun fetchFallback(client: OkHttpClient): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(FALLBACK_API)
                .header("Accept", "application/vnd.github+json").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body?.string() ?: error("Empty release response")
                val json = JsonParser.parseString(body).asJsonObject
                check(!json.get("prerelease").asBoolean && !json.get("draft").asBoolean)
                val tag = json.get("tag_name").asString
                val version = tag.removePrefix("v")
                val apkUrl = json.getAsJsonArray("assets")
                    ?.firstOrNull { it.asJsonObject.get("name").asString.endsWith(".apk") }
                    ?.asJsonObject?.get("browser_download_url")?.asString
                    ?: "https://github.com/dwicao/Litube/releases/download/$tag/Litube-$version.apk"
                UpdateInfo(version = version, apkUrl = apkUrl)
            }
        }.getOrNull()
    }
}

sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    data class UpToDate(val latest: String) : UpdateResult
    data class Development(val latest: String) : UpdateResult
    data class Failure(val message: String) : UpdateResult
}
