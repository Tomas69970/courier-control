package cz.tomas.couriercontrol

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Minimalistický klient pro obs-websocket v5 (OBS Studio 28+).
 * Protokol: https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md
 */
class ObsClient(private val listener: Listener) {

    interface Listener {
        fun onIdentified()
        fun onDisconnected(reason: String, authFailed: Boolean)
        fun onEvent(type: String, data: JSONObject)
    }

    data class Resp(val ok: Boolean, val data: JSONObject, val comment: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var identified = false
        private set
    private var password: String = ""
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Resp>>()

    val isOpen get() = ws != null

    fun connect(host: String, port: Int, password: String) {
        close()
        this.password = password
        val request = Request.Builder().url("ws://$host:$port").build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                handle(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                drop(t.message ?: t.javaClass.simpleName, false)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                // 4009 = AuthenticationFailed
                drop(if (code == 4009) "Špatné heslo k OBS" else "Spojení ukončeno ($code) $reason", code == 4009)
            }
        })
    }

    fun close() {
        val old = ws
        ws = null
        identified = false
        old?.close(1000, "bye")
        failPending()
    }

    private fun drop(reason: String, authFailed: Boolean) {
        ws = null
        identified = false
        failPending()
        listener.onDisconnected(reason, authFailed)
    }

    private fun failPending() {
        pending.values.forEach { it.complete(Resp(false, JSONObject(), "disconnected")) }
        pending.clear()
    }

    private fun handle(socket: WebSocket, text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val d = msg.optJSONObject("d") ?: JSONObject()
        when (msg.optInt("op", -1)) {
            0 -> { // Hello
                val identify = JSONObject()
                    .put("rpcVersion", 1)
                    // General(1) | Scenes(4) | Inputs(8) | Outputs(64) | MediaInputs(256)
                    .put("eventSubscriptions", 1 or 4 or 8 or 64 or 256)
                d.optJSONObject("authentication")?.let { auth ->
                    identify.put("authentication", authString(password, auth.getString("salt"), auth.getString("challenge")))
                }
                socket.send(JSONObject().put("op", 1).put("d", identify).toString())
            }
            2 -> { // Identified
                identified = true
                listener.onIdentified()
            }
            5 -> { // Event
                listener.onEvent(d.optString("eventType"), d.optJSONObject("eventData") ?: JSONObject())
            }
            7 -> { // RequestResponse
                val id = d.optString("requestId")
                val status = d.optJSONObject("requestStatus") ?: JSONObject()
                pending.remove(id)?.complete(
                    Resp(
                        status.optBoolean("result", false),
                        d.optJSONObject("responseData") ?: JSONObject(),
                        status.optString("comment").ifEmpty { null },
                    )
                )
            }
        }
    }

    suspend fun request(type: String, data: JSONObject? = null, timeoutMs: Long = 5000): Resp {
        val socket = ws
        if (socket == null || !identified) return Resp(false, JSONObject(), "not connected")
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Resp>()
        pending[id] = deferred
        val d = JSONObject().put("requestType", type).put("requestId", id)
        if (data != null) d.put("requestData", data)
        if (!socket.send(JSONObject().put("op", 6).put("d", d).toString())) {
            pending.remove(id)
            return Resp(false, JSONObject(), "send failed")
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: Resp(false, JSONObject(), "timeout").also { pending.remove(id) }
    }

    companion object {
        fun authString(password: String, salt: String, challenge: String): String {
            val secret = b64(sha256(password + salt))
            return b64(sha256(secret + challenge))
        }

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
