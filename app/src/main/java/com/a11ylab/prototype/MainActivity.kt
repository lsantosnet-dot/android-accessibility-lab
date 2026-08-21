package com.a11ylab.prototype

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.a11ylab.prototype.capture.CaptureAccessibilityService
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
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenFloatingWindow: () -> Unit,
) {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled()) }
    var overlayOn by remember { mutableStateOf(isOverlayGranted()) }
    var rate by remember { mutableFloatStateOf(SpeechPrefs.rate(context)) }
    var pitch by remember { mutableFloatStateOf(SpeechPrefs.pitch(context)) }

    // Re-check whenever the user comes back from Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = isAccessibilityEnabled()
                overlayOn = isOverlayGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val readyForOverlay = accessibilityOn && overlayOn

    Column(
        modifier = Modifier
            .fillMaxSize()
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

        Text(
            "Depois de ativar o serviço e conceder a permissão de overlay, use o botão " +
                "acima para abrir o painel flutuante — ou simplesmente abra qualquer outro " +
                "app, e ele vai aparecer automaticamente.",
        )
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
