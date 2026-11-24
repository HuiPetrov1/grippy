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
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    /** Camera-counted reps (0 for anything measured with the scale). */
    @ColumnInfo(defaultValue = "0") val reps: Int = 0,
    /** Each rep as `finishedMs:tookMs:range%`, joined by `;` — see [RepMarks]. */
    @ColumnInfo(defaultValue = "") val repMarks: String = "",
    /** Reps: the weight lifted, kg (from a session plan); null when not given. */
    val loadKg: Double? = null,
) {
    val measureType: MeasureType get() = MeasureType.of(type)
    val marks: List<app.grippy.camera.RepMark> get() = RepMarks.decode(repMarks)
    /** Seconds per rep: the average of the reps the camera timed, else the whole set shared out. */
    val tempoSec: Double
        get() {
            val timed = marks.filter { it.durationMs > 0 }
            return when {
                timed.isNotEmpty() -> timed.map { it.durationMs }.average() / 1000.0
                reps > 0 -> durationSec.toDouble() / reps
                else -> 0.0
            }
        }
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

        /**
         * A camera-counted set. [signal] is the movement the camera followed (0…100 across its range)
         * so the result can draw it; [reps] is the count that stands, which the person may have corrected.
         */
        fun fromReps(
            exerciseId: Long, sessionId: String, hand: Hand, seconds: Int, takenAt: Long,
            signal: List<ForcePoint>, reps: Int, marks: List<app.grippy.camera.RepMark>, bodyWeightKg: Double?,
            loadKg: Double? = null,
        ): MeasurementEntity = MeasurementEntity(
            exerciseId = exerciseId, sessionId = sessionId, type = MeasureType.Reps.key, hand = hand.key,
            durationSec = seconds, takenAt = takenAt,
            samples = MeasureMath.encode(MeasureMath.thin(signal, hz = 12)),
            maxKg = 0.0, minKg = 0.0, avgKg = 0.0, impulse = 0.0, rfd = 0.0, cfKg = 0.0,
            bodyWeightKg = bodyWeightKg, reps = reps, repMarks = RepMarks.encode(marks), loadKg = loadKg,
        )
    }
}

/** Reps as text in the database. */
object RepMarks {
    fun encode(marks: List<app.grippy.camera.RepMark>): String =
        marks.joinToString(";") { "${it.tMs}:${it.durationMs}:${it.amplitude}" }

    fun decode(text: String): List<app.grippy.camera.RepMark> =
        if (text.isBlank()) emptyList() else text.split(';').mapNotNull { part ->
            val n = part.split(':').mapNotNull { it.toIntOrNull() }
            if (n.size < 3) null else app.grippy.camera.RepMark(n[0], n[1], n[2])
        }
}

/** Something planned for a day: a measurement type, maybe for an exercise. */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `LocalDate.toEpochDay()`. */
    val epochDay: Long,
    val type: String,
    val exerciseId: Long? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val measureType: MeasureType get() = MeasureType.of(type)
}

/** Version 2 adds the plans table; nothing measured before is touched. */
val MEASURE_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`epochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `exerciseId` INTEGER, " +
                "`note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
    }
}

/** Version 3 adds the camera-counted reps to a measurement; everything measured before keeps 0 reps. */
val MEASURE_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `measurements` ADD COLUMN `reps` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `measurements` ADD COLUMN `repMarks` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 4: the weight lifted in a camera-counted set. */
val MEASURE_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `measurements` ADD COLUMN `loadKg` REAL")
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

    @Query("SELECT * FROM plans ORDER BY epochDay, createdAt")
    fun plans(): Flow<List<PlanEntity>>

    @Insert suspend fun insert(plan: PlanEntity): Long
    @Query("DELETE FROM plans WHERE id = :id") suspend fun deletePlan(id: Long)

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
    entities = [FolderEntity::class, ExerciseEntity::class, MeasurementEntity::class, PlanEntity::class],
    version = 4,
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
    val plans: StateFlow<List<PlanEntity>> =
        dao.plans().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun addPlan(plan: PlanEntity) = scope.launch { dao.insert(plan) }
    fun deletePlan(id: Long) = scope.launch { dao.deletePlan(id) }

    fun addFolder(name: String) = scope.launch { dao.insert(FolderEntity(name = name.trim())) }

    fun renameFolder(folder: FolderEntity, name: String) =
        scope.launch { dao.update(folder.copy(name = name.trim())) }

    /** The exercises inside go back to My Measures; nothing measured is lost. */
    fun deleteFolder(id: Long) = scope.launch { dao.releaseFolder(id); dao.deleteFolder(id) }

    /** The exercises as stored right now (not the screen's copy, which starts empty). */
    suspend fun currentExercises(): List<ExerciseEntity> = dao.exercises().first()

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
            Room.databaseBuilder(app, MeasureDatabase::class.java, "grippy-measure.db").addMigrations(MEASURE_MIGRATION_1_2, MEASURE_MIGRATION_2_3, MEASURE_MIGRATION_3_4)
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
    val plans: List<PlanEntity> = emptyList(),
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
    val plans by plans.collectAsState()
    return remember(folders, exercises, measurements, plans) { MeasureSnapshot(folders, exercises, measurements, plans) }
}
