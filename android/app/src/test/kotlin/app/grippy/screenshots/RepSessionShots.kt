// SPDX-License-Identifier: MPL-2.0
// Grippy: the camera session screens rendered without a camera phone, for design checks. Not part of upstream.

package app.grippy.screenshots

import app.grippy.measure.ExerciseEntity
import app.grippy.measure.ForcePoint
import app.grippy.measure.MeasureSnapshot
import app.grippy.measure.PlanItem
import app.grippy.measure.RepRun
import app.grippy.measure.RepSessionPreview
import app.grippy.measure.SessionPlan
import app.grippy.measure.sets

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.grippy.camera.RepMark
import app.grippy.camera.Tracking
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.io.File

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepSessionShots(private val theme: String) {
    @get:Rule val compose = createComposeRule()

    private val exercises = listOf(
        ExerciseEntity(1, "Wrist extension (strap)"), ExerciseEntity(2, "Pronation"),
        ExerciseEntity(3, "Supination"), ExerciseEntity(4, "Rising (radial deviation)"),
    )
    private val data = MeasureSnapshot(emptyList(), exercises, emptyList())
    private val plan = SessionPlan(1, "Forearm session", listOf(
        PlanItem(1, 5.0), PlanItem(2, 2.0), PlanItem(3, 2.0), PlanItem(4, 3.0),
    ))
    private fun run() = RepRun(plan.sets { id -> exercises.firstOrNull { it.id == id }?.name }, 3, planName = plan.name)

    private fun shoot(name: String, run: RepRun) {
        RuntimeEnvironment.setQualifiers("w393dp-h852dp-$theme-xhdpi")
        compose.setContent { GetAGripTheme { RepSessionPreview(run, data) } }
        compose.waitForIdle()
        val dir = File(System.getProperty("grippy.screenshots.dir") ?: "build/outputs/roborazzi")
        compose.onRoot().captureRoboImage(File(dir, "$name-${if (theme == "night") "dark" else "light"}.png"))
    }

    private fun done(run: RepRun, i: Int, reps: Int) = RepRun.Result(
        run.sets[i], reps * 1.1, reps * 1.1 + 2, List(reps) { RepMark((it + 1) * 1100, 1100, 100) },
        List(40) { ForcePoint(it / 4.0, 50.0) }, Tracking.Hand, 0L,
    )

    @Test fun counting() {
        val run = run()
        run.preview(RepRun.Stage.Counting, index = 0, counting = 0, count = 12, elapsed = 34.0, restSecondsAgo = 0, done = emptyMap())
        shoot("19a-session-counting", run)
    }

    @Test fun resting() {
        val run = run()
        run.preview(RepRun.Stage.Ready, index = 1, counting = 0, count = 0, elapsed = 0.0, restSecondsAgo = 18, done = mapOf(0 to done(run, 0, 14)))
        shoot("19b-session-rest", run)
    }

    @Test fun firstSet() {
        val run = run()
        run.preview(RepRun.Stage.Ready, index = 0, counting = 0, count = 0, elapsed = 0.0, restSecondsAgo = 0, done = emptyMap())
        shoot("19c-session-ready", run)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf("night", "notnight")
    }
}
