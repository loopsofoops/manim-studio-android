package com.atelier.manim.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "project_files",
    indices = [Index(value = ["relativePath"], unique = true)]
)
data class ProjectFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relativePath: String,
    val language: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOutputPath: String = "",
    val lastOutputType: String = "NONE",
    val lastOutputMeta: String = ""
)

@Entity(
    tableName = "file_revisions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["fileId", "createdAt"])]
)
data class FileRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: Long,
    val contentHash: String,
    val sizeBytes: Long,
    val lineCount: Int,
    val createdAt: Long,
    val label: String,
    val triggerType: String,
    val outputPath: String = "",
    val outputType: String = "NONE",
    val outputMeta: String = ""
)

@Entity(
    tableName = "render_history",
    indices = [Index(value = ["createdAt"])]
)
data class RenderHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: Long,
    val revisionId: Long,
    val sceneName: String,
    val preset: String,
    val outputPath: String,
    val outputType: String,
    val exportedUri: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val createdAt: Long
)

@Dao
interface AtelierDao {
    @Query("SELECT * FROM project_files ORDER BY updatedAt DESC")
    fun observeFiles(): Flow<List<ProjectFileEntity>>

    @Query("SELECT * FROM project_files ORDER BY updatedAt DESC")
    suspend fun getAllFiles(): List<ProjectFileEntity>

    @Query("SELECT * FROM project_files WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: Long): ProjectFileEntity?

    @Query("SELECT * FROM project_files WHERE relativePath = :path LIMIT 1")
    suspend fun getFileByPath(path: String): ProjectFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: ProjectFileEntity): Long

    @Update
    suspend fun updateFile(file: ProjectFileEntity)

    @Query("DELETE FROM project_files WHERE id = :id")
    suspend fun deleteFileById(id: Long)

    @Query("SELECT * FROM file_revisions WHERE fileId = :fileId ORDER BY createdAt DESC, id DESC")
    fun observeRevisions(fileId: Long): Flow<List<FileRevisionEntity>>

    @Query("SELECT * FROM file_revisions WHERE fileId = :fileId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getLatestRevision(fileId: Long): FileRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevision(rev: FileRevisionEntity): Long

    @Query("SELECT * FROM render_history ORDER BY createdAt DESC LIMIT 100")
    fun observeRenderHistory(): Flow<List<RenderHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRenderHistory(entry: RenderHistoryEntity): Long

    @Query("DELETE FROM render_history")
    suspend fun clearRenderHistory()
}

@Database(
    entities = [
        ProjectFileEntity::class,
        FileRevisionEntity::class,
        RenderHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AtelierDatabase : RoomDatabase() {
    abstract fun dao(): AtelierDao

    companion object {
        fun build(context: Context): AtelierDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AtelierDatabase::class.java,
                "atelier_studio.db"
            ).fallbackToDestructiveMigration().build()
        }
    }
}
