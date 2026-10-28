package com.rusty.flick

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val value: String,
    val emoji: String = "",
    /** "url" (opens on the phone) or "text" (shows as a plain text tag). */
    val kind: String = "url",
) {
    val uri: String get() = if (kind == "url" && !value.contains(':')) "https://$value" else value

    fun ndef(): NdefMessage = NdefMessage(
        if (kind == "text") NdefRecord.createTextRecord("en", value) else NdefRecord.createUri(uri)
    )
}

@Serializable
private data class PresetList(val presets: List<Preset> = emptyList())

data class State(val presets: List<Preset>, val activeId: String, val lastSync: Long, val syncError: String?, val beams: Int) {
    val active: Preset? get() = presets.firstOrNull { it.id == activeId }
}

/**
 * Presets live on the Latitude (edited in a browser) and are cached here, so beaming works with
 * no network at all. BeamService reads [state] straight from this object: same process.
 */
object Store {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(State(emptyList(), "", 0, null, 0))
    val state: StateFlow<State> = _state
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("flick", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        val list = runCatching { json.decodeFromString<PresetList>(p.getString("presets", "") ?: "").presets }.getOrDefault(emptyList())
        _state.value = State(list, p.getString("active", "") ?: "", p.getLong("lastSync", 0), null, p.getInt("beams", 0))
        loaded = true
    }

    fun select(ctx: Context, id: String) {
        prefs(ctx).edit().putString("active", id).apply()
        _state.value = _state.value.copy(activeId = id)
    }

    fun countBeam(ctx: Context) {
        val n = _state.value.beams + 1
        prefs(ctx).edit().putInt("beams", n).apply()
        _state.value = _state.value.copy(beams = n)
    }

    suspend fun sync(ctx: Context) {
        load(ctx)
        val result = withWifi(ctx) { wifi -> withContext(Dispatchers.IO) { runCatching { fetch(wifi) } } }
        result.onSuccess { (raw, list) ->
            val now = System.currentTimeMillis()
            // A preset deleted in the editor stops beaming rather than lingering as a ghost.
            val active = _state.value.activeId.takeIf { id -> list.any { it.id == id } } ?: ""
            prefs(ctx).edit().putString("presets", raw).putString("active", active).putLong("lastSync", now).apply()
            _state.value = _state.value.copy(presets = list, activeId = active, lastSync = now, syncError = null)
        }.onFailure {
            _state.value = _state.value.copy(syncError = it.message ?: "Sync failed")
        }
    }

    /**
     * Wear OS keeps Wi-Fi off while the phone is connected over Bluetooth, and the Bluetooth proxy
     * can't reach LAN addresses. Asking for a Wi-Fi network brings the radio up for the sync;
     * releasing it lets the watch drop back to Bluetooth. Falls back to the default network.
     */
    private suspend fun <T> withWifi(ctx: Context, block: suspend (Network?) -> T): T {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val active = cm.getNetworkCapabilities(cm.activeNetwork)
        if (active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return block(cm.activeNetwork)
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val wifi = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) { _, _, _ -> } }
                    }
                    callback = cb
                    val req = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.requestNetwork(req, cb)
                }
            }
            return block(wifi)
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    /** LAN first with a short timeout, then the optional remote address. */
    private fun fetch(wifi: Network?): Pair<String, List<Preset>> {
        val bases = listOf(BuildConfig.SERVER_LAN to 2000, BuildConfig.SERVER_FUNNEL to 7000).filter { it.first.isNotBlank() }
        if (bases.isEmpty()) throw IllegalStateException("No server configured")
        var last: Exception = IllegalStateException("Can't reach the Latitude")
        for ((base, connectMs) in bases) {
            val url = URL("$base/api/presets")
            val conn = (wifi?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            try {
                conn.connectTimeout = connectMs
                conn.readTimeout = 8000
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.SERVER_KEY}")
                val code = conn.responseCode
                if (code == 200) {
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    return raw to json.decodeFromString<PresetList>(raw).presets
                }
                last = IllegalStateException(if (code == 401) "Wrong key" else "HTTP $code")
                if (code == 401) break
            } catch (e: Exception) {
                last = IllegalStateException(if (wifi == null) "No Wi-Fi, can't reach the Latitude" else "Can't reach the Latitude")
            } finally {
                conn.disconnect()
            }
        }
        throw last
    }
}
