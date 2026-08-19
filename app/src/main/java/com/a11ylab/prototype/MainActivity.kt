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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.a11ylab.prototype.capture.CaptureAccessibilityService

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
        return splitter.asSequence().any { it.equals(expected, ignoreCase = true) }
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
) {
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled()) }
    var overlayOn by remember { mutableStateOf(isOverlayGranted()) }

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

        Text(
            "Depois de ativar o serviço e conceder a permissão de overlay, abra " +
                "qualquer outro app: um painel flutuante vai mostrar os eventos de " +
                "acessibilidade capturados em tempo real.",
        )
    }
}
