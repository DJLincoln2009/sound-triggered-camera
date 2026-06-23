package com.soundcam.camera

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.soundcam.camera.data.AppConfig
import com.soundcam.camera.data.SettingsRepository
import com.soundcam.camera.service.CameraService
import com.soundcam.camera.service.ServiceState
import com.soundcam.camera.ui.theme.SoundCamTheme
import com.soundcam.camera.util.BatteryGuidance
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(applicationContext)
        setContent {
            SoundCamTheme {
                AppRoot(repo)
            }
        }
    }
}

private fun requiredPermissions(): Array<String> {
    val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

@Composable
private fun AppRoot(repo: SettingsRepository) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        })
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result.values.all { it } }

    if (granted) {
        MainScreen(repo)
    } else {
        PermissionScreen(onRequest = { launcher.launch(requiredPermissions()) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(repo: SettingsRepository) {
    val config by repo.config.collectAsState(initial = null)
    val serviceState by ServiceState.state.collectAsState()
    var tab by remember { mutableStateOf(0) }

    Scaffold(topBar = { TopAppBar(title = { Text("SoundCam — Caméra") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Statut") })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Réglages") })
            }
            val cfg = config
            if (cfg == null) {
                Text("Chargement…", Modifier.padding(16.dp))
            } else if (tab == 0) {
                StatusTab(serviceState, cfg)
            } else {
                SettingsTab(repo, cfg)
            }
        }
    }
}

@Composable
private fun StatusTab(state: com.soundcam.camera.service.CameraServiceState, cfg: AppConfig) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Service", style = MaterialTheme.typography.titleMedium)
                Text(if (state.running) "● En fonctionnement" else "○ Arrêté")
                Text(
                    when {
                        state.connected -> "Connecté à : ${state.serverName ?: "PC"}"
                        cfg.pairingToken == null -> "Non appairé — saisissez le code PIN du PC dans Réglages"
                        else -> "Connexion au PC…"
                    },
                )
                if (state.recording) Text("⏺ Enregistrement en cours", color = MaterialTheme.colorScheme.error)
                Text("En attente de transfert : ${state.pendingUploads}")
                state.pairingError?.let { Text("Appairage refusé : $it", color = MaterialTheme.colorScheme.error) }
                state.lastEvent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { CameraService.start(context) }) { Text("Démarrer") }
            OutlinedButton(onClick = { CameraService.stop(context) }) { Text("Arrêter") }
        }

        BatteryGuidance.forCurrentDevice()?.let { g ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optimisation batterie (${g.manufacturer})", style = MaterialTheme.typography.titleMedium)
                    Text(g.message, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    BatteryGuidance.batterySettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("Réglages batterie") }
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    BatteryGuidance.dontKillMyAppIntent(g.dontKillMyAppUrl)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("Guide") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(repo: SettingsRepository, cfg: AppConfig) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(cfg.deviceName) }
    var host by remember { mutableStateOf(cfg.serverHost ?: "") }
    var port by remember { mutableStateOf(cfg.serverPort.toString()) }
    var threshold by remember { mutableStateOf(cfg.threshold) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Appairage", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) },
            label = { Text("Code PIN affiché par le PC") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Button(
            onClick = { scope.launch { repo.setPairingToken(pin); pin = "" } },
            enabled = pin.length == 6,
        ) { Text("Appairer") }
        if (cfg.pairingToken != null) {
            OutlinedButton(onClick = { scope.launch { repo.setPairingToken(null) } }) {
                Text("Oublier l'appairage")
            }
        }

        Text("Identité", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Nom de la caméra") }, singleLine = true,
        )
        OutlinedButton(onClick = { scope.launch { repo.setDeviceName(name) } }) { Text("Enregistrer le nom") }

        Text("Serveur (optionnel — sinon découverte auto)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Adresse IP du PC") }, singleLine = true)
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") }, singleLine = true,
        )
        OutlinedButton(onClick = {
            scope.launch { repo.setServer(host.ifBlank { null }, port.toIntOrNull() ?: 8766, cfg.serverTls) }
        }) { Text("Enregistrer le serveur") }

        Text("Détection sonore — seuil : ${(threshold * 100).toInt()} %", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = threshold, onValueChange = { threshold = it },
            onValueChangeFinished = { scope.launch { repo.setThreshold(threshold) } },
            valueRange = 0.05f..0.95f,
        )

        QualityDropdown(cfg.videoQuality) { q ->
            scope.launch { repo.applyRemoteSettings(com.soundcam.camera.protocol.RemoteSettings(videoQuality = q)) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Classifieur audio (YAMNet)", Modifier.weight(1f))
            Switch(
                checked = cfg.useClassifier,
                onCheckedChange = { v ->
                    scope.launch { repo.applyRemoteSettings(com.soundcam.camera.protocol.RemoteSettings(useClassifier = v)) }
                },
            )
        }
        Text(
            "Les réglages peuvent aussi être pilotés à distance depuis le tableau de bord PC.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityDropdown(current: String, onSelect: (String) -> Unit) {
    val options = listOf("SD_480P" to "480p", "HD_720P" to "720p", "FHD_1080P" to "1080p")
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == current }?.second ?: current
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = "Qualité vidéo : $label", onValueChange = {}, readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Autorisations requises", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "SoundCam a besoin de la caméra, du micro et des notifications pour assurer la " +
                "surveillance avec déclenchement sonore et manuel.",
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Accorder les autorisations") }
    }
}
