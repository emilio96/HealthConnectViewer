package com.emilio.healthconnectviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DataMixingTest {

    private val start: Instant = Instant.parse("2026-08-01T00:00:00Z")
    private val dayEnd: Instant = start.plusSeconds(24 * 3600)

    private fun sample(minutesFromStart: Long, value: Double, origin: String = "a", estimated: Boolean = false) =
        FlatSample(start.plusSeconds(minutesFromStart * 60), value, origin, estimated)

    @Test
    fun `a day at fifteen minute buckets yields ninety six points`() {
        val points = groupSamples(emptyList(), AppDataType.STEPS, start, dayEnd, 15)
        assertEquals(96, points.size)
    }

    @Test
    fun `a period that is not a whole number of buckets still covers the end`() {
        val end = start.plusSeconds(70 * 60) // 70 minutes at 30-minute buckets
        val points = groupSamples(emptyList(), AppDataType.STEPS, start, end, 30)
        assertEquals(3, points.size)
    }

    @Test
    fun `samples land in the bucket that contains them`() {
        val samples = listOf(
            sample(0, 10.0),
            sample(14, 20.0),   // still the first bucket
            sample(15, 5.0)     // first sample of the second bucket
        )
        val points = groupSamples(samples, AppDataType.STEPS, start, dayEnd, 15)

        assertEquals(30.0, points[0].mixedValue!!, 0.001)
        assertEquals(5.0, points[1].mixedValue!!, 0.001)
    }

    @Test
    fun `a sample exactly on the end instant is excluded`() {
        val samples = listOf(FlatSample(dayEnd, 99.0, "a"))
        val points = groupSamples(samples, AppDataType.STEPS, start, dayEnd, 15)
        assertTrue(points.all { it.mixedValue == null })
    }

    @Test
    fun `samples before the window are ignored`() {
        val samples = listOf(FlatSample(start.minusSeconds(60), 99.0, "a"))
        val points = groupSamples(samples, AppDataType.STEPS, start, dayEnd, 15)
        assertNull(points.first().mixedValue)
    }

    @Test
    fun `cumulative types sum within an origin and average across origins`() {
        val samples = listOf(
            sample(1, 100.0, "ring"),
            sample(2, 100.0, "ring"),   // ring: 200 in this bucket
            sample(3, 400.0, "phone")   // phone: 400
        )
        val points = groupSamples(samples, AppDataType.STEPS, start, dayEnd, 15)

        assertEquals(200.0, points[0].valuesByOrigin["ring"]!!, 0.001)
        assertEquals(400.0, points[0].valuesByOrigin["phone"]!!, 0.001)
        assertEquals(300.0, points[0].mixedValue!!, 0.001)
    }

    @Test
    fun `non cumulative types average within an origin`() {
        val samples = listOf(
            sample(1, 60.0, "ring"),
            sample(2, 80.0, "ring")
        )
        val points = groupSamples(samples, AppDataType.HEART_RATE, start, dayEnd, 15)
        assertEquals(70.0, points[0].valuesByOrigin["ring"]!!, 0.001)
    }

    @Test
    fun `a measured zero counts towards the mix instead of being discarded`() {
        // The regression this guards: a source reporting "no steps this quarter
        // hour" used to be filtered out, so the mix reported the other source's
        // figure as if both agreed on it.
        val samples = listOf(
            sample(1, 0.0, "ring"),
            sample(2, 500.0, "phone")
        )
        val points = groupSamples(samples, AppDataType.STEPS, start, dayEnd, 15)

        assertEquals(0.0, points[0].valuesByOrigin["ring"]!!, 0.001)
        assertEquals(250.0, points[0].mixedValue!!, 0.001)
    }

    @Test
    fun `an empty bucket is null rather than zero`() {
        val points = groupSamples(listOf(sample(1, 10.0)), AppDataType.STEPS, start, dayEnd, 15)
        assertEquals(10.0, points[0].mixedValue!!, 0.001)
        assertNull(points[1].mixedValue)
    }

    @Test
    fun `min and max are carried for range bars`() {
        val samples = listOf(
            sample(1, 55.0, "ring"),
            sample(2, 95.0, "ring"),
            sample(3, 70.0, "phone")
        )
        val points = groupSamples(samples, AppDataType.HEART_RATE, start, dayEnd, 15)

        assertEquals(55.0, points[0].minValue!!, 0.001)
        assertEquals(95.0, points[0].maxValue!!, 0.001)
    }

    @Test
    fun `only the interpolated origin is flagged, not the whole bucket`() {
        val samples = listOf(
            sample(1, 100.0, "phone", estimated = true),
            sample(2, 100.0, "ring")
        )
        val points = groupSamples(samples, AppDataType.STEPS, start, dayEnd, 15)

        assertTrue(points[0].isEstimated)
        assertEquals(setOf("phone"), points[0].estimatedOrigins)
        assertFalse(points[1].isEstimated)
    }

    @Test
    fun `an inverted window produces nothing rather than throwing`() {
        assertTrue(groupSamples(emptyList(), AppDataType.STEPS, dayEnd, start, 15).isEmpty())
    }

    @Test
    fun `bucketing a busy day stays linear`() {
        // 170k samples is a plausible day of second-by-second heart rate from two
        // devices. The previous quadratic implementation re-scanned the whole list
        // once per bucket; this must complete comfortably inside a test run.
        val samples = (0 until 170_000).map {
            FlatSample(start.plusSeconds(it.toLong() % 86_400), 70.0, if (it % 2 == 0) "a" else "b")
        }
        val points = groupSamples(samples, AppDataType.HEART_RATE, start, dayEnd, 15)
        assertEquals(96, points.size)
        assertTrue(points.all { it.mixedValue != null })
    }
}
