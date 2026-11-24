// SPDX-License-Identifier: MPL-2.0
package app.grippy.measure

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** A phone that measured on version 1 keeps everything when the plans table arrives. */
@RunWith(RobolectricTestRunner::class)
class MeasureMigrationTests {
    @Test
    fun version1DatabaseUpgradesWithItsDataAndTakesPlans() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `folderId` INTEGER, `howTo` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `measurements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `exerciseId` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, `type` TEXT NOT NULL, `hand` TEXT NOT NULL, `durationSec` INTEGER NOT NULL, `takenAt` INTEGER NOT NULL, `samples` TEXT NOT NULL, `maxKg` REAL NOT NULL, `minKg` REAL NOT NULL, `avgKg` REAL NOT NULL, `impulse` REAL NOT NULL, `rfd` REAL NOT NULL, `cfKg` REAL NOT NULL, `bodyWeightKg` REAL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_measurements_exerciseId` ON `measurements` (`exerciseId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_measurements_sessionId` ON `measurements` (`sessionId`)")
            db.execSQL("INSERT INTO exercises (name, folderId, howTo, createdAt) VALUES ('supinated front raise', NULL, '', 1)")
            db.execSQL("INSERT INTO measurements (exerciseId, sessionId, type, hand, durationSec, takenAt, samples, maxKg, minKg, avgKg, impulse, rfd, cfKg, bodyWeightKg) " +
                "VALUES (1, 's1', 'endurance', 'left', 10, 1000, '0.00,1.00', 6.0, 1.0, 5.0, 50.0, 10.0, 0.0, NULL)")
            db.version = 1
        }
        val room = Room.databaseBuilder(context, MeasureDatabase::class.java, "migration-test.db")
            .addMigrations(MEASURE_MIGRATION_1_2, MEASURE_MIGRATION_2_3, MEASURE_MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            val dao = room.dao()
            assertEquals(listOf("supinated front raise"), dao.exercises().first().map { it.name })
            assertEquals(1, dao.measurements().first().size)
            dao.insert(PlanEntity(epochDay = LocalDate.of(2026, 11, 26).toEpochDay(), type = "peak", exerciseId = 1))
            assertEquals(1, dao.plans().first().size)
            // What was measured with the scale has no reps; a camera-counted set keeps its own.
            assertEquals(0, dao.measurements().first().single().reps)
            val marks = listOf(app.grippy.camera.RepMark(2_000, 1_800, 100), app.grippy.camera.RepMark(4_100, 2_100, 92))
            dao.insert(listOf(MeasurementEntity.fromReps(1, "s2", Hand.Right, 4, 2000,
                listOf(ForcePoint(0.0, 10.0), ForcePoint(1.0, 90.0)), reps = 3, marks = marks, bodyWeightKg = null, loadKg = 5.0)))
            val saved = dao.measurements().first().last()
            assertEquals(5.0, saved.loadKg!!, 1e-9)
            assertEquals(null, dao.measurements().first().first().loadKg)
            assertEquals(MeasureType.Reps, saved.measureType)
            assertEquals(3, saved.reps)
            assertEquals(marks, saved.marks)
            assertEquals(3.0, saved.headline, 1e-9)
            assertEquals(1.95, saved.tempoSec, 1e-9)
        } finally {
            room.close()
            file.delete()
        }
    }

    @Test
    fun bestStreakCountsTheLongestRunOfDays() {
        val d = LocalDate.of(2026, 11, 1)
        assertEquals(3, bestStreak(setOf(d, d.plusDays(1), d.plusDays(2), d.plusDays(5), d.plusDays(6))))
        assertEquals(0, bestStreak(emptySet()))
    }

    @Test
    fun valueAtInterpolatesBetweenReadings() {
        val points = listOf(ForcePoint(0.0, 0.0), ForcePoint(1.0, 10.0), ForcePoint(2.0, 4.0))
        assertEquals(5.0, valueAt(points, 0.5), 1e-9)
        assertEquals(7.0, valueAt(points, 1.5), 1e-9)
        assertEquals(4.0, valueAt(points, 9.0), 1e-9)
    }
}

/** Peak keeps going after a pull: every pull is counted, the best one counts, Stop ends it. */
class PeakPullTests {
    @Test
    fun peakCountsEveryPullAndKeepsTheBestUntilStop() = runBlocking {
        val run = MeasureRun(MeasureRequest(MeasureType.Peak, listOf(Hand.Left), PEAK_MAX_SECONDS, startAfter = 1))
        val driver = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { run.drive(tare = {}, cue = {}) }
        suspend fun hold(kg: Double, ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { run.receive(kg); kotlinx.coroutines.delay(20) }
        }
        hold(0.0, 1300) // the countdown
        hold(10.0, 400); hold(0.0, 600)
        hold(15.0, 400); hold(0.0, 600)
        hold(12.0, 400); hold(0.0, 600)
        run.finishHand()
        kotlinx.coroutines.withTimeout(3000) { driver.join() }
        assertEquals(listOf(10.0, 15.0, 12.0), run.pullPeaks.toList())
        assertEquals(MeasureRun.Stage.Done, run.stage)
        assertEquals(15.0, MeasureMath.maxKg(run.results.getValue(Hand.Left)), 1e-9)
    }
}
