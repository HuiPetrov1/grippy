// SPDX-License-Identifier: MPL-2.0
// Grippy: storage for the Measure section — its own Room database, so upstream's schema and
// migrations stay untouched. Not part of upstream Get a Grip.

package app.grippy.measure

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Null = the built-in "My Measures" folder. */
    val folderId: Long? = null,
    /** The person's own notes on how the exercise is done. */
    val howTo: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** One hand's recording. Both hands measured together share a [sessionId]. */
@Entity(tableName = "measurements", indices = [Index("exerciseId"), Index("sessionId")])
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val sessionId: String,
    val type: String,
    val hand: String,
    /** The recording window in seconds (Critical Force: the whole protocol). */
    val durationSec: Int,
    val takenAt: Long,
    /** `t,kg;t,kg…` — see `MeasureMath.encode`. */
    val samples: String,
    val maxKg: Double,
    val minKg: Double,
    val avgKg: Double,
    val impulse: Double,
    val rfd: Double,
    val cfKg: Double,
    val bodyWeightKg: Double?,
) {
    val measureType: MeasureType get() = MeasureType.of(type)
    val side: Hand get() = Hand.of(hand)
    val headline: Double get() = measureType.headline.of(this)
    val points: List<ForcePoint> get() = MeasureMath.decode(samples)

    companion object {
        fun from(
            exerciseId: Long, sessionId: String, type: MeasureType, hand: Hand, seconds: Int,
            takenAt: Long, points: List<ForcePoint>, bodyWeightKg: Double?,
        ): MeasurementEntity {
            val window = if (type == MeasureType.CriticalForce) CF_TOTAL_SECONDS else seconds
            return MeasurementEntity(
                exerciseId = exerciseId, sessionId = sessionId, type = type.key, hand = hand.key,
                durationSec = window, takenAt = takenAt,
                samples = MeasureMath.encode(MeasureMath.thin(points)),
                maxKg = MeasureMath.maxKg(points),
                minKg = MeasureMath.minKg(points),
                avgKg = MeasureMath.average(points, window.toDouble()),
                impulse = MeasureMath.impulse(points),
                rfd = MeasureMath.rfd(points),
                cfKg = if (type == MeasureType.CriticalForce) MeasureMath.criticalForce(points) else 0.0,
                bodyWeightKg = bodyWeightKg,
            )
        }
    }
}

@Dao
interface MeasureDao {
    @Query("SELECT * FROM folders ORDER BY createdAt")
    fun folders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM exercises ORDER BY createdAt")
    fun exercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM measurements ORDER BY takenAt")
    fun measurements(): Flow<List<MeasurementEntity>>

    @Insert suspend fun insert(folder: FolderEntity): Long
    @Insert suspend fun insert(exercise: ExerciseEntity): Long
    @Insert suspend fun insert(measurements: List<MeasurementEntity>)
    @Update suspend fun update(exercise: ExerciseEntity)
    @Update suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM exercises WHERE id = :id") suspend fun deleteExercise(id: Long)
    @Query("DELETE FROM measurements WHERE exerciseId = :id") suspend fun deleteMeasurementsOf(id: Long)
    @Query("DELETE FROM measurements WHERE sessionId = :sessionId") suspend fun deleteSession(sessionId: String)
    @Query("DELETE FROM folders WHERE id = :id") suspend fun deleteFolder(id: Long)
    @Query("UPDATE exercises SET folderId = NULL WHERE folderId = :id") suspend fun releaseFolder(id: Long)
}

@Database(
    entities = [FolderEntity::class, ExerciseEntity::class, MeasurementEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MeasureDatabase : RoomDatabase() {
    abstract fun dao(): MeasureDao
}

/** The Measure section's single door to its data. Writes are fire-and-forget on its own scope. */
class MeasureStore(private val db: MeasureDatabase) {
    private val dao = db.dao()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val folders: StateFlow<List<FolderEntity>> =
        dao.folders().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val exercises: StateFlow<List<ExerciseEntity>> =
        dao.exercises().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val measurements: StateFlow<List<MeasurementEntity>> =
        dao.measurements().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun addFolder(name: String) = scope.launch { dao.insert(FolderEntity(name = name.trim())) }

    fun renameFolder(folder: FolderEntity, name: String) =
        scope.launch { dao.update(folder.copy(name = name.trim())) }

    /** The exercises inside go back to My Measures; nothing measured is lost. */
    fun deleteFolder(id: Long) = scope.launch { dao.releaseFolder(id); dao.deleteFolder(id) }

    suspend fun addExercise(name: String, folderId: Long?): Long =
        dao.insert(ExerciseEntity(name = name.trim(), folderId = folderId))

    fun updateExercise(exercise: ExerciseEntity) = scope.launch { dao.update(exercise) }

    fun deleteExercise(id: Long) = scope.launch { dao.deleteMeasurementsOf(id); dao.deleteExercise(id) }

    suspend fun save(measurements: List<MeasurementEntity>) = dao.insert(measurements)

    fun deleteSession(sessionId: String) = scope.launch { dao.deleteSession(sessionId) }

    fun close() = db.close()
}

object MeasureStores {
    private var owner: Context? = null
    private var store: MeasureStore? = null

    /** One store per Application instance (Robolectric makes a fresh one per test). */
    @Synchronized
    fun get(context: Context): MeasureStore {
        val app = context.applicationContext
        store?.let { if (owner === app) return it }
        store?.close()
        // Robolectric: in memory, so tests never share or leave a file behind.
        val builder = if (Build.FINGERPRINT == "robolectric") {
            Room.inMemoryDatabaseBuilder(app, MeasureDatabase::class.java).allowMainThreadQueries()
        } else {
            Room.databaseBuilder(app, MeasureDatabase::class.java, "grippy-measure.db")
        }
        return MeasureStore(builder.build()).also { store = it; owner = app }
    }
}

@Composable
fun rememberMeasureStore(): MeasureStore {
    val context = LocalContext.current
    return remember(context.applicationContext) { MeasureStores.get(context) }
}

/** Everything the Measure screens read, collected once per screen. */
class MeasureSnapshot(
    val folders: List<FolderEntity>,
    val exercises: List<ExerciseEntity>,
    val measurements: List<MeasurementEntity>,
) {
    fun exercise(id: Long): ExerciseEntity? = exercises.firstOrNull { it.id == id }
    fun of(exerciseId: Long): List<MeasurementEntity> = measurements.filter { it.exerciseId == exerciseId }
    fun session(sessionId: String): List<MeasurementEntity> =
        measurements.filter { it.sessionId == sessionId }.sortedBy { it.side.ordinal }
}

@Composable
fun MeasureStore.collect(): MeasureSnapshot {
    val folders by folders.collectAsState()
    val exercises by exercises.collectAsState()
    val measurements by measurements.collectAsState()
    return remember(folders, exercises, measurements) { MeasureSnapshot(folders, exercises, measurements) }
}
