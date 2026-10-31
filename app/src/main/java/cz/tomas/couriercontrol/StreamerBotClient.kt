package cz.tomas.couriercontrol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Klient pro WebSocket server Streamer.botu (Servers/Clients → WebSocket Server).
 * Umí spustit akci podle názvu (DoAction). Autentizaci ve Streamer.botu nech vypnutou,
 * spojení stejně jde jen přes domácí síť / Tailscale.
 */
class StreamerBotClient(private val onState: (Boolean) -> Unit) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false
        private set

    fun connect(host: String, port: Int) {
        close()
        ws = http.newWebSocket(Request.Builder().url("ws://$host:$port/").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== ws) return
                connected = true; onState(true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                ws = null; connected = false; onState(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                ws = null; connected = false; onState(false)
            }
        })
    }

    val isOpen get() = ws != null

    fun doAction(name: String): Boolean {
        if (name.isBlank()) return false
        val msg = JSONObject()
            .put("request", "DoAction")
            .put("id", UUID.randomUUID().toString())
            .put("action", JSONObject().put("name", name))
        return ws?.send(msg.toString()) ?: false
    }

    fun close() {
        val old = ws
        ws = null
        connected = false
        old?.close(1000, "bye")
    }
}
