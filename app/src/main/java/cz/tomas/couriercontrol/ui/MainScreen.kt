package cz.tomas.couriercontrol.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.tomas.couriercontrol.CameraFeed
import cz.tomas.couriercontrol.Mode
import cz.tomas.couriercontrol.ModeReason
import cz.tomas.couriercontrol.ObsConn
import cz.tomas.couriercontrol.Settings
import cz.tomas.couriercontrol.UiState

@Composable
fun MainScreen(
    st: UiState,
    settings: Settings,
    onDrive: () -> Unit,
    onPrivacy: () -> Unit,
    onDelivery: () -> Unit,
    onObsTile: () -> Unit,
    onGpsTile: () -> Unit,
    onSettings: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxSize()
            .background(CC.Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(st, settings, onSettings)

        if (!st.internetOk) Banner("VÝPADEK INTERNETU – vysílání neběží", CC.PrivacyAccent)
        else if (st.obs == ObsConn.DISCONNECTED && st.obsError != null) Banner("OBS odpojeno: ${st.obsError}", CC.Amber)

        val driveActive = st.mode == Mode.DRIVE
        BigModeButton(
            icon = Icons.Outlined.DirectionsCar,
            title = "JÍZDA",
            subtitle = when {
                driveActive && st.delivery -> "Doručení · mikrofon vypnutý"
                driveActive -> "Kamera aktivní"
                else -> "Klepni pro zapnutí kamery"
            },
            active = driveActive,
            activeBg = CC.DriveBg,
            accent = CC.DriveAccent,
        ) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onDrive()
        }

        BigModeButton(
            icon = Icons.Outlined.Shield,
            title = "PRIVACY",
            subtitle = if (!driveActive) when (st.modeReason) {
                ModeReason.ZONE -> "Privacy zóna${st.inZone?.let { " $it" } ?: ""} · Kamera skrytá · Mikrofon vypnutý"
                ModeReason.EMERGENCY -> "NOUZOVĚ · Kamera skrytá · Mikrofon vypnutý"
                else -> "Kamera skrytá · Mikrofon vypnutý"
            } else "Kamera skrytá · Mikrofon vypnutý",
            active = !driveActive,
            activeBg = CC.PrivacyBg,
            accent = CC.PrivacyAccent,
        ) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onPrivacy()
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val (obsText, obsColor) = when (st.obs) {
                ObsConn.CONNECTED -> (if (st.streaming == true) "Připojeno · LIVE" else "Připojeno") to CC.DriveAccent
                ObsConn.CONNECTING -> "Připojuji…" to CC.Amber
                ObsConn.NOT_CONFIGURED -> "Nenastaveno" to CC.Sub
                ObsConn.AUTH_FAILED -> "Špatné heslo" to CC.PrivacyAccent
                ObsConn.DISCONNECTED -> "Odpojeno" to CC.PrivacyAccent
            }
            Tile(Modifier.weight(1f), Icons.Outlined.SettingsInputAntenna, "OBS", obsText, obsColor, onClick = onObsTile)
            val (gpsText, gpsColor) = when {
                !st.gpsPermission -> "Povolit polohu" to CC.Amber
                st.inZone != null -> "Zóna: ${st.inZone}" to CC.PrivacyAccent
                st.gpsActive -> ("Aktivní" + (st.speedKmh?.let { " · ${it.toInt()} km/h" } ?: "")) to CC.DriveAccent
                else -> "Neaktivní" to CC.Sub
            }
            Tile(Modifier.weight(1f), Icons.Outlined.LocationOn, "GPS", gpsText, gpsColor, onClick = onGpsTile)
        }

        val (camText, camColor) = when (st.camera) {
            CameraFeed.LIVE -> (st.cameraDetail ?: "RTMP · Přenos běží") to CC.DriveAccent
            CameraFeed.NO_SIGNAL -> (st.cameraDetail ?: "RTMP · Bez signálu") to CC.PrivacyAccent
            CameraFeed.NOT_FOUND -> (st.cameraDetail ?: "Zdroj nenalezen") to CC.Amber
            CameraFeed.UNKNOWN -> (if (st.obs == ObsConn.CONNECTED) st.cameraDetail ?: "RTMP · Stav spojení" else "RTMP · čeká na OBS") to CC.Sub
        }
        Tile(Modifier.fillMaxWidth(), Icons.Outlined.Wifi, "PŘENOS KAMERY", camText, camColor) {}

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                Modifier.weight(1f), Icons.Outlined.Language, "INTERNET",
                if (st.internetOk) "Online" else "OFFLINE",
                if (st.internetOk) CC.DriveAccent else CC.PrivacyAccent,
            ) {}
            Tile(
                Modifier.weight(1f), Icons.Outlined.LocalShipping, "DORUČENÍ",
                when {
                    st.delivery && st.deliveryAuto -> "Stojím · mic vypnutý"
                    st.delivery -> "Mic vypnutý · klepni"
                    settings.autoDeliveryMute -> "Auto po ${settings.deliverySeconds} s"
                    else -> "Klepni při doručení"
                },
                if (st.delivery) CC.Amber else CC.Sub,
                highlighted = st.delivery,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelivery()
            }
        }

        Text(
            "PRIVACY funguje okamžitě i z notifikace a z rychlého nastavení (dlaždice PRIVACY).",
            color = CC.Sub, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun Header(st: UiState, settings: Settings, onSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("COURIER CONTROL", color = CC.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            val (label, color) = when (st.obs) {
                ObsConn.CONNECTED -> "CONNECTED" to CC.DriveAccent
                ObsConn.CONNECTING -> "CONNECTING" to CC.Amber
                ObsConn.NOT_CONFIGURED -> "NOT CONFIGURED" to CC.Sub
                ObsConn.AUTH_FAILED -> "AUTH FAILED" to CC.PrivacyAccent
                ObsConn.DISCONNECTED -> "DISCONNECTED" to CC.PrivacyAccent
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(label, color)
                if (st.streaming == true) Pill("● LIVE", CC.PrivacyAccent)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "OBS Studio · ${st.obsPlatform ?: "Windows 10"} · ${settings.platformLabel}",
                color = CC.Sub, fontSize = 13.sp,
            )
        }
        IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Filled.Settings, contentDescription = "Nastavení", tint = CC.Sub)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.35f))
            .border(1.dp, color, RoundedCornerShape(14.dp))
            .padding(12.dp),
    )
}

@Composable
private fun BigModeButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    activeBg: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) activeBg else CC.Card, label = "bg")
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(shape)
            .background(bg)
            .then(if (active) Modifier.border(BorderStroke(1.5.dp, accent.copy(alpha = 0.7f)), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) accent else CC.Text, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, color = CC.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = CC.Text.copy(alpha = 0.9f), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Tile(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    status: String,
    statusColor: Color,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(CC.Tile)
            .then(if (highlighted) Modifier.border(1.dp, statusColor, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = CC.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = CC.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text(status, color = statusColor, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}
