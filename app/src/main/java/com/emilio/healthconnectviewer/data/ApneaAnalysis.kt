package com.emilio.healthconnectviewer.data

import java.time.Duration
import java.time.Instant

/**
 * Overnight oximetry analysis.
 *
 * This file deliberately contains no Android or Health Connect types: it is pure
 * arithmetic over timestamped SpO2 readings, so it can be unit-tested on the JVM.
 *
 * A word on what this does and does not claim. The oxygen desaturation index
 * computed here approximates the ODI, which correlates with — but is not — the
 * apnea-hypopnea index measured by polysomnography. Consumer wearables sample
 * SpO2 far too sparsely to resolve individual respiratory events, so the analysis
 * refuses to produce a number when the sampling rate cannot support one
 * (see [MAX_USABLE_SAMPLING_SECONDS]). Reporting a confident "severe" risk from a
 * reading every fifteen minutes, as the earlier implementation did, is worse than
 * reporting nothing.
 */

data class Spo2Sample(
    val time: Instant,
    val value: Double
)

data class DesaturationEvent(
    val start: Instant,
    val end: Instant,
    val baseline: Double,
    val nadir: Double
) {
    val dropPercent: Double get() = baseline - nadir
    val durationSeconds: Long get() = Duration.between(start, end).seconds
}

enum class AnalysisQuality {
    /** Enough samples, closely enough spaced, to resolve individual events. */
    ADEQUATE,

    /** Samples exist but are too far apart; events shorter than the gap are invisible. */
    TOO_SPARSE,

    /** Not enough readings to establish a baseline at all. */
    INSUFFICIENT_DATA
}

data class DesaturationAnalysis(
    val events: List<DesaturationEvent>,
    /** Events per hour of monitored sleep, or null when [quality] is not ADEQUATE. */
    val indexPerHour: Double?,
    val quality: AnalysisQuality,
    val medianSamplingSeconds: Double?,
    val monitoredHours: Double,
    val sampleCount: Int
)

/** A drop of at least this many percentage points below baseline opens an event. */
const val DESAT_DROP_PERCENT = 3.0

/** The event closes once the reading climbs back to within this much of baseline. */
private const val RECOVERY_MARGIN_PERCENT = 1.0

/** Respiratory events shorter than this are not scored. */
private const val MIN_EVENT_SECONDS = 10L

/** Baseline is the mean of the readings in this window immediately before the event. */
private const val BASELINE_WINDOW_SECONDS = 120L

/** At least this many pre-event readings are needed before a baseline is trusted. */
private const val MIN_BASELINE_SAMPLES = 3

/**
 * Above this median gap between readings the recording cannot resolve a
 * ten-second event, and no index is reported.
 */
const val MAX_USABLE_SAMPLING_SECONDS = 60.0

/**
 * Detect desaturation events in a night of SpO2 readings.
 *
 * The baseline is a rolling mean of the readings in the two minutes preceding the
 * event and is frozen for the event's duration, so a long desaturation cannot drag
 * its own reference down with it. After an event the window is cleared and the
 * baseline is re-established from scratch.
 */
fun detectDesaturations(
    samples: List<Spo2Sample>,
    minDropPercent: Double = DESAT_DROP_PERCENT,
    minDurationSeconds: Long = MIN_EVENT_SECONDS
): List<DesaturationEvent> {
    if (samples.size < MIN_BASELINE_SAMPLES + 1) return emptyList()

    val sorted = samples.sortedBy { it.time }
    val events = mutableListOf<DesaturationEvent>()
    val baselineWindow = ArrayDeque<Spo2Sample>()

    var i = 0
    while (i < sorted.size) {
        val sample = sorted[i]

        while (baselineWindow.isNotEmpty() &&
            Duration.between(baselineWindow.first().time, sample.time).seconds > BASELINE_WINDOW_SECONDS
        ) {
            baselineWindow.removeFirst()
        }

        val baseline = if (baselineWindow.size >= MIN_BASELINE_SAMPLES) {
            baselineWindow.sumOf { it.value } / baselineWindow.size
        } else {
            null
        }

        if (baseline != null && sample.value <= baseline - minDropPercent) {
            val recoveryLevel = baseline - RECOVERY_MARGIN_PERCENT
            var nadir = sample.value
            var end = sample.time
            var j = i
            while (j < sorted.size && sorted[j].value < recoveryLevel) {
                nadir = minOf(nadir, sorted[j].value)
                end = sorted[j].time
                j++
            }

            if (Duration.between(sample.time, end).seconds >= minDurationSeconds) {
                events.add(
                    DesaturationEvent(
                        start = sample.time,
                        end = end,
                        baseline = baseline,
                        nadir = nadir
                    )
                )
            }

            // The readings inside an event say nothing about the resting level,
            // so the baseline is rebuilt from the readings that follow it.
            baselineWindow.clear()
            i = j
        } else {
            baselineWindow.addLast(sample)
            i++
        }
    }

    return events
}

/** Median gap between consecutive readings, in seconds. Null with fewer than two readings. */
fun medianSamplingSeconds(samples: List<Spo2Sample>): Double? {
    if (samples.size < 2) return null
    val sorted = samples.sortedBy { it.time }
    val gaps = (1 until sorted.size)
        .map { Duration.between(sorted[it - 1].time, sorted[it].time).seconds.toDouble() }
        .sorted()
    val mid = gaps.size / 2
    return if (gaps.size % 2 == 0) (gaps[mid - 1] + gaps[mid]) / 2.0 else gaps[mid]
}

/**
 * Full analysis over a monitored window. Returns a null index rather than a
 * meaningless one when the recording is too sparse to support it.
 */
fun analyseOvernightSpo2(
    samples: List<Spo2Sample>,
    windowStart: Instant,
    windowEnd: Instant
): DesaturationAnalysis {
    val monitoredHours = Duration.between(windowStart, windowEnd)
        .toMinutes()
        .coerceAtLeast(0) / 60.0

    val median = medianSamplingSeconds(samples)

    val quality = when {
        samples.size < MIN_BASELINE_SAMPLES + 1 || median == null -> AnalysisQuality.INSUFFICIENT_DATA
        median > MAX_USABLE_SAMPLING_SECONDS -> AnalysisQuality.TOO_SPARSE
        else -> AnalysisQuality.ADEQUATE
    }

    val events = if (quality == AnalysisQuality.ADEQUATE) detectDesaturations(samples) else emptyList()

    val index = if (quality == AnalysisQuality.ADEQUATE && monitoredHours > 0) {
        events.size / monitoredHours
    } else {
        null
    }

    return DesaturationAnalysis(
        events = events,
        indexPerHour = index,
        quality = quality,
        medianSamplingSeconds = median,
        monitoredHours = monitoredHours,
        sampleCount = samples.size
    )
}

// ---------------------------------------------------------------------------
// Sleep window resolution
// ---------------------------------------------------------------------------

enum class SleepWindowStrategy {
    /** Only one source reported sleep; its own window is used. */
    SINGLE_SOURCE,

    /** Several sources agree on an overlapping stretch; that overlap is used. */
    INTERSECTION,

    /** Several sources, but their windows barely overlap; the longest one is used. */
    LONGEST_NO_OVERLAP,

    /** No sleep records at all; the caller's default window is used. */
    FALLBACK
}

data class ResolvedSleepWindow(
    val start: Instant,
    val end: Instant,
    val strategy: SleepWindowStrategy
)

/** An intersection shorter than this is treated as no real agreement between sources. */
private const val MIN_INTERSECTION_MINUTES = 60L

/**
 * Pick the window to analyse from per-source sleep windows.
 *
 * With several sources the intersection is used: it is the stretch every device
 * agrees the user was asleep, which is the only interval where averaging their
 * heart rate and SpO2 means anything. Averaging the start and end instants across
 * sources — what this used to do — produces a window that no device ever measured.
 */
fun resolveSleepWindow(
    windowsBySource: List<Pair<Instant, Instant>>,
    fallbackStart: Instant,
    fallbackEnd: Instant
): ResolvedSleepWindow {
    if (windowsBySource.isEmpty()) {
        return ResolvedSleepWindow(fallbackStart, fallbackEnd, SleepWindowStrategy.FALLBACK)
    }
    if (windowsBySource.size == 1) {
        val (start, end) = windowsBySource.first()
        return ResolvedSleepWindow(start, end, SleepWindowStrategy.SINGLE_SOURCE)
    }

    val intersectionStart = windowsBySource.maxOf { it.first }
    val intersectionEnd = windowsBySource.minOf { it.second }
    val intersectionMinutes = if (intersectionEnd.isAfter(intersectionStart)) {
        Duration.between(intersectionStart, intersectionEnd).toMinutes()
    } else {
        0L
    }

    if (intersectionMinutes >= MIN_INTERSECTION_MINUTES) {
        return ResolvedSleepWindow(
            intersectionStart,
            intersectionEnd,
            SleepWindowStrategy.INTERSECTION
        )
    }

    val longest = windowsBySource.maxByOrNull {
        Duration.between(it.first, it.second).toMinutes()
    }!!
    return ResolvedSleepWindow(
        longest.first,
        longest.second,
        SleepWindowStrategy.LONGEST_NO_OVERLAP
    )
}
