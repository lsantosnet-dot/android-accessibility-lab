package com.a11ylab.prototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.a11ylab.prototype.capture.CaptureAccessibilityService
import com.a11ylab.prototype.capture.CaptureTuning
import com.a11ylab.prototype.reader.SPEECH_STEP
import com.a11ylab.prototype.reader.SpeechPrefs
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatusScreen(
                        isAccessibilityEnabled = ::isAccessibilityServiceEnabled,
                        isOverlayGranted = { Settings.canDrawOverlays(this) },
                        isNotificationGranted = ::isNotificationPermissionGranted,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenOverlaySettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        },
                        onOpenFloatingWindow = { CaptureAccessibilityService.openOverlay() },
                    )
                }
            }
        }
    }

    /** Notification permission is required (Android 13+) for the lock-screen media card to actually show. */
    private fun isNotificationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentNameString
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        for (name in splitter) {
            if (name.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private val ComponentNameString: String
        get() = "$packageName/${CaptureAccessibilityService::class.java.name}"
}

@Composable
private fun StatusScreen(
    isAccessibilityEnabled: () -> Boolean,
    isOverlayGranted: () -> Boolean,
    isNotificationGranted: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenFloatingWindow: () -> Unit,
) {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled()) }
    var overlayOn by remember { mutableStateOf(isOverlayGranted()) }
    var notificationsOn by remember { mutableStateOf(isNotificationGranted()) }
    var rate by remember { mutableFloatStateOf(SpeechPrefs.rate(context)) }
    var pitch by remember { mutableFloatStateOf(SpeechPrefs.pitch(context)) }
    var mainContentOnly by remember { mutableStateOf(CaptureTuning.readMainContentOnly(context)) }
    var fullTree by remember { mutableStateOf(CaptureTuning.includeNotImportantViews(context)) }
    var pixelOcclusion by remember { mutableStateOf(CaptureTuning.pixelOcclusionFilter(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsOn = granted }

    // Re-check whenever the user comes back from Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = isAccessibilityEnabled()
                overlayOn = isOverlayGranted()
                notificationsOn = isNotificationGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val readyForOverlay = accessibilityOn && overlayOn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Accessibility Lab", style = MaterialTheme.typography.headlineSmall)
        Text("Protótipo de estudo da AccessibilityService do Android.")

        Text(if (accessibilityOn) "Serviço de acessibilidade: ATIVO" else "Serviço de acessibilidade: INATIVO")
        Button(onClick = onOpenAccessibilitySettings) {
            Text("Abrir configurações de acessibilidade")
        }

        Text(if (overlayOn) "Permissão de overlay: CONCEDIDA" else "Permissão de overlay: PENDENTE")
        Button(onClick = onOpenOverlaySettings) {
            Text("Conceder permissão de overlay")
        }

        Button(enabled = readyForOverlay, onClick = onOpenFloatingWindow) {
            Text("Abrir janela flutuante")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text(
                if (notificationsOn) {
                    "Permissão de notificação: CONCEDIDA"
                } else {
                    "Permissão de notificação: PENDENTE — sem ela o card não aparece na tela de bloqueio"
                },
            )
            Button(
                enabled = !notificationsOn,
                onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ) {
                Text("Conceder permissão de notificação")
            }
        }

        Text("Velocidade e tom de voz", style = MaterialTheme.typography.titleMedium)

        SpeechStepperRow(
            label = "Velocidade",
            value = rate,
            onAdjust = { delta -> rate = CaptureAccessibilityService.adjustRate(context, delta) },
        )
        SpeechStepperRow(
            label = "Tom",
            value = pitch,
            onAdjust = { delta -> pitch = CaptureAccessibilityService.adjustPitch(context, delta) },
        )

        Text("Como a tela é lida", style = MaterialTheme.typography.titleMedium)

        SettingSwitchRow(
            label = "Ler só o conteúdo principal",
            explanation = "Lê o container que tem o texto da tela — o corpo do e-mail, o " +
                "artigo — em vez de cada palavra da janela. Desligado, um e-mail aberto vem " +
                "com \"Caixa de entrada\", \"Arquivar\", \"Excluir\", \"Gemini\" e as abas de " +
                "baixo em volta do texto, que é o que fazia parecer que ele estava lendo a " +
                "tela inicial do Gmail. Desligue se quiser ouvir também remetente, assunto e " +
                "botões.",
            checked = mainContentOnly,
            onCheckedChange = {
                mainContentOnly = it
                CaptureAccessibilityService.setReadMainContentOnly(context, it)
            },
        )

        SettingSwitchRow(
            label = "Árvore completa (precisa ficar ligada)",
            explanation = "O Chromium marca todo o conteúdo de WebView como \"não importante " +
                "para acessibilidade\". Sem esta opção o sistema poda essa árvore inteira e o " +
                "corpo do e-mail simplesmente não existe para o app — sobram assunto, ícones " +
                "e abas. Desligue só para gerar um dump de comparação, e ligue de volta.",
            checked = fullTree,
            onCheckedChange = {
                fullTree = it
                CaptureAccessibilityService.setIncludeNotImportantViews(context, it)
            },
        )

        SettingSwitchRow(
            label = "Filtro de oclusão por pixels",
            explanation = "Heurística antiga: descarta um nó cujos limites parecem cobertos " +
                "pelo que os irmãos acima dele pintam. É o filtro que uma vez apagou o corpo " +
                "inteiro de um e-mail por engano. Ligue só para comparar.",
            checked = pixelOcclusion,
            onCheckedChange = {
                pixelOcclusion = it
                CaptureAccessibilityService.setPixelOcclusionFilter(context, it)
            },
        )

        Text(
            "O botão 🧪 dump no painel flutuante salva em Downloads a árvore de acessibilidade " +
                "completa da tela que estiver aberta — é o que permite diagnosticar um app que " +
                "lê errado sem precisar de adb.",
        )

        Text(
            "Depois de ativar o serviço e conceder a permissão de overlay, use o botão " +
                "acima para abrir o painel flutuante — ou simplesmente abra qualquer outro " +
                "app, e ele vai aparecer automaticamente. Enquanto a leitura estiver ativa, " +
                "um card com play/pause e avançar/voltar também aparece na tela de bloqueio.",
        )
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.padding(end = 12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(explanation, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SpeechStepperRow(label: String, value: Float, onAdjust: (delta: Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.padding(end = 8.dp))
        Button(onClick = { onAdjust(-SPEECH_STEP) }) { Text("−") }
        Text(
            String.format(Locale.US, "%.2fx", value),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Button(onClick = { onAdjust(SPEECH_STEP) }) { Text("+") }
    }
}
