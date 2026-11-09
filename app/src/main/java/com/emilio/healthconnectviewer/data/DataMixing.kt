package com.emilio.healthconnectviewer.data

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor

enum class AppDataType(val id: String, val title: String, val unit: String, val isCumulative: Boolean) {
    STEPS("steps", "Steps", "steps", true),
    HEART_RATE("heart_rate", "Heart Rate", "bpm", false),
    SPO2("spo2", "SpO2", "%", false),
    SLEEP("sleep", "Sleep", "hours", true),
    HRV("hrv", "HRV", "ms", false)
}

data class AggregatedDataPoint(
    val timestamp: Instant,
    val timeLabel: String,
    val valuesByOrigin: Map<String, Double>,
    val mixedValue: Double?,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    /**
     * The origins whose value in this bucket was interpolated rather than measured
     * (see [distributeStepsExcludingSleep]). Per-origin rather than a single flag:
     * only the source that published a daily roll-up is estimated, and the UI should
     * only warn when that source is the one being looked at.
     */
    val estimatedOrigins: Set<String> = emptySet()
) {
    val isEstimated: Boolean get() = estimatedOrigins.isNotEmpty()
}

data class FlatSample(
    val timestamp: Instant,
    val value: Double,
    val origin: String,
    /** Interpolated from a coarser record rather than measured at this instant. */
    val estimated: Boolean = false
)

/**
 * Sleep window exclusion ranges — used to prevent distributing steps
 * evenly during sleep hours when the source provides a single daily aggregate.
 */
data class SleepWindow(
    val start: Instant,
    val end: Instant
)

/** A record spanning more than this is treated as a daily roll-up, not a measurement. */
private const val DAILY_AGGREGATE_MINUTES = 720L

/**
 * Pre-filter step records to deduplicate per-origin.
 * If a source provides BOTH a daily aggregate (>12h duration) AND
 * more granular records, the aggregate is dropped in favour of the
 * granular data (which is more accurate).
 * If a source only provides the daily aggregate, it is kept.
 */
internal fun deduplicateStepRecords(records: List<Any>): List<Any> {
    val stepsRecords = records.filterIsInstance<StepsRecord>()
    if (stepsRecords.isEmpty()) return records

    // Origins that publish granular data as well as a roll-up: for those, and only
    // those, the roll-up is redundant and would double-count.
    val originsWithGranular = stepsRecords
        .filter { Duration.between(it.startTime, it.endTime).toMinutes() <= DAILY_AGGREGATE_MINUTES }
        .map { it.metadata.dataOrigin.packageName }
        .toSet()

    return records.filter { record ->
        if (record !is StepsRecord) return@filter true
        val isAggregate =
            Duration.between(record.startTime, record.endTime).toMinutes() > DAILY_AGGREGATE_MINUTES
        !(isAggregate && record.metadata.dataOrigin.packageName in originsWithGranular)
    }
}

/**
 * Flattens records into individual (timestamp, value, origin) samples.
 *
 * Only daily-aggregate step records (>12h, e.g. Samsung Health) get spread across
 * the timeline, and every sample produced that way is flagged `estimated = true`
 * so the chart can label it instead of passing interpolation off as measurement.
 * All other records keep their own timestamps.
 */
fun flattenRecords(
    records: List<Any>,
    dataType: AppDataType,
    intervalMinutes: Long,
    sleepWindows: List<SleepWindow> = emptyList()
): List<FlatSample> {
    val cleanedRecords = if (dataType == AppDataType.STEPS) {
        deduplicateStepRecords(records)
    } else {
        records
    }

    return cleanedRecords.flatMap { record ->
        when (record) {
            is StepsRecord -> {
                val origin = record.metadata.dataOrigin.packageName
                val rawDurationMin = Duration.between(record.startTime, record.endTime).toMinutes()
                val isDailyAggregate = rawDurationMin > DAILY_AGGREGATE_MINUTES

                if (isDailyAggregate && sleepWindows.isNotEmpty()) {
                    distributeStepsExcludingSleep(
                        record.count, record.startTime, record.endTime,
                        origin, intervalMinutes, sleepWindows
                    )
                } else if (isDailyAggregate) {
                    distributeProportionally(
                        record.count.toDouble(), record.startTime, record.endTime,
                        origin, intervalMinutes
                    )
                } else {
                    val mid = Instant.ofEpochMilli(
                        record.startTime.toEpochMilli() / 2 + record.endTime.toEpochMilli() / 2
                    )
                    listOf(FlatSample(mid, record.count.toDouble(), origin))
                }
            }
            is HeartRateRecord -> {
                val origin = record.metadata.dataOrigin.packageName
                record.samples.map { sample ->
                    FlatSample(sample.time, sample.beatsPerMinute.toDouble(), origin)
                }
            }
            is OxygenSaturationRecord -> {
                listOf(
                    FlatSample(
                        record.time,
                        record.percentage.value,
                        record.metadata.dataOrigin.packageName
                    )
                )
            }
            is HeartRateVariabilityRmssdRecord -> {
                listOf(
                    FlatSample(
                        record.time,
                        record.heartRateVariabilityMillis,
                        record.metadata.dataOrigin.packageName
                    )
                )
            }
            is SleepSessionRecord -> {
                val origin = record.metadata.dataOrigin.packageName
                val durationMin =
                    Duration.between(record.startTime, record.endTime).toMinutes().coerceAtLeast(1)

                if (durationMin <= intervalMinutes) {
                    listOf(FlatSample(record.startTime, durationMin / 60.0, origin))
                } else {
                    val segments = mutableListOf<FlatSample>()
                    var segStart = record.startTime
                    while (segStart.isBefore(record.endTime)) {
                        val segEnd = segStart.plusSeconds(intervalMinutes * 60)
                            .coerceAtMost(record.endTime)
                        val segMins = Duration.between(segStart, segEnd).toMinutes().coerceAtLeast(1)
                        segments.add(FlatSample(segStart, segMins / 60.0, origin))
                        segStart = segEnd
                    }
                    segments
                }
            }
            else -> emptyList()
        }
    }
}

/**
 * Spread a daily step total over the waking part of the day.
 * The result is an estimate, never a measurement — every sample is flagged.
 */
private fun distributeStepsExcludingSleep(
    totalSteps: Long,
    start: Instant,
    end: Instant,
    origin: String,
    intervalMinutes: Long,
    sleepWindows: List<SleepWindow>
): List<FlatSample> {
    val totalMinutes = Duration.between(start, end).toMinutes()
    var sleepMinutes = 0L

    for (sw in sleepWindows) {
        val overlapStart = maxOf(start.toEpochMilli(), sw.start.toEpochMilli())
        val overlapEnd = minOf(end.toEpochMilli(), sw.end.toEpochMilli())
        if (overlapEnd > overlapStart) {
            sleepMinutes += (overlapEnd - overlapStart) / 60000
        }
    }

    val wakingMinutes = (totalMinutes - sleepMinutes).coerceAtLeast(60)
    val stepsPerWakingMin = totalSteps.toDouble() / wakingMinutes

    val segments = mutableListOf<FlatSample>()
    var segStart = start
    while (segStart.isBefore(end)) {
        val segEnd = segStart.plusSeconds(intervalMinutes * 60).coerceAtMost(end)

        val segMid = Instant.ofEpochMilli(
            segStart.toEpochMilli() / 2 + segEnd.toEpochMilli() / 2
        )
        val isSleeping = sleepWindows.any { sw ->
            !segMid.isBefore(sw.start) && segMid.isBefore(sw.end)
        }

        if (!isSleeping) {
            val segMins = Duration.between(segStart, segEnd).toMinutes().coerceAtLeast(1)
            segments.add(
                FlatSample(segStart, stepsPerWakingMin * segMins, origin, estimated = true)
            )
        }

        segStart = segEnd
    }
    return segments
}

private fun distributeProportionally(
    totalValue: Double,
    start: Instant,
    end: Instant,
    origin: String,
    intervalMinutes: Long
): List<FlatSample> {
    val durationMin = Duration.between(start, end).toMinutes().coerceAtLeast(1)
    val valuePerMin = totalValue / durationMin
    val segments = mutableListOf<FlatSample>()
    var segStart = start
    while (segStart.isBefore(end)) {
        val segEnd = segStart.plusSeconds(intervalMinutes * 60).coerceAtMost(end)
        val segMins = Duration.between(segStart, segEnd).toMinutes().coerceAtLeast(1)
        segments.add(FlatSample(segStart, valuePerMin * segMins, origin, estimated = true))
        segStart = segEnd
    }
    return segments
}

/**
 * Bucket the samples into fixed intervals and fuse the sources.
 *
 * Bucketing is a single pass: each sample lands in its bucket by index arithmetic.
 * The previous implementation re-scanned the whole sample list once per bucket,
 * which on a day of second-by-second heart rate from two devices meant tens of
 * millions of comparisons per refresh.
 */
fun mixAndGroupData(
    records: List<Any>,
    dataType: AppDataType,
    start: Instant,
    end: Instant,
    intervalMinutes: Long = 60,
    sleepWindows: List<SleepWindow> = emptyList()
): List<AggregatedDataPoint> {
    if (!end.isAfter(start) || intervalMinutes <= 0) return emptyList()

    val allSamples = flattenRecords(records, dataType, intervalMinutes, sleepWindows)
    return groupSamples(allSamples, dataType, start, end, intervalMinutes)
}

/**
 * The bucketing half of [mixAndGroupData], split out so it can be unit-tested
 * without constructing Health Connect record objects.
 */
internal fun groupSamples(
    allSamples: List<FlatSample>,
    dataType: AppDataType,
    start: Instant,
    end: Instant,
    intervalMinutes: Long
): List<AggregatedDataPoint> {
    if (!end.isAfter(start) || intervalMinutes <= 0) return emptyList()

    val startMillis = start.toEpochMilli()
    val endMillis = end.toEpochMilli()
    val intervalMillis = intervalMinutes * 60_000L
    val bucketCount =
        floor((endMillis - startMillis - 1).toDouble() / intervalMillis).toInt() + 1

    val buckets = Array(bucketCount) { mutableListOf<FlatSample>() }
    for (sample in allSamples) {
        val offset = sample.timestamp.toEpochMilli() - startMillis
        if (offset < 0 || sample.timestamp.toEpochMilli() >= endMillis) continue
        val index = (offset / intervalMillis).toInt()
        if (index in 0 until bucketCount) buckets[index].add(sample)
    }

    val formatter = if (intervalMinutes >= 1440) {
        DateTimeFormatter.ofPattern("MMM dd").withZone(ZoneId.systemDefault())
    } else {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }

    return List(bucketCount) { index ->
        val bucketStart = Instant.ofEpochMilli(startMillis + index * intervalMillis)
        val samplesInInterval = buckets[index]

        val valuesByOrigin = samplesInInterval
            .groupBy { it.origin }
            .mapValues { (_, originSamples) ->
                if (dataType.isCumulative) {
                    originSamples.sumOf { it.value }
                } else {
                    originSamples.sumOf { it.value } / originSamples.size
                }
            }

        // Every origin present in this bucket contributes, zeros included: a source
        // that measured "no steps this quarter hour" is data, not a missing reading.
        val mixedValue = if (valuesByOrigin.isEmpty()) {
            null
        } else {
            valuesByOrigin.values.sum() / valuesByOrigin.size
        }

        val allValues = samplesInInterval.map { it.value }

        AggregatedDataPoint(
            timestamp = bucketStart,
            timeLabel = formatter.format(bucketStart),
            valuesByOrigin = valuesByOrigin,
            mixedValue = mixedValue,
            minValue = allValues.minOrNull(),
            maxValue = allValues.maxOrNull(),
            estimatedOrigins = samplesInInterval
                .filter { it.estimated }
                .mapTo(mutableSetOf()) { it.origin }
        )
    }
}
