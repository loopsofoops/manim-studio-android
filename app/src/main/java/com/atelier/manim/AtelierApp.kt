package com.atelier.manim

import android.app.Application
import com.atelier.manim.data.AtelierDatabase
import com.atelier.manim.data.HistoryBlobStore
import com.atelier.manim.data.MediaExportManager
import com.atelier.manim.data.WorkspaceRepository
import com.atelier.manim.runtime.AtelierBridgeServer
import com.atelier.manim.runtime.CrashReporter
import com.atelier.manim.runtime.ExecutionCoordinator
import com.atelier.manim.runtime.OfflineVoiceEngine
import com.atelier.manim.runtime.ProotEngine
import com.atelier.manim.runtime.RuntimeManager
import com.atelier.manim.runtime.StudioInspector
import com.atelier.manim.runtime.StudioSettingsStore
import java.io.File
import kotlin.concurrent.thread

class AtelierApp : Application() {

    lateinit var settingsStore: StudioSettingsStore
        private set
    lateinit var database: AtelierDatabase
        private set
    lateinit var blobStore: HistoryBlobStore
        private set
    lateinit var workspaceRepo: WorkspaceRepository
        private set
    lateinit var mediaExport: MediaExportManager
        private set
    lateinit var crashReporter: CrashReporter
        private set
    lateinit var runtimeManager: RuntimeManager
        private set
    lateinit var voiceEngine: OfflineVoiceEngine
        private set
    lateinit var prootEngine: ProotEngine
        private set
    lateinit var coordinator: ExecutionCoordinator
        private set
    lateinit var inspector: StudioInspector
        private set
    lateinit var bridgeServer: AtelierBridgeServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        settingsStore = StudioSettingsStore(this)
        database = AtelierDatabase.build(this)
        blobStore = HistoryBlobStore(this)
        workspaceRepo = WorkspaceRepository(this, database.dao(), blobStore)
        mediaExport = MediaExportManager(this)
        crashReporter = CrashReporter(this, mediaExport, settingsStore)
        crashReporter.installUncaughtExceptionHandler()

        runtimeManager = RuntimeManager(this)
        runtimeManager.deployAtelierSdk()
        voiceEngine = OfflineVoiceEngine(this, runtimeManager, settingsStore)

        prootEngine = ProotEngine(
            context = this,
            runtimeManager = runtimeManager,
            threadsProvider = { settingsStore.computeProfile.value.threads },
            bridgeInfoProvider = { if (::bridgeServer.isInitialized) bridgeServer.port to bridgeServer.token else 0 to "" }
        )
        coordinator = ExecutionCoordinator(
            context = this,
            prootEngine = prootEngine,
            runtimeManager = runtimeManager,
            workspaceRepo = workspaceRepo,
            mediaExport = mediaExport,
            dao = database.dao(),
            blobStore = blobStore,
            crashReporter = crashReporter,
            onBeforeHeavyRender = { voiceEngine.release() }
        )
        inspector = StudioInspector(
            context = this,
            runtimeManager = runtimeManager,
            settingsStore = settingsStore,
            workspaceRepo = workspaceRepo,
            coordinator = coordinator,
            crashReporter = crashReporter,
            dao = database.dao(),
            bridgePortProvider = { if (::bridgeServer.isInitialized) bridgeServer.port else 0 },
            voiceEngine = voiceEngine
        )
        bridgeServer = AtelierBridgeServer(
            runtimeManager = runtimeManager,
            settingsStore = settingsStore,
            workspaceRepo = workspaceRepo,
            coordinator = coordinator,
            crashReporter = crashReporter,
            inspector = inspector,
            mediaExport = mediaExport,
            voiceEngine = voiceEngine
        )
        bridgeServer.start()

        thread(isDaemon = true, name = "atelier-startup-tasks") {
            runCatching {
                val tmpDir = File(filesDir, "proot_tmp")
                if (tmpDir.exists()) tmpDir.listFiles()?.forEach { it.deleteRecursively() }
                runtimeManager.cleanRootfsTmpSafe()
                crashReporter.checkPreviousSessionExits()
            }
        }
    }

    companion object {
        @Volatile
        lateinit var instance: AtelierApp
            private set
    }
}
