package cz.tomas.couriercontrol

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PrivacyZone(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
)

data class Settings(
    // OBS (obs-websocket v5)
    val obsHost: String = "",
    val obsPort: Int = 4455,
    val obsPassword: String = "",
    val sceneDrive: String = "JÍZDA",
    val scenePrivacy: String = "PRIVACY",
    val micInput: String = "Mikrofon",
    val djiSource: String = "DJI",
    val platformLabel: String = "Kick",
    // Streamer.bot (WebSocket server)
    val sbEnabled: Boolean = false,
    val sbHost: String = "",
    val sbPort: Int = 8080,
    val sbActionDrive: String = "",
    val sbActionPrivacy: String = "",
    // Automatika
    val gpsPrivacyEnabled: Boolean = true,
    val autoDeliveryMute: Boolean = true,
    val deliverySeconds: Int = 30,
    val zones: List<PrivacyZone> = emptyList(),
)

object SettingsStore {
    private const val PREFS = "courier_settings"
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state
    @Volatile private var loaded = false

    fun load(context: Context): Settings {
        if (loaded) return _state.value
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Settings()
        val zones = runCatching {
            val arr = JSONArray(p.getString("zones", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PrivacyZone(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.getInt("radius"))
            }
        }.getOrDefault(emptyList())
        _state.value = Settings(
            obsHost = p.getString("obsHost", d.obsHost)!!,
            obsPort = p.getInt("obsPort", d.obsPort),
            obsPassword = p.getString("obsPassword", d.obsPassword)!!,
            sceneDrive = p.getString("sceneDrive", d.sceneDrive)!!,
            scenePrivacy = p.getString("scenePrivacy", d.scenePrivacy)!!,
            micInput = p.getString("micInput", d.micInput)!!,
            djiSource = p.getString("djiSource", d.djiSource)!!,
            platformLabel = p.getString("platformLabel", d.platformLabel)!!,
            sbEnabled = p.getBoolean("sbEnabled", d.sbEnabled),
            sbHost = p.getString("sbHost", d.sbHost)!!,
            sbPort = p.getInt("sbPort", d.sbPort),
            sbActionDrive = p.getString("sbActionDrive", d.sbActionDrive)!!,
            sbActionPrivacy = p.getString("sbActionPrivacy", d.sbActionPrivacy)!!,
            gpsPrivacyEnabled = p.getBoolean("gpsPrivacyEnabled", d.gpsPrivacyEnabled),
            autoDeliveryMute = p.getBoolean("autoDeliveryMute", d.autoDeliveryMute),
            deliverySeconds = p.getInt("deliverySeconds", d.deliverySeconds),
            zones = zones,
        )
        loaded = true
        return _state.value
    }

    fun save(context: Context, s: Settings) {
        val arr = JSONArray()
        s.zones.forEach {
            arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("radius", it.radiusM))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("obsHost", s.obsHost.trim())
            .putInt("obsPort", s.obsPort)
            .putString("obsPassword", s.obsPassword)
            .putString("sceneDrive", s.sceneDrive)
            .putString("scenePrivacy", s.scenePrivacy)
            .putString("micInput", s.micInput)
            .putString("djiSource", s.djiSource)
            .putString("platformLabel", s.platformLabel)
            .putBoolean("sbEnabled", s.sbEnabled)
            .putString("sbHost", s.sbHost.trim())
            .putInt("sbPort", s.sbPort)
            .putString("sbActionDrive", s.sbActionDrive)
            .putString("sbActionPrivacy", s.sbActionPrivacy)
            .putBoolean("gpsPrivacyEnabled", s.gpsPrivacyEnabled)
            .putBoolean("autoDeliveryMute", s.autoDeliveryMute)
            .putInt("deliverySeconds", s.deliverySeconds)
            .putString("zones", arr.toString())
            .apply()
        _state.value = s.copy(obsHost = s.obsHost.trim(), sbHost = s.sbHost.trim())
    }
}
