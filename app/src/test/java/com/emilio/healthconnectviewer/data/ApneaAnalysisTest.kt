package com.emilio.healthconnectviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ApneaAnalysisTest {

    private val t0: Instant = Instant.parse("2026-08-01T23:00:00Z")

    /** A steady baseline of [value] for [count] readings, one every [everySeconds]. */
    private fun steady(
        from: Instant,
        count: Int,
        value: Double,
        everySeconds: Long = 5
    ): List<Spo2Sample> = (0 until count).map {
        Spo2Sample(from.plusSeconds(it * everySeconds), value)
    }

    @Test
    fun `flat night produces no events`() {
        val samples = steady(t0, 200, 97.0)
        assertTrue(detectDesaturations(samples).isEmpty())
    }

    @Test
    fun `a sustained four point drop is scored as one event`() {
        val baseline = steady(t0, 40, 97.0)
        val dipStart = t0.plusSeconds(40 * 5)
        val dip = steady(dipStart, 6, 93.0)          // 30 s at 93%, i.e. 4 points below
        val recovery = steady(dipStart.plusSeconds(30), 40, 97.0)

        val events = detectDesaturations(baseline + dip + recovery)

        assertEquals(1, events.size)
        assertEquals(93.0, events.first().nadir, 0.001)
        assertTrue(events.first().durationSeconds >= 10)
    }

    @Test
    fun `a dip shorter than ten seconds is not scored`() {
        val baseline = steady(t0, 40, 97.0)
        val dipStart = t0.plusSeconds(40 * 5)
        val dip = listOf(Spo2Sample(dipStart, 92.0))  // a single reading: zero duration
        val recovery = steady(dipStart.plusSeconds(5), 40, 97.0)

        assertTrue(detectDesaturations(baseline + dip + recovery).isEmpty())
    }

    @Test
    fun `a two point drop stays below the threshold`() {
        val baseline = steady(t0, 40, 97.0)
        val dipStart = t0.plusSeconds(40 * 5)
        val dip = steady(dipStart, 6, 95.0)          // only 2 points down
        val recovery = steady(dipStart.plusSeconds(30), 40, 97.0)

        assertTrue(detectDesaturations(baseline + dip + recovery).isEmpty())
    }

    @Test
    fun `a long desaturation does not drag its own baseline down`() {
        // The old implementation used a fixed baseline from the first five samples;
        // a rolling one must still be frozen for the duration of the event, or a
        // long dip re-baselines itself and the event disappears.
        val baseline = steady(t0, 40, 97.0)
        val dipStart = t0.plusSeconds(40 * 5)
        val longDip = steady(dipStart, 60, 92.0)     // five minutes at 92%
        val recovery = steady(dipStart.plusSeconds(300), 40, 97.0)

        val events = detectDesaturations(baseline + longDip + recovery)

        assertEquals(1, events.size)
        assertEquals(97.0, events.first().baseline, 0.001)
        // First to last reading below the recovery level: 60 samples 5 s apart.
        assertEquals(295, events.first().durationSeconds)
    }

    @Test
    fun `two separated dips are scored separately`() {
        val a = steady(t0, 40, 97.0)
        val dip1 = steady(t0.plusSeconds(200), 6, 93.0)
        val b = steady(t0.plusSeconds(230), 40, 97.0)
        val dip2 = steady(t0.plusSeconds(430), 6, 93.0)
        val c = steady(t0.plusSeconds(460), 40, 97.0)

        assertEquals(2, detectDesaturations(a + dip1 + b + dip2 + c).size)
    }

    @Test
    fun `median sampling interval is reported`() {
        val samples = steady(t0, 10, 97.0, everySeconds = 30)
        assertEquals(30.0, medianSamplingSeconds(samples)!!, 0.001)
    }

    @Test
    fun `sparse sampling refuses to report an index`() {
        // One reading every fifteen minutes — what the app used to score as an AHI.
        val samples = (0 until 32).map {
            Spo2Sample(t0.plusSeconds(it * 900L), if (it % 5 == 0) 90.0 else 97.0)
        }
        val analysis = analyseOvernightSpo2(samples, t0, t0.plusSeconds(8 * 3600))

        assertEquals(AnalysisQuality.TOO_SPARSE, analysis.quality)
        assertNull(analysis.indexPerHour)
        assertTrue(analysis.events.isEmpty())
    }

    @Test
    fun `too few readings is reported as insufficient rather than sparse`() {
        val analysis = analyseOvernightSpo2(
            steady(t0, 2, 97.0),
            t0,
            t0.plusSeconds(8 * 3600)
        )
        assertEquals(AnalysisQuality.INSUFFICIENT_DATA, analysis.quality)
        assertNull(analysis.indexPerHour)
    }

    @Test
    fun `dense sampling yields an index per monitored hour`() {
        // Two hours of 10 s sampling with four dips: two events per hour.
        val samples = mutableListOf<Spo2Sample>()
        var cursor = t0
        repeat(4) {
            samples += steady(cursor, 100, 97.0, everySeconds = 10)   // ~16 min flat
            cursor = cursor.plusSeconds(1000)
            samples += steady(cursor, 4, 92.0, everySeconds = 10)     // 30 s dip
            cursor = cursor.plusSeconds(40)
        }
        samples += steady(cursor, 60, 97.0, everySeconds = 10)

        val analysis = analyseOvernightSpo2(samples, t0, t0.plusSeconds(2 * 3600))

        assertEquals(AnalysisQuality.ADEQUATE, analysis.quality)
        assertEquals(4, analysis.events.size)
        assertNotNull(analysis.indexPerHour)
        assertEquals(2.0, analysis.indexPerHour!!, 0.001)
    }

    // -- sleep window resolution ------------------------------------------------

    private val fallbackStart = Instant.parse("2026-08-01T22:00:00Z")
    private val fallbackEnd = Instant.parse("2026-08-02T08:00:00Z")

    @Test
    fun `no sources falls back`() {
        val w = resolveSleepWindow(emptyList(), fallbackStart, fallbackEnd)
        assertEquals(SleepWindowStrategy.FALLBACK, w.strategy)
        assertEquals(fallbackStart, w.start)
    }

    @Test
    fun `a single source keeps its own window`() {
        val start = Instant.parse("2026-08-01T23:10:00Z")
        val end = Instant.parse("2026-08-02T07:20:00Z")
        val w = resolveSleepWindow(listOf(start to end), fallbackStart, fallbackEnd)

        assertEquals(SleepWindowStrategy.SINGLE_SOURCE, w.strategy)
        assertEquals(start, w.start)
        assertEquals(end, w.end)
    }

    @Test
    fun `overlapping sources use the intersection not the average`() {
        // Ring says 23:00-07:00, phone says 01:00-06:00. The average would be
        // 00:00-06:30, a window neither device ever measured.
        val ring = Instant.parse("2026-08-01T23:00:00Z") to Instant.parse("2026-08-02T07:00:00Z")
        val phone = Instant.parse("2026-08-02T01:00:00Z") to Instant.parse("2026-08-02T06:00:00Z")

        val w = resolveSleepWindow(listOf(ring, phone), fallbackStart, fallbackEnd)

        assertEquals(SleepWindowStrategy.INTERSECTION, w.strategy)
        assertEquals(phone.first, w.start)
        assertEquals(phone.second, w.end)
    }

    @Test
    fun `barely overlapping sources fall back to the longest window`() {
        val a = Instant.parse("2026-08-01T23:00:00Z") to Instant.parse("2026-08-02T07:00:00Z")
        val b = Instant.parse("2026-08-02T06:45:00Z") to Instant.parse("2026-08-02T08:00:00Z")

        val w = resolveSleepWindow(listOf(a, b), fallbackStart, fallbackEnd)

        assertEquals(SleepWindowStrategy.LONGEST_NO_OVERLAP, w.strategy)
        assertEquals(a.first, w.start)
        assertEquals(a.second, w.end)
    }

    @Test
    fun `monitored hours come from the window not the samples`() {
        val analysis = analyseOvernightSpo2(
            steady(t0, 100, 97.0),
            t0,
            t0.plus(Duration.ofHours(8))
        )
        assertEquals(8.0, analysis.monitoredHours, 0.001)
    }
}
