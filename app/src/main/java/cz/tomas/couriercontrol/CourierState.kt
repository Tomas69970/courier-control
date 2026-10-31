package cz.tomas.couriercontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Mode { DRIVE, PRIVACY }

/** Proč je aktuální režim zapnutý – podle toho se rozhoduje, co smí automatika přepnout zpět. */
enum class ModeReason { STARTUP, MANUAL, EMERGENCY, ZONE, OBS }

enum class ObsConn { NOT_CONFIGURED, CONNECTING, CONNECTED, DISCONNECTED, AUTH_FAILED }

enum class CameraFeed { UNKNOWN, LIVE, NO_SIGNAL, NOT_FOUND }

data class UiState(
    val serviceRunning: Boolean = false,
    val mode: Mode = Mode.PRIVACY,
    val modeReason: ModeReason = ModeReason.STARTUP,
    val obs: ObsConn = ObsConn.NOT_CONFIGURED,
    val obsError: String? = null,
    val obsPlatform: String? = null,
    val obsScene: String? = null,
    val micMuted: Boolean? = null,
    val streaming: Boolean? = null,
    val camera: CameraFeed = CameraFeed.UNKNOWN,
    val cameraDetail: String? = null,
    val internetOk: Boolean = true,
    val gpsActive: Boolean = false,
    val gpsPermission: Boolean = false,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val speedKmh: Float? = null,
    val inZone: String? = null,
    val delivery: Boolean = false,
    val deliveryAuto: Boolean = false,
    val sbConnected: Boolean = false,
)

object CourierState {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    fun update(f: (UiState) -> UiState) = _ui.update(f)
}
