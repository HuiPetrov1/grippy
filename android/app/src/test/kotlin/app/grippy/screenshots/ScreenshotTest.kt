// SPDX-License-Identifier: MPL-2.0
// Grippy: renders the real app to PNGs on the JVM for design work. Not part of upstream Get a Grip.

package app.grippy.screenshots

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.cancel
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
        // Seeding runs on the store scope: wait until the seeded "Max pulls" routine is on Today.
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodes(hasText("Max pulls", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        settle()
    }

    @After fun close() {
        if (::controller.isInitialized) compose.runOnUiThread { controller.pause().stop().destroy() }
        if (::application.isInitialized) {
            application.gaugeScope.cancel()
            application.storeScope.cancel()
            application.database.close()
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

    private fun tap(text: String) {
        // Routine cards sit side by side, so a label can match twice: the first is the one on screen.
        compose.onAllNodes(hasText(text, substring = true) and hasClickAction())[0].performClick()
        settle()
    }

    @Test fun today() = shoot("01-today")

    @Test fun history() { tap("History"); shoot("02-history") }

    @Test fun benchmarks() { tap("Benchmarks"); shoot("03-benchmarks") }

    @Test fun settings() { tap("Settings"); shoot("04-settings") }

    @Test fun settingsBottom() {
        tap("Settings")
        runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText("About")) }
        shoot("05-settings-bottom")
    }

    @Test fun routineOverview() { tap("min"); shoot("06-routine-overview") }

    @Test fun routineEditor() { tap("min"); tap("Edit routine"); shoot("10-routine-editor") }

    @Test fun liveGauge() {
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Live gauge")).performClick()
        settle()
        Thread.sleep(1_500) // the mock gauge streams a few readings
        shoot("07-live-gauge")
    }

    @Test fun workout() {
        tap("Start without a gauge")
        Thread.sleep(1_000)
        shoot("08-workout")
    }

    @Test fun workoutWithGauge() {
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
