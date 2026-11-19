package com.atelier.manim

import android.app.Application
import com.atelier.manim.data.AtelierDatabase
import com.atelier.manim.data.HistoryBlobStore
import com.atelier.manim.data.MediaExportManager
import com.atelier.manim.data.WorkspaceRepository
import com.atelier.manim.runtime.ExecutionCoordinator
import com.atelier.manim.runtime.ProotEngine
import com.atelier.manim.runtime.RuntimeManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.concurrent.thread

class AtelierApp : Application() {

    lateinit var database: AtelierDatabase
        private set
    lateinit var blobStore: HistoryBlobStore
        private set
    lateinit var workspaceRepo: WorkspaceRepository
        private set
    lateinit var mediaExport: MediaExportManager
        private set
    lateinit var runtimeManager: RuntimeManager
        private set
    lateinit var prootEngine: ProotEngine
        private set
    lateinit var coordinator: ExecutionCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecorder()

        database = AtelierDatabase.build(this)
        blobStore = HistoryBlobStore(this)
        workspaceRepo = WorkspaceRepository(this, database.dao(), blobStore)
        mediaExport = MediaExportManager(this)
        runtimeManager = RuntimeManager(this)
        prootEngine = ProotEngine(this, runtimeManager)
        coordinator = ExecutionCoordinator(
            context = this,
            prootEngine = prootEngine,
            runtimeManager = runtimeManager,
            workspaceRepo = workspaceRepo,
            mediaExport = mediaExport,
            dao = database.dao(),
            blobStore = blobStore
        )

        thread(isDaemon = true, name = "atelier-startup-cleanup") {
            runCatching {
                val tmpDir = File(filesDir, "proot_tmp")
                if (tmpDir.exists()) {
                    tmpDir.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
        }
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { threadObj, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash_last.txt").writeText("Thread: ${threadObj.name}\n$sw")
            }
            previous?.uncaughtException(threadObj, throwable)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: AtelierApp
            private set
    }
}
