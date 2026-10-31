package cz.tomas.couriercontrol.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object CC {
    val Bg = Color(0xFF000000)
    val Frame = Color(0xFF2A2A2A)
    val Card = Color(0xFF1B1B1B)
    val Tile = Color(0xFF242424)
    val DriveBg = Color(0xFF1B4332)
    val DriveAccent = Color(0xFF2ECC71)
    val PrivacyBg = Color(0xFF4A1515)
    val PrivacyAccent = Color(0xFFFF5A4E)
    val Amber = Color(0xFFF5B041)
    val Text = Color(0xFFF2F2F2)
    val Sub = Color(0xFF9A9A9A)
}

@Composable
fun CourierTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CC.DriveAccent,
            onPrimary = Color.Black,
            background = CC.Bg,
            surface = CC.Card,
            onSurface = CC.Text,
            onBackground = CC.Text,
            error = CC.PrivacyAccent,
        ),
        content = content,
    )
}
