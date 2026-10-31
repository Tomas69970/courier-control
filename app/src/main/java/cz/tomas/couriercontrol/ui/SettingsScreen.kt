package cz.tomas.couriercontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.tomas.couriercontrol.PrivacyZone
import cz.tomas.couriercontrol.Settings
import cz.tomas.couriercontrol.UiState

@Composable
fun SettingsScreen(
    initial: Settings,
    st: UiState,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onBatteryOptimization: () -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var zoneName by remember { mutableStateOf("") }
    var zoneRadius by remember { mutableStateOf("150") }

    Column(
        Modifier
            .fillMaxSize()
            .background(CC.Bg)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět", tint = CC.Text) }
            Text("Nastavení", color = CC.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Section("Domácí OBS (obs-websocket)")
        Hint("V OBS: Nástroje → Nastavení WebSocket serveru → Povolit. Jako adresu zadej Tailscale IP domácího PC (100.x.x.x) nebo jeho jméno.")
        Field("Adresa PC", s.obsHost) { s = s.copy(obsHost = it) }
        Field("Port", s.obsPort.toString(), number = true) { s = s.copy(obsPort = it.toIntOrNull() ?: 4455) }
        Field("Heslo", s.obsPassword, password = true) { s = s.copy(obsPassword = it) }

        Section("Scény a zdroje v OBS")
        Field("Scéna JÍZDA", s.sceneDrive) { s = s.copy(sceneDrive = it) }
        Field("Scéna PRIVACY", s.scenePrivacy) { s = s.copy(scenePrivacy = it) }
        Field("Zdroj mikrofonu", s.micInput) { s = s.copy(micInput = it) }
        Field("Zdroj DJI kamery (RTMP)", s.djiSource) { s = s.copy(djiSource = it) }
        Field("Platforma (text v hlavičce)", s.platformLabel) { s = s.copy(platformLabel = it) }

        Section("Automatika")
        Toggle("GPS privacy zóny", s.gpsPrivacyEnabled) { s = s.copy(gpsPrivacyEnabled = it) }
        Toggle("Ztlumit mikrofon při doručení (zastávce)", s.autoDeliveryMute) { s = s.copy(autoDeliveryMute = it) }
        Field("Po kolika sekundách stání", s.deliverySeconds.toString(), number = true) {
            s = s.copy(deliverySeconds = (it.toIntOrNull() ?: 30).coerceIn(5, 600))
        }

        Section("Privacy zóny")
        Hint("Po vjezdu do zóny se automaticky zapne PRIVACY, po výjezdu zase JÍZDA.")
        s.zones.forEachIndexed { i, z ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CC.Tile).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(z.name, color = CC.Text, fontWeight = FontWeight.Bold)
                    Text("%.5f, %.5f · %d m".format(z.lat, z.lon, z.radiusM), color = CC.Sub, fontSize = 12.sp)
                }
                IconButton(onClick = { s = s.copy(zones = s.zones.filterIndexed { j, _ -> j != i }) }) {
                    Icon(Icons.Outlined.Delete, "Smazat", tint = CC.PrivacyAccent)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(zoneName, { zoneName = it }, label = { Text("Název (např. Domov)") }, modifier = Modifier.weight(2f), singleLine = true)
            OutlinedTextField(
                zoneRadius, { zoneRadius = it }, label = { Text("Poloměr m") }, modifier = Modifier.weight(1f), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        val canAdd = st.lastLat != null && st.lastLon != null
        OutlinedButton(
            enabled = canAdd,
            onClick = {
                val zone = PrivacyZone(
                    zoneName.ifBlank { "Zóna ${s.zones.size + 1}" },
                    st.lastLat!!, st.lastLon!!, zoneRadius.toIntOrNull()?.coerceIn(30, 5000) ?: 150,
                )
                s = s.copy(zones = s.zones + zone)
                zoneName = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (canAdd) "Přidat zónu na aktuální poloze" else "Čekám na GPS polohu…") }

        Section("Streamer.bot (volitelné)")
        Toggle("Spouštět akce ve Streamer.botu", s.sbEnabled) { s = s.copy(sbEnabled = it) }
        if (s.sbEnabled) {
            Hint("Streamer.bot: Servers/Clients → WebSocket Server → Auto Start, adresa 0.0.0.0, bez autentizace.")
            Field("Adresa PC", s.sbHost) { s = s.copy(sbHost = it) }
            Field("Port", s.sbPort.toString(), number = true) { s = s.copy(sbPort = it.toIntOrNull() ?: 8080) }
            Field("Akce při JÍZDA", s.sbActionDrive) { s = s.copy(sbActionDrive = it) }
            Field("Akce při PRIVACY", s.sbActionPrivacy) { s = s.copy(sbActionPrivacy = it) }
            Text(if (st.sbConnected) "Streamer.bot připojen" else "Streamer.bot nepřipojen", color = if (st.sbConnected) CC.DriveAccent else CC.Sub, fontSize = 12.sp)
        }

        Section("Telefon")
        OutlinedButton(onClick = onBatteryOptimization, modifier = Modifier.fillMaxWidth()) {
            Text("Vypnout optimalizaci baterie (doporučeno)")
        }

        Spacer(Modifier.width(1.dp))
        Button(
            onClick = { onSave(s); onBack() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CC.DriveAccent, contentColor = CC.Bg),
        ) { Text("Uložit", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Section(t: String) = Text(t, color = CC.DriveAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun Hint(t: String) = Text(t, color = CC.Sub, fontSize = 12.sp)

@Composable
private fun Field(label: String, value: String, number: Boolean = false, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        ),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CC.Text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
