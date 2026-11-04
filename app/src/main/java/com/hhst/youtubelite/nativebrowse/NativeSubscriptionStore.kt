package com.hhst.youtubelite.nativebrowse

import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

class NativeSubscriptionStore(
    private val kv: MMKV,
) {
    private val _subscriptions = MutableStateFlow<List<NativeSubscription>>(emptyList())
    val subscriptions: StateFlow<List<NativeSubscription>> = _subscriptions.asStateFlow()

    init {
        _subscriptions.value = read()
    }

    fun isSubscribed(channelId: String): Boolean =
        _subscriptions.value.any { it.channelId == channelId }

    fun subscribe(item: NativeSubscription) {
        if (isSubscribed(item.channelId)) return
        _subscriptions.update { it + item }
        persist()
    }

    fun unsubscribe(channelId: String) {
        _subscriptions.update { list -> list.filterNot { it.channelId == channelId } }
        persist()
    }

    fun toggle(item: NativeSubscription): Boolean {
        return if (isSubscribed(item.channelId)) {
            unsubscribe(item.channelId)
            false
        } else {
            subscribe(item)
            true
        }
    }

    private fun read(): List<NativeSubscription> {
        return runCatching {
            val raw = kv.decodeString(KEY).orEmpty()
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(NativeSubscription(
                        channelId = obj.getString("id"),
                        name = obj.optString("name"),
                        avatarUrl = obj.optString("avatar").takeIf { it.isNotBlank() },
                    ))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun persist() {
        val arr = JSONArray()
        _subscriptions.value.forEach { sub ->
            arr.put(JSONObject()
                .put("id", sub.channelId)
                .put("name", sub.name)
                .put("avatar", sub.avatarUrl.orEmpty()))
        }
        kv.encode(KEY, arr.toString())
    }

    companion object {
        const val KEY = "native_subscriptions_v1"
    }
}
