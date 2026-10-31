package cz.tomas.couriercontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Srdce aplikace. Běží jako foreground služba, takže funguje i se zhasnutým displejem:
 *  - drží spojení s OBS (a volitelně Streamer.botem) a automaticky se znovu připojuje,
 *  - přepíná JÍZDA / PRIVACY (scéna + mikrofon),
 *  - hlídá GPS privacy zóny a zastávky (doručení → ztlumit mikrofon),
 *  - hlídá internet a stav přenosu z DJI kamery.
 */
class ControlService : Service(), ObsClient.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modeLock = Mutex()
    private lateinit var obs: ObsClient
    private lateinit var sb: StreamerBotClient
    private var connectJob: Job? = null
    private var pollJob: Job? = null

    private var obsWasConnected = false
    private var stoppedSinceMs: Long? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var locationListener: LocationListener? = null

    private val settings get() = SettingsStore.state.value
    private val ui get() = CourierState.ui.value

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SettingsStore.load(this)
        obs = ObsClient(this)
        sb = StreamerBotClient { ok -> CourierState.update { it.copy(sbConnected = ok) } }
        goForeground()
        CourierState.update { it.copy(serviceRunning = true) }
        startNetworkWatch()
        startLocation()
        startPolling()
        // Při změně nastavení se znovu připojit
        scope.launch {
            SettingsStore.state
                .map { Triple(it.obsHost, it.obsPort, it.obsPassword) }
                .distinctUntilChanged()
                .collect { reconnectObs() }
        }
        scope.launch {
            SettingsStore.state
                .map { Triple(it.sbEnabled, it.sbHost, it.sbPort) }
                .distinctUntilChanged()
                .collect { sb.close() }
        }
        scope.launch {
            CourierState.ui
                .map { listOf(it.mode, it.obs, it.inZone, it.delivery, it.internetOk, it.streaming) }
                .distinctUntilChanged()
                .collect { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_DRIVE -> setMode(Mode.DRIVE, ModeReason.MANUAL)
            ACTION_PRIVACY -> setMode(Mode.PRIVACY, ModeReason.MANUAL)
            ACTION_EMERGENCY -> {
                vibrate()
                setMode(Mode.PRIVACY, ModeReason.EMERGENCY)
            }
            ACTION_DELIVERY_TOGGLE -> setDelivery(!ui.delivery, auto = false)
            ACTION_RECONNECT -> reconnectObs()
            ACTION_LOCATION_REFRESH -> startLocation()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        CourierState.update { it.copy(serviceRunning = false, obs = ObsConn.DISCONNECTED, gpsActive = false) }
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        locationListener?.let { runCatching { getSystemService(LocationManager::class.java).removeUpdates(it) } }
        obs.close()
        sb.close()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- režimy

    private fun setMode(mode: Mode, reason: ModeReason) {
        CourierState.update { it.copy(mode = mode, modeReason = reason) }
        scope.launch { applyToObs() }
        if (settings.sbEnabled) {
            scope.launch {
                ensureSb()
                sb.doAction(if (mode == Mode.DRIVE) settings.sbActionDrive else settings.sbActionPrivacy)
            }
        }
    }

    /** Pošle požadovaný stav (scéna + mikrofon) do OBS. */
    private suspend fun applyToObs() = modeLock.withLock {
        if (!obs.identified) return@withLock
        val s = settings
        val state = ui
        val scene = if (state.mode == Mode.DRIVE) s.sceneDrive else s.scenePrivacy
        if (scene.isNotBlank()) {
            val r = obs.request("SetCurrentProgramScene", JSONObject().put("sceneName", scene))
            if (!r.ok) alert(ALERT_OBS_ERR, "OBS: scénu „$scene“ nelze přepnout", r.comment ?: "")
        }
        val mute = state.mode == Mode.PRIVACY || state.delivery
        setMic(mute)
    }

    private suspend fun setMic(mute: Boolean) {
        val mic = settings.micInput
        if (mic.isBlank() || !obs.identified) return
        val r = obs.request("SetInputMute", JSONObject().put("inputName", mic).put("inputMuted", mute))
        if (r.ok) CourierState.update { it.copy(micMuted = mute) }
        else alert(ALERT_OBS_ERR, "OBS: mikrofon „$mic“ nenalezen", r.comment ?: "")
    }

    private fun setDelivery(on: Boolean, auto: Boolean) {
        if (ui.delivery == on) return
        CourierState.update { it.copy(delivery = on, deliveryAuto = auto && on) }
        scope.launch { setMic(on || ui.mode == Mode.PRIVACY) }
    }

    // ---------------------------------------------------------------- OBS

    private fun reconnectObs() {
        obs.close()
        connectJob?.cancel()
        startConnectionLoop()
    }

    private fun startConnectionLoop() {
        connectJob = scope.launch {
            var backoff = 2000L
            while (isActive) {
                val s = settings
                if (s.obsHost.isBlank()) {
                    CourierState.update { it.copy(obs = ObsConn.NOT_CONFIGURED) }
                } else if (!obs.isOpen) {
                    CourierState.update { if (it.obs == ObsConn.AUTH_FAILED) it else it.copy(obs = ObsConn.CONNECTING) }
                    obs.connect(s.obsHost, s.obsPort, s.obsPassword)
                }
                if (ui.obs == ObsConn.CONNECTED) backoff = 2000L
                delay(backoff)
                if (ui.obs != ObsConn.CONNECTED) backoff = (backoff * 2).coerceAtMost(15000L)
            }
        }
    }

    override fun onIdentified() {
        obsWasConnected = true
        CourierState.update { it.copy(obs = ObsConn.CONNECTED, obsError = null) }
        cancelAlert(ALERT_OBS)
        scope.launch {
            val v = obs.request("GetVersion")
            if (v.ok) {
                val platform = v.data.optString("platformDescription").ifEmpty { v.data.optString("platform") }
                CourierState.update { it.copy(obsPlatform = prettyPlatform(platform)) }
            }
            val cur = obs.request("GetCurrentProgramScene")
            val scene = cur.data.optString("currentProgramSceneName").ifEmpty { cur.data.optString("sceneName") }
            CourierState.update { it.copy(obsScene = scene.ifEmpty { null }) }
            // Bezpečnost: pokud telefon chce PRIVACY, vynutit ho. Jinak převzít stav z OBS.
            if (ui.mode == Mode.PRIVACY && ui.modeReason != ModeReason.STARTUP) {
                applyToObs()
            } else {
                val m = when (scene) {
                    settings.sceneDrive -> Mode.DRIVE
                    settings.scenePrivacy -> Mode.PRIVACY
                    else -> ui.mode
                }
                CourierState.update { it.copy(mode = m, modeReason = ModeReason.OBS) }
                if (ui.delivery) setMic(true) else refreshMic()
            }
            pollOnce()
        }
    }

    override fun onDisconnected(reason: String, authFailed: Boolean) {
        CourierState.update {
            it.copy(obs = if (authFailed) ObsConn.AUTH_FAILED else ObsConn.DISCONNECTED, obsError = reason, streaming = null, camera = CameraFeed.UNKNOWN)
        }
        if (obsWasConnected) {
            obsWasConnected = false
            alert(ALERT_OBS, "Spojení s OBS ztraceno", "$reason – zkouším se znovu připojit…")
        }
    }

    override fun onEvent(type: String, data: JSONObject) {
        when (type) {
            "CurrentProgramSceneChanged" -> {
                val scene = data.optString("sceneName")
                CourierState.update {
                    val m = when (scene) {
                        settings.sceneDrive -> Mode.DRIVE
                        settings.scenePrivacy -> Mode.PRIVACY
                        else -> it.mode
                    }
                    it.copy(obsScene = scene, mode = m, modeReason = if (m != it.mode) ModeReason.OBS else it.modeReason)
                }
            }
            "InputMuteStateChanged" -> if (data.optString("inputName") == settings.micInput) {
                CourierState.update { it.copy(micMuted = data.optBoolean("inputMuted")) }
            }
            "StreamStateChanged" -> CourierState.update { it.copy(streaming = data.optBoolean("outputActive")) }
            "ExitStarted" -> obs.close()
        }
    }

    private suspend fun refreshMic() {
        val mic = settings.micInput
        if (mic.isBlank()) return
        val r = obs.request("GetInputMute", JSONObject().put("inputName", mic))
        if (r.ok) CourierState.update { it.copy(micMuted = r.data.optBoolean("inputMuted")) }
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                if (obs.identified) pollOnce()
                if (settings.sbEnabled) ensureSb()
                delay(5000)
            }
        }
    }

    private suspend fun pollOnce() {
        val st = obs.request("GetStreamStatus")
        if (st.ok) CourierState.update { it.copy(streaming = st.data.optBoolean("outputActive")) }

        val src = settings.djiSource
        if (src.isBlank()) {
            CourierState.update { it.copy(camera = CameraFeed.UNKNOWN, cameraDetail = "Zdroj není nastaven") }
            return
        }
        val prev = ui.camera
        val media = obs.request("GetMediaInputStatus", JSONObject().put("inputName", src))
        val (feed, detail) = if (media.ok) {
            when (val state = media.data.optString("mediaState")) {
                "OBS_MEDIA_STATE_PLAYING" -> CameraFeed.LIVE to "RTMP · Přenos běží"
                "OBS_MEDIA_STATE_OPENING", "OBS_MEDIA_STATE_BUFFERING" -> CameraFeed.NO_SIGNAL to "RTMP · Navazuji…"
                else -> CameraFeed.NO_SIGNAL to "RTMP · Bez signálu (${state.removePrefix("OBS_MEDIA_STATE_").lowercase()})"
            }
        } else {
            // Není to mediální zdroj – zkusit aspoň, zda je zdroj aktivní
            val act = obs.request("GetSourceActive", JSONObject().put("sourceName", src))
            if (act.ok) {
                if (act.data.optBoolean("videoActive")) CameraFeed.LIVE to "Zdroj aktivní"
                else CameraFeed.NO_SIGNAL to "Zdroj neaktivní"
            } else CameraFeed.NOT_FOUND to "Zdroj „$src“ v OBS nenalezen"
        }
        CourierState.update { it.copy(camera = feed, cameraDetail = detail) }
        if (prev == CameraFeed.LIVE && feed == CameraFeed.NO_SIGNAL) {
            alert(ALERT_CAMERA, "Přenos z DJI kamery přerušen", detail)
        } else if (feed == CameraFeed.LIVE) cancelAlert(ALERT_CAMERA)
    }

    private fun ensureSb() {
        val s = settings
        if (s.sbEnabled && s.sbHost.isNotBlank() && !sb.isOpen) sb.connect(s.sbHost, s.sbPort)
    }

    // ---------------------------------------------------------------- internet

    private fun startNetworkWatch() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                setInternet(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }

            override fun onLost(network: Network) = setInternet(false)
            override fun onUnavailable() = setInternet(false)
        }
        netCallback = cb
        cm.registerDefaultNetworkCallback(cb)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        CourierState.update { it.copy(internetOk = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) }
    }

    private fun setInternet(ok: Boolean) {
        val was = ui.internetOk
        if (was == ok) return
        CourierState.update { it.copy(internetOk = ok) }
        if (!ok) {
            alert(ALERT_NET, "Výpadek internetu!", "Telefon je offline – vysílání a ovládání OBS nefunguje.")
        } else {
            cancelAlert(ALERT_NET)
            alert(ALERT_NET_BACK, "Internet je zpět", "Připojení obnoveno.", silent = true)
            reconnectObs()
        }
    }

    // ---------------------------------------------------------------- GPS

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        CourierState.update { it.copy(gpsPermission = granted) }
        if (!granted || locationListener != null) return
        goForeground()
        val lm = getSystemService(LocationManager::class.java)
        val l = LocationListener { onLocation(it) }
        locationListener = l
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, l, Looper.getMainLooper())
            CourierState.update { it.copy(gpsActive = true) }
        }.onFailure {
            locationListener = null
            CourierState.update { it.copy(gpsActive = false) }
        }
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocation(it) } }
    }

    private fun onLocation(loc: Location) {
        val s = settings
        val speed = if (loc.hasSpeed()) loc.speed else null
        val zone = if (s.gpsPrivacyEnabled) s.zones.firstOrNull { z ->
            val d = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, z.lat, z.lon, d)
            d[0] <= z.radiusM
        } else null
        val prevZone = ui.inZone
        CourierState.update {
            it.copy(lastLat = loc.latitude, lastLon = loc.longitude, speedKmh = speed?.times(3.6f), inZone = zone?.name, gpsActive = true)
        }

        // Privacy zóny
        if (zone != null && prevZone == null && ui.mode == Mode.DRIVE) {
            setMode(Mode.PRIVACY, ModeReason.ZONE)
            alert(ALERT_ZONE, "PRIVACY – zóna ${zone.name}", "Kamera skryta, mikrofon vypnut.")
        } else if (zone == null && prevZone != null && ui.mode == Mode.PRIVACY && ui.modeReason == ModeReason.ZONE) {
            setMode(Mode.DRIVE, ModeReason.ZONE)
            alert(ALERT_ZONE, "JÍZDA – opustil jsi zónu $prevZone", "Kamera znovu aktivní.", silent = true)
        }

        // Doručení: stojím déle než N sekund → ztlumit mikrofon; rozjedu se → zase zapnout
        if (s.autoDeliveryMute && speed != null && ui.mode == Mode.DRIVE) {
            val now = SystemClock.elapsedRealtime()
            if (speed < 0.7f) {
                val since = stoppedSinceMs ?: now.also { stoppedSinceMs = it }
                if (!ui.delivery && now - since >= s.deliverySeconds * 1000L) setDelivery(true, auto = true)
            } else {
                stoppedSinceMs = null
                if (speed > 2.5f && ui.delivery && ui.deliveryAuto) setDelivery(false, auto = true)
            }
        }
    }

    // ---------------------------------------------------------------- notifikace

    private fun goForeground() {
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val type = if (hasLoc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), if (Build.VERSION.SDK_INT >= 34) type else 0)
        } catch (e: Exception) {
            runCatching {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
                )
            }
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val st = CourierState.ui.value
        val title = when {
            st.mode == Mode.PRIVACY -> "PRIVACY · kamera skrytá, mikrofon vypnutý"
            st.delivery -> "JÍZDA · doručení (mikrofon vypnutý)"
            else -> "JÍZDA · kamera aktivní"
        }
        val obsText = when (st.obs) {
            ObsConn.CONNECTED -> "OBS připojeno" + if (st.streaming == true) " · LIVE" else ""
            ObsConn.CONNECTING -> "Připojuji k OBS…"
            ObsConn.NOT_CONFIGURED -> "OBS není nastaveno"
            ObsConn.AUTH_FAILED -> "Špatné heslo k OBS"
            ObsConn.DISCONNECTED -> "OBS odpojeno"
        }
        val text = buildString {
            append(obsText)
            if (!st.internetOk) append(" · BEZ INTERNETU")
            st.inZone?.let { append(" · zóna $it") }
        }
        return NotificationCompat.Builder(this, CourierApp.CH_STATUS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(if (st.mode == Mode.PRIVACY) 0xFFC0392B.toInt() else 0xFF2ECC71.toInt())
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "🛡 PRIVACY", actionIntent(ACTION_EMERGENCY, 1))
            .addAction(0, "🚗 JÍZDA", actionIntent(ACTION_DRIVE, 2))
            .addAction(0, if (st.delivery) "Doručeno" else "Doručuji", actionIntent(ACTION_DELIVERY_TOGGLE, 3))
            .build()
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, req: Int) = PendingIntent.getService(
        this, req, Intent(this, ControlService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alert(id: Int, title: String, text: String, silent: Boolean = false) {
        val n = NotificationCompat.Builder(this, CourierApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(silent)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun cancelAlert(id: Int) = getSystemService(NotificationManager::class.java).cancel(id)

    private fun vibrate() {
        runCatching {
            val effect = android.os.VibrationEffect.createOneShot(150, 255)
            if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(android.os.VibratorManager::class.java).defaultVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.os.Vibrator::class.java).vibrate(effect)
            }
        }
    }

    private fun prettyPlatform(p: String): String = when {
        p.contains("Windows 11", true) -> "Windows 11"
        p.contains("Windows 10", true) -> "Windows 10"
        p.contains("windows", true) -> "Windows"
        p.contains("mac", true) -> "macOS"
        p.contains("linux", true) -> "Linux"
        else -> p
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_DRIVE = "drive"
        const val ACTION_PRIVACY = "privacy"
        const val ACTION_EMERGENCY = "emergency"
        const val ACTION_DELIVERY_TOGGLE = "delivery"
        const val ACTION_RECONNECT = "reconnect"
        const val ACTION_LOCATION_REFRESH = "location"
        const val ACTION_STOP = "stop"

        private const val NOTIF_ID = 1
        private const val ALERT_NET = 10
        private const val ALERT_NET_BACK = 11
        private const val ALERT_OBS = 12
        private const val ALERT_OBS_ERR = 13
        private const val ALERT_CAMERA = 14
        private const val ALERT_ZONE = 15

        fun send(context: Context, action: String) {
            val i = Intent(context, ControlService::class.java).setAction(action)
            if (CourierState.ui.value.serviceRunning) {
                runCatching { context.startService(i) }.onFailure { runCatching { ContextCompat.startForegroundService(context, i) } }
            } else {
                runCatching { ContextCompat.startForegroundService(context, i) }
            }
        }
    }
}
