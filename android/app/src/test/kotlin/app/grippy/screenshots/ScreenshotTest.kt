// SPDX-License-Identifier: MPL-2.0
// Grippy: renders the real app to PNGs on the JVM for design work. Not part of upstream Get a Grip.

package app.grippy.screenshots

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.cancel
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import app.grippy.measure.ForcePoint
import app.grippy.measure.Hand
import app.grippy.measure.MeasureStores
import app.grippy.measure.MeasureType
import app.grippy.measure.MeasurementEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import run.nuri.getagrip.GetAGripApplication
import run.nuri.getagrip.MainActivity
import java.io.File

/**
 * The real app (real Activity, stores and Room) with the debug demo data: two routines, five
 * weeks of history, a target band and a mock gauge. One PNG per screen and theme in
 * `android/app/build/outputs/roborazzi/`. Run with `scripts/grippy-screenshots.sh`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = GetAGripApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest(private val theme: String) {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var application: GetAGripApplication
    private lateinit var controller: ActivityController<MainActivity>

    @Before fun open() {
        RuntimeEnvironment.setQualifiers("w393dp-h852dp-$theme-xhdpi")
        application = ApplicationProvider.getApplicationContext()
        compose.runOnUiThread {
            val intent = Intent(application, MainActivity::class.java)
                .putExtra("mockDevice", true)
                .putExtra("seedTwoRoutines", true)
                .putExtra("seedHistory", true)
                .putExtra("seedTargetKg", true)
                .putExtra("seedOneToday", true)
            controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        }
        // Seeding runs on the store scope: wait until the seeded "Max pulls" routine has loaded.
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper()
            compose.runOnIdle { application.templates.routines.any { it.name.contains("Max pulls") } }
        }
        seedMeasures()
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            compose.runOnIdle { MeasureStores.get(application).measurements.value.size >= 5 }
        }
        settle()
    }

    @After fun close() {
        if (::controller.isInitialized) compose.runOnUiThread { controller.pause().stop().destroy() }
        if (::application.isInitialized) {
            application.gaugeScope.cancel()
            application.storeScope.cancel()
            application.database.close()
            MeasureStores.get(application).close()
        }
    }

    private fun settle() {
        repeat(3) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            Thread.sleep(150)
        }
    }

    private fun shoot(name: String) {
        settle()
        val dir = File(System.getProperty("grippy.screenshots.dir") ?: "build/outputs/roborazzi")
        compose.onRoot().captureRoboImage(File(dir, "$name-${if (theme == "night") "dark" else "light"}.png"))
    }

    /** An exercise like the one in the Frez screenshots: two Endurance windows, a few days. */
    private fun seedMeasures() = runBlocking {
        val store = MeasureStores.get(application)
        val id = store.addExercise("supinated front raise", null)
        store.addExercise("belt hip pull", null)
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        fun curve(kg: Double, seconds: Int, seed: Int): List<ForcePoint> {
            val r = java.util.Random(seed.toLong())
            return (0..seconds * 10).map { i ->
                val t = i / 10.0
                val ramp = when {
                    t < 0.6 -> 0.0
                    t < 1.0 -> (t - 0.6) / 0.4
                    t > seconds - 0.5 -> 0.0
                    else -> 1.0
                }
                val fade = 1.0 - 0.18 * (t / seconds)
                ForcePoint(t, (kg * 1.15 * ramp * fade + r.nextGaussian() * 0.12 * ramp).coerceAtLeast(0.0))
            }
        }
        fun session(at: Long, type: MeasureType, seconds: Int, left: Double?, right: Double?): List<MeasurementEntity> {
            val sid = java.util.UUID.randomUUID().toString()
            return listOfNotNull(
                left?.let { MeasurementEntity.from(id, sid, type, Hand.Left, seconds, at, curve(it, seconds, 1), 68.0) },
                right?.let { MeasurementEntity.from(id, sid, type, Hand.Right, seconds, at + 60_000, curve(it, seconds, 2), 68.0) },
            )
        }
        store.save(
            session(now - 9 * day, MeasureType.Endurance, 10, 5.6, 5.0) +
                session(now - 5 * day, MeasureType.Endurance, 10, 6.1, 5.2) +
                session(now - 2 * day, MeasureType.Endurance, 10, 6.6, 5.7) +
                session(now - 3_600_000, MeasureType.Endurance, 15, 5.3, null),
        )
    }

    private fun tabTap(label: String) {
        compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        settle()
    }

    private fun tap(text: String) {
        // Routine cards sit side by side, so a label can match twice: the first is the one on screen.
        compose.onAllNodes(hasText(text, substring = true) and hasClickAction())[0].performClick()
        settle()
    }

    @Test fun home() = shoot("01-home")

    @Test fun workoutTab() { tabTap("Workout"); shoot("02-workout-tab") }

    @Test fun history() { tabTap("History"); shoot("03-history") }

    @Test fun measureTab() { tabTap("Measure"); shoot("11-measure") }

    @Test fun exercise() { tabTap("Measure"); tap("supinated front raise"); shoot("12-exercise") }

    @Test fun exerciseSummary() { tabTap("Measure"); tap("supinated front raise"); tap("Summary"); shoot("12b-exercise-summary") }

    @Test fun group() { tabTap("Measure"); tap("supinated front raise"); tap("Endurance 10 s"); shoot("13-group") }

    @Test fun result() { tap("Endurance 15 s"); shoot("14-result") }

    @Test fun startSheet() {
        tabTap("Measure")
        compose.onAllNodes(hasText("Measure") and hasClickAction() and
            !SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))[0].performClick()
        settle()
        shoot("15-start-sheet")
    }

    @Test fun handSheet() {
        tabTap("Measure")
        compose.onAllNodes(hasText("Measure") and hasClickAction() and
            !SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))[0].performClick()
        settle()
        tap("How long you can hold force")
        tap("Start Endurance")
        shoot("16-hand-sheet")
    }

    @Test fun startSheetExercise() {
        openStartSheet()
        compose.onNodeWithText("Create custom exercise").performScrollTo()
        settle()
        shoot("15b-start-sheet-exercise")
    }

    @Test fun peakHandSheet() {
        openStartSheet()
        tap("Start Peak")
        shoot("16b-peak-hand-sheet")
    }

    private fun openStartSheet() {
        tabTap("Measure")
        compose.onAllNodes(hasText("Measure") and hasClickAction() and
            !SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))[0].performClick()
        settle()
    }

    @Test fun peakWaiting() {
        openStartSheet()
        tap("Start Peak")
        tap("Measure left")
        repeat(10) {
            ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(200)
            Thread.sleep(50)
            compose.waitForIdle()
        }
        val dir = File(System.getProperty("grippy.screenshots.dir") ?: "build/outputs/roborazzi")
        compose.onRoot().captureRoboImage(File(dir, "17b-peak-${if (theme == "night") "dark" else "light"}.png"))
    }

    @Test fun measuring() {
        openStartSheet()
        tap("How long you can hold force")
        tap("Start Endurance")
        // The sheet's last field is Start after.
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1] }
            .performTextReplacement("1")
        tap("Measure left")
        // The mock gauge connects, the one-second countdown runs (looper time), and the pull
        // records (wall time).
        repeat(25) {
            ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(200)
            Thread.sleep(100)
            compose.waitForIdle()
        }
        val dir = File(System.getProperty("grippy.screenshots.dir") ?: "build/outputs/roborazzi")
        compose.onRoot().captureRoboImage(File(dir, "17-measuring-${if (theme == "night") "dark" else "light"}.png"))
    }

    @Test fun benchmarks() { tap("Benchmarks"); shoot("04-benchmarks") }

    @Test fun settings() {
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Settings")).performClick()
        settle()
        shoot("05-settings")
    }

    @Test fun routineOverview() { tabTap("Workout"); tap("min"); shoot("06-routine-overview") }

    @Test fun liveGauge() {
        tap("Live Data")
        Thread.sleep(1_500) // the mock gauge streams a few readings
        shoot("07-live-gauge")
    }

    @Test fun workout() {
        tabTap("Workout")
        tap("Start without a gauge")
        Thread.sleep(1_000)
        shoot("08-workout")
    }

    @Test fun workoutWithGauge() {
        tabTap("Workout")
        tap("Connect and start")
        Thread.sleep(4_000) // mock gauge connects, tares and the first pull starts
        shoot("09-workout-gauge")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf("notnight", "night")
    }
}
