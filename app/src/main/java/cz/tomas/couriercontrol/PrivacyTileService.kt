package cz.tomas.couriercontrol

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Dlaždice v rychlém nastavení: jedním klepnutím okamžité PRIVACY. */
class PrivacyTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val privacy = CourierState.ui.value.mode == Mode.PRIVACY
        tile.state = if (privacy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "PRIVACY"
        tile.subtitle = if (privacy) "Zapnuto" else "Klepni pro skrytí"
        tile.updateTile()
    }

    override fun onClick() {
        ControlService.send(this, ControlService.ACTION_EMERGENCY)
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            subtitle = "Zapnuto"
            updateTile()
        }
    }
}
