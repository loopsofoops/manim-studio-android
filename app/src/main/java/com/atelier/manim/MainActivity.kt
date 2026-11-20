package com.atelier.manim

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.atelier.manim.ui.AtelierMainScreen
import com.atelier.manim.ui.AtelierViewModel
import com.atelier.manim.ui.theme.AtelierTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AtelierViewModel by viewModels()

    private var pendingRecordCallback: ((Boolean) -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingRecordCallback?.invoke(granted)
        pendingRecordCallback = null
    }

    private val importFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importFilesFromUris(uris)
    }

    private val exportZipLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.exportWorkspaceZipToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        requestNotificationsOnceOnLaunch()

        setContent {
            val isDark by viewModel.isDarkTheme.collectAsState()
            AtelierTheme(darkTheme = isDark) {
                AtelierMainScreen(
                    viewModel = viewModel,
                    onImportFiles = { runCatching { importFilesLauncher.launch(arrayOf("*/*")) } },
                    onExportWorkspaceZip = { runCatching { exportZipLauncher.launch("atelier_workspace.zip") } },
                    onRequestRecordAudio = { cb ->
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            cb(true)
                        } else {
                            pendingRecordCallback = cb
                            runCatching { recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        }
                    }
                )
            }
        }
    }

    private fun requestNotificationsOnceOnLaunch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = getSharedPreferences("atelier_ui", MODE_PRIVATE)
            if (prefs.getBoolean("notif_asked_v1", false)) return
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                prefs.edit().putBoolean("notif_asked_v1", true).apply()
                runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}
