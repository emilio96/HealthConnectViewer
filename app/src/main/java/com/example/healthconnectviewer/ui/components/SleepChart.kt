package com.example.healthconnectviewer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.records.SleepSessionRecord
import com.example.healthconnectviewer.data.DisplayStage
import com.example.healthconnectviewer.data.GapFill
import com.example.healthconnectviewer.data.SourceReport
import com.example.healthconnectviewer.data.reconstructStages
import com.example.healthconnectviewer.data.stageTypeName
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Stage rendering info
data class StageInfo(
    val name: String,
    val color: Color,
    val yLevel: Int // 0=bottom (Deep), 1=Light, 2=REM, 3=Awake (top)
)

private val stageMap = mapOf(
    SleepSessionRecord.STAGE_TYPE_DEEP to StageInfo("Deep", Color(0xFF4F46E5), 0),
    SleepSessionRecord.STAGE_TYPE_LIGHT to StageInfo("Light", Color(0xFF3B82F6), 1),
    SleepSessionRecord.STAGE_TYPE_SLEEPING to StageInfo("Light", Color(0xFF3B82F6), 1),
    SleepSessionRecord.STAGE_TYPE_REM to StageInfo("REM", Color(0xFF06B6D4), 2),
    SleepSessionRecord.STAGE_TYPE_AWAKE to StageInfo("Awake", Color(0xFFF97316), 3),
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to StageInfo("Awake", Color(0xFFF97316), 3),
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to StageInfo("Awake", Color(0xFFF97316), 3),
    SleepSessionRecord.STAGE_TYPE_UNKNOWN to StageInfo("Unknown", Color(0xFF94A3B8), 1)
)

private val unknownStage = stageMap.getValue(SleepSessionRecord.STAGE_TYPE_UNKNOWN)

private val legendItems = listOf(
    "Awake" to Color(0xFFF97316),
    "REM" to Color(0xFF06B6D4),
    "Light" to Color(0xFF3B82F6),
    "Deep" to Color(0xFF4F46E5)
)

@Composable
fun SleepChart(
    sessions: List<SleepSessionRecord>,
    modifier: Modifier = Modifier
) {
    if (sessions.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No sleep data recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Every session in the window is rendered on one timeline. Keeping only the
    // longest one, as this used to, silently drops the rest of a fragmented night
    // and under-reports the total.
    val night = remember(sessions) { buildNight(sessions) }

    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val startLabel = formatter.format(night.timelineStart)
    val endLabel = formatter.format(night.timelineEnd)
    val durationH = night.totalAsleepMinutes / 60
    val durationM = night.totalAsleepMinutes % 60

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    "Sleep Stages",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "$startLabel – $endLabel",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (night.sessionCount > 1) {
                    Text(
                        "${night.sessionCount} sessions in this window",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "${durationH}h ${durationM}m total",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4F46E5)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            legendItems.forEach { (name, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = color, radius = size.minDimension / 2)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        night.sources.forEach { source ->
            SourceNote(source, showOrigin = night.sources.size > 1)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Hypnogram(night = night, formatter = formatter)

        Spacer(modifier = Modifier.height(12.dp))

        legendItems.reversed().forEach { (name, color) ->
            val mins = night.measuredMinutesByStage[name] ?: 0L
            val inferredMins = night.inferredMinutesByStage[name] ?: 0L
            val total = mins + inferredMins
            if (total > 0) {
                StageSummaryRow(
                    name = name,
                    color = color,
                    minutes = total,
                    inferredMinutes = inferredMins,
                    denominatorMinutes = night.stagedMinutes
                )
            }
        }

        // Time inside the session that carries no stage and could not be named.
        // It shows as a hole in the hypnogram; this is the number behind the hole.
        if (night.unlabelledMinutes > 0) {
            StageSummaryRow(
                name = "Unlabelled",
                color = Color(0xFFCBD5E1),
                minutes = night.unlabelledMinutes,
                inferredMinutes = 0,
                denominatorMinutes = night.stagedMinutes + night.unlabelledMinutes
            )
        }
    }
}

@Composable
private fun Hypnogram(night: Night, formatter: DateTimeFormatter) {
    val yAxisPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 22f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    val xAxisPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 22f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val leftPad = 55f
        val bottomPad = 32f
        val chartW = size.width - leftPad
        val chartH = size.height - bottomPad
        if (chartW <= 0 || chartH <= 0) return@Canvas

        val stageNames = listOf("Deep", "Light", "REM", "Awake")
        val bandH = chartH / stageNames.size

        stageNames.forEachIndexed { index, name ->
            val bandTop = chartH - (index * bandH)
            drawLine(
                Color(0xFFE2E8F0),
                Offset(leftPad, bandTop),
                Offset(leftPad + chartW, bandTop),
                1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            drawContext.canvas.nativeCanvas.drawText(
                name,
                leftPad - 6f,
                bandTop - bandH / 2 + 8f,
                yAxisPaint
            )
        }

        drawLine(Color(0xFFE2E8F0), Offset(leftPad, 0f), Offset(leftPad + chartW, 0f), 1f)

        val timelineStartMs = night.timelineStart.toEpochMilli()
        val timelineEndMs = night.timelineEnd.toEpochMilli()
        val totalMs = (timelineEndMs - timelineStartMs).toFloat().coerceAtLeast(1f)

        val labelCount = 6
        for (i in 0..labelCount) {
            val t = timelineStartMs + (i.toDouble() / labelCount * totalMs).toLong()
            val x = leftPad + (i.toFloat() / labelCount) * chartW
            drawContext.canvas.nativeCanvas.drawText(
                formatter.format(Instant.ofEpochMilli(t)),
                x,
                chartH + 24f,
                xAxisPaint
            )
        }

        night.stages.forEach { stage ->
            val info = stageMap[stage.stageType] ?: unknownStage

            val x1 = leftPad + ((stage.startTime.toEpochMilli() - timelineStartMs).toFloat() / totalMs) * chartW
            val x2 = leftPad + ((stage.endTime.toEpochMilli() - timelineStartMs).toFloat() / totalMs) * chartW
            val blockW = (x2 - x1).coerceAtLeast(2f)
            val y = chartH - ((info.yLevel + 1) * bandH)

            if (stage.inferred) {
                drawRect(
                    color = info.color.copy(alpha = 0.15f),
                    topLeft = Offset(x1, y),
                    size = Size(blockW, bandH),
                    style = Fill
                )
                drawRect(
                    color = info.color,
                    topLeft = Offset(x1, y),
                    size = Size(blockW, bandH),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                )
            } else {
                drawRect(
                    color = info.color,
                    topLeft = Offset(x1, y),
                    size = Size(blockW, bandH),
                    style = Fill
                )
            }
        }
    }
}

@Composable
private fun StageSummaryRow(
    name: String,
    color: Color,
    minutes: Long,
    inferredMinutes: Long,
    denominatorMinutes: Long
) {
    val pct = if (denominatorMinutes > 0) {
        (minutes.toFloat() / denominatorMinutes * 100).coerceIn(0f, 100f)
    } else {
        0f
    }
    val hours = minutes / 60
    val remainMins = minutes % 60

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(0.4f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color(0xFFE2E8F0))
                drawRect(color = color, size = Size(size.width * (pct / 100f), size.height))
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "${String.format("%.0f", pct)}%",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.width(36.dp)
        )
        Text(
            text = buildString {
                append("$name ${hours}h ${remainMins}m")
                if (inferredMinutes > 0) append(" (${inferredMinutes}m inferred)")
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One night's worth of sessions, flattened onto a single timeline. */
private data class Night(
    val timelineStart: Instant,
    val timelineEnd: Instant,
    val sessionCount: Int,
    val stages: List<DisplayStage>,
    val sources: List<SourceReport>,
    val unlabelledMinutes: Long,
    /** Sum of the session durations, i.e. actual time in bed, not the span. */
    val totalAsleepMinutes: Long,
    val stagedMinutes: Long,
    val measuredMinutesByStage: Map<String, Long>,
    val inferredMinutesByStage: Map<String, Long>
) {
    val inferredMinutes: Long get() = inferredMinutesByStage.values.sum()
}

/**
 * One line per source saying what it actually wrote and what had to be filled in.
 * Naming the stages present in the data is the quickest way to tell a rendering
 * bug apart from a source that simply never synced REM.
 */
@Composable
private fun SourceNote(source: SourceReport, showOrigin: Boolean) {
    val prefix = if (showOrigin) source.origin.substringAfterLast(".") + ": " else ""
    val reported = source.reportedStages
        .map(::stageTypeName)
        .distinct()
        .sorted()

    val text = buildString {
        append(prefix)
        if (reported.isEmpty()) {
            append("no stage detail recorded, only a sleep session.")
        } else {
            append("stages recorded: ")
            append(reported.joinToString(", "))
            append(".")
        }
        when (source.gapFill) {
            GapFill.AWAKE ->
                append(" Awake is never written by this source, so unlabelled gaps are " +
                    "shown as Awake (outlined).")
            GapFill.REM ->
                append(" REM is never written by this source, so unlabelled gaps are " +
                    "shown as REM (outlined).")
            GapFill.NONE ->
                if (source.unlabelledMinutes > 0) {
                    append(" ${source.unlabelledMinutes} min carry no stage and are left " +
                        "blank: there is no basis to name them.")
                }
        }
    }

    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private fun buildNight(sessions: List<SleepSessionRecord>): Night {
    val sorted = sessions.sortedBy { it.startTime }
    val timelineStart = sorted.minOf { it.startTime }
    val timelineEnd = sorted.maxOf { it.endTime }

    val reconstruction = reconstructStages(sorted)
    val stages = reconstruction.stages

    val measured = mutableMapOf<String, Long>()
    val inferred = mutableMapOf<String, Long>()
    stages.forEach { stage ->
        val info = stageMap[stage.stageType] ?: unknownStage
        val mins = Duration.between(stage.startTime, stage.endTime).toMinutes()
        val target = if (stage.inferred) inferred else measured
        target[info.name] = (target[info.name] ?: 0L) + mins
    }

    // Sessions from a single source can overlap after a resync; summing raw
    // durations would then double-count, so overlapping spans are merged first.
    val totalAsleepMinutes = mergeSpans(sorted.map { it.startTime to it.endTime })
        .sumOf { Duration.between(it.first, it.second).toMinutes() }

    return Night(
        timelineStart = timelineStart,
        timelineEnd = timelineEnd,
        sessionCount = sorted.size,
        stages = stages,
        sources = reconstruction.sources,
        unlabelledMinutes = reconstruction.unlabelledMinutes,
        totalAsleepMinutes = totalAsleepMinutes,
        stagedMinutes = measured.values.sum() + inferred.values.sum(),
        measuredMinutesByStage = measured,
        inferredMinutesByStage = inferred
    )
}

private fun mergeSpans(spans: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> {
    if (spans.isEmpty()) return emptyList()
    val sorted = spans.sortedBy { it.first }
    val merged = mutableListOf(sorted.first())
    for (span in sorted.drop(1)) {
        val last = merged.last()
        if (!span.first.isAfter(last.second)) {
            merged[merged.lastIndex] = last.first to maxOf(last.second, span.second)
        } else {
            merged.add(span)
        }
    }
    return merged
}
