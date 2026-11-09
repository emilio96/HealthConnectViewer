package com.emilio.healthconnectviewer.data

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import java.time.Duration
import java.time.Instant

/**
 * What is actually inside Health Connect, per data type and per writing app.
 *
 * This exists to answer a question the charts cannot: when a screen shows one
 * reading, is that because the app read the wrong window, because the permission
 * was never granted, or because the watch simply never synced the data? Those three
 * look identical on a chart and completely different here.
 *
 * A "sample" is one measured value: a heart rate record carries many, an SpO2
 * record carries one. Counting records alone would make a single heart-rate record
 * holding a thousand beats look as sparse as a single spot reading.
 */

data class OriginDiagnostic(
    val origin: String,
    val records: Int,
    val samples: Int,
    val first: Instant?,
    val last: Instant?,
    /** Median gap between consecutive samples from this origin. */
    val medianGapSeconds: Double?
)

data class TypeDiagnostic(
    val type: AppDataType,
    val granted: Boolean,
    val byOrigin: List<OriginDiagnostic>,
    val error: String? = null
) {
    val totalRecords: Int get() = byOrigin.sumOf { it.records }
    val totalSamples: Int get() = byOrigin.sumOf { it.samples }
}

/**
 * Timestamps of every measured value in [records], paired with the app that wrote it.
 *
 * Deliberately not routed through [flattenRecords]: that spreads a daily step total
 * across the day, which is exactly the kind of reshaping a diagnostic must not do.
 */
internal fun rawSampleTimes(records: List<Record>): List<Pair<String, Instant>> =
    records.flatMap { record ->
        when (record) {
            is HeartRateRecord -> {
                val origin = record.metadata.dataOrigin.packageName
                record.samples.map { origin to it.time }
            }
            is OxygenSaturationRecord ->
                listOf(record.metadata.dataOrigin.packageName to record.time)
            is HeartRateVariabilityRmssdRecord ->
                listOf(record.metadata.dataOrigin.packageName to record.time)
            is StepsRecord ->
                listOf(record.metadata.dataOrigin.packageName to record.startTime)
            is SleepSessionRecord ->
                listOf(record.metadata.dataOrigin.packageName to record.startTime)
            else -> emptyList()
        }
    }

fun diagnoseType(
    type: AppDataType,
    granted: Boolean,
    records: List<Record>
): TypeDiagnostic {
    if (!granted) return TypeDiagnostic(type, granted = false, byOrigin = emptyList())

    val recordsByOrigin = records.groupBy { it.metadata.dataOrigin.packageName }
    val samplesByOrigin = rawSampleTimes(records)
        .groupBy({ it.first }, { it.second })

    val origins = (recordsByOrigin.keys + samplesByOrigin.keys).sorted()

    return TypeDiagnostic(
        type = type,
        granted = true,
        byOrigin = origins.map { origin ->
            val times = samplesByOrigin[origin].orEmpty().sorted()
            OriginDiagnostic(
                origin = origin,
                records = recordsByOrigin[origin]?.size ?: 0,
                samples = times.size,
                first = times.firstOrNull(),
                last = times.lastOrNull(),
                medianGapSeconds = medianGap(times)
            )
        }
    )
}

private fun medianGap(times: List<Instant>): Double? {
    if (times.size < 2) return null
    val gaps = (1 until times.size)
        .map { Duration.between(times[it - 1], times[it]).seconds.toDouble() }
        .sorted()
    val mid = gaps.size / 2
    return if (gaps.size % 2 == 0) (gaps[mid - 1] + gaps[mid]) / 2.0 else gaps[mid]
}
