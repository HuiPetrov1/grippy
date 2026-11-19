// SPDX-License-Identifier: MPL-2.0
// Grippy: the Measure section's maths. Not part of upstream Get a Grip.

package app.grippy.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeasureMathTests {
    private fun hold(kg: Double, seconds: Double, hz: Int = 10) =
        (0..(seconds * hz).toInt()).map { ForcePoint(it.toDouble() / hz, kg) }

    @Test fun aSteadyHoldAveragesToItsForceAndLoadIsForceTimesTime() {
        val points = hold(6.0, 10.0)
        assertEquals(60.0, MeasureMath.impulse(points), 1e-6)
        assertEquals(6.0, MeasureMath.average(points, 10.0), 1e-6)
        assertEquals(6.0, MeasureMath.maxKg(points))
    }

    /** The screenshot's numbers: 80 kg·s over a 15 s window is a 5.3 kg average. */
    @Test fun averageIsLoadOverTheWindowAskedFor() {
        val points = hold(8.0, 10.0)
        assertEquals(80.0 / 15, MeasureMath.average(points, 15.0), 1e-6)
    }

    @Test fun negativeDriftCountsAsZeroLoadAndZeroMin() {
        val points = listOf(ForcePoint(0.0, -1.0), ForcePoint(1.0, -1.0))
        assertEquals(0.0, MeasureMath.impulse(points))
        assertEquals(0.0, MeasureMath.minKg(points))
    }

    @Test fun rfdIsTheSteepestRiseOverAtLeastATenthOfASecond() {
        val points = listOf(ForcePoint(0.0, 0.0), ForcePoint(0.1, 2.0), ForcePoint(0.2, 10.0), ForcePoint(0.3, 11.0))
        assertEquals(80.0, MeasureMath.rfd(points), 1e-6)
    }

    @Test fun criticalForceIsTheMeanOfTheLastSixReps() {
        val period = CF_WORK_SECONDS + CF_REST_SECONDS
        val points = (0 until CF_REPS).flatMap { rep ->
            val kg = if (rep < CF_REPS - 6) 20.0 else 12.0
            (0..CF_WORK_SECONDS * 10).map { ForcePoint(rep * period + it / 10.0, kg) }
        }
        assertEquals(CF_REPS, MeasureMath.cfReps(points).size)
        assertEquals(12.0, MeasureMath.criticalForce(points), 1e-6)
    }

    @Test fun samplesRoundTripThroughTheirTextForm() {
        val points = listOf(ForcePoint(0.0, 1.25), ForcePoint(0.5, 6.6))
        assertEquals(points, MeasureMath.decode(MeasureMath.encode(points)))
    }

    @Test fun thinningKeepsTheEndsAndCapsTheRate() {
        val points = (0..1000).map { ForcePoint(it / 100.0, it.toDouble()) }
        val thin = MeasureMath.thin(points, hz = 25)
        assertEquals(points.first(), thin.first())
        assertEquals(points.last(), thin.last())
        assertTrue(thin.size <= 10 * 25 + 2)
    }

    @Test fun groupsSplitEnduranceByWindowOnly() {
        assertEquals("Endurance 10 s", GroupKey(MeasureType.Endurance, 10).title)
        assertEquals(GroupKey(MeasureType.Endurance, 15), GroupKey.parse(GroupKey(MeasureType.Endurance, 15).id))
        assertEquals("SF", initials("supinated front raise"))
    }
}
