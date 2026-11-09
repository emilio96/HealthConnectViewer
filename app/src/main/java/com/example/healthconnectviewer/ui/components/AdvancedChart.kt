package com.example.healthconnectviewer.ui.components

import android.graphics.Paint as NativePaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Data for range bars (min-max display for week/month views)
 */
data class RangeData(
    val min: Double,
    val max: Double,
    val avg: Double
)

/** Vertical scale of the plot, derived from the data rather than from the canvas size. */
private data class ChartScale(
    val paddedMin: Double,
    val paddedMax: Double
) {
    val range: Double get() = (paddedMax - paddedMin).coerceAtLeast(0.1)
}

private const val GRID_LINES = 4
private const val MAX_X_LABELS = 7
private const val AXIS_LEFT_PADDING = 55f
private const val AXIS_BOTTOM_PADDING = 40f

@Composable
fun AdvancedChart(
    data: List<Double?>,
    color: Color,
    forceBars: Boolean = false,
    modifier: Modifier = Modifier,
    timeLabels: List<String> = emptyList(),
    unit: String = "",
    showAxes: Boolean = true,
    reserveAxesPadding: Boolean = showAxes,
    averageValue: Double? = null,
    sharedMin: Double? = null,
    sharedMax: Double? = null,
    // Range bar data for week/month views (non-cumulative data)
    rangeData: List<RangeData?>? = null
) {
    if (data.isEmpty()) return

    val useBars = forceBars
    val bars = rangeData?.takeIf { it.isNotEmpty() }
    val useRangeBars = bars != null

    // Scale, label stride and the axis Paints all depend on the data, not on the
    // canvas size, so they are computed once per data change instead of on every
    // frame inside the draw scope.
    val scale = remember(data, bars, sharedMin, sharedMax, useBars) {
        computeScale(data, bars, sharedMin, sharedMax, useBars)
    }

    val yLabels = remember(scale) {
        if (scale == null) emptyList() else List(GRID_LINES + 1) { i ->
            formatAxisValue(scale.paddedMin + (i.toDouble() / GRID_LINES) * scale.range)
        }
    }

    val labelStride = remember(timeLabels) {
        (timeLabels.size / MAX_X_LABELS).coerceAtLeast(1)
    }

    val yAxisPaint = remember {
        NativePaint().apply {
            this.color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 26f
            isAntiAlias = true
            textAlign = NativePaint.Align.RIGHT
        }
    }
    val xAxisPaint = remember {
        NativePaint().apply {
            this.color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 22f
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
    }

    if (scale == null) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val leftPadding = if (reserveAxesPadding) AXIS_LEFT_PADDING else 0f
        val bottomPadding = if (reserveAxesPadding) AXIS_BOTTOM_PADDING else 0f

        val chartWidth = size.width - leftPadding
        val chartHeight = size.height - bottomPadding

        if (chartWidth <= 0 || chartHeight <= 0) return@Canvas

        val range = scale.range
        val paddedMin = scale.paddedMin

        fun yFor(value: Double): Float =
            (chartHeight - ((value - paddedMin) / range * chartHeight).toFloat())
                .coerceIn(0f, chartHeight)

        if (showAxes) {
            for (i in 0..GRID_LINES) {
                val y = chartHeight - (i * (chartHeight / GRID_LINES))
                drawLine(
                    color = Color(0xFFE2E8F0),
                    start = Offset(leftPadding, y),
                    end = Offset(leftPadding + chartWidth, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )
                drawContext.canvas.nativeCanvas.drawText(
                    yLabels[i],
                    leftPadding - 8f,
                    y + 8f,
                    yAxisPaint
                )
            }

            if (timeLabels.isNotEmpty()) {
                val totalItems = bars?.size ?: data.size
                for (i in timeLabels.indices step labelStride) {
                    val x = leftPadding +
                        (i.toFloat() / (totalItems - 1).coerceAtLeast(1)) * chartWidth
                    drawContext.canvas.nativeCanvas.drawText(
                        timeLabels[i],
                        x,
                        chartHeight + 28f,
                        xAxisPaint
                    )
                }
            }
        }

        val dataSize = bars?.size ?: data.size
        val xStep = chartWidth / (dataSize - 1).coerceAtLeast(1).toFloat()

        clipRect(
            left = leftPadding,
            top = 0f,
            right = leftPadding + chartWidth,
            bottom = chartHeight
        ) {
            if (bars != null) {
                val barWidth = (xStep * 0.5f).coerceIn(6f, 40f)

                bars.forEachIndexed { index, rd ->
                    if (rd != null) {
                        val x = leftPadding + index * xStep
                        val yMax = yFor(rd.max)
                        val yMin = yFor(rd.min)
                        val yAvg = yFor(rd.avg)
                        val barHeight = (yMin - yMax).coerceAtLeast(4f)

                        drawRoundRect(
                            color = color.copy(alpha = 0.25f),
                            topLeft = Offset(x - barWidth / 2, yMax),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawRoundRect(
                            color = color.copy(alpha = 0.6f),
                            topLeft = Offset(x - barWidth / 2, yMax),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(4f, 4f),
                            style = Stroke(width = 2f)
                        )
                        drawCircle(color = color, radius = 5f, center = Offset(x, yAvg))
                        drawCircle(color = Color.White, radius = 2.5f, center = Offset(x, yAvg))
                    }
                }
            } else if (useBars) {
                val barWidth = (xStep * 0.7f).coerceIn(4f, 40f)
                data.forEachIndexed { index, value ->
                    if (value != null) {
                        val x = leftPadding + index * xStep - (barWidth / 2f)
                        val barHeight =
                            ((value - paddedMin) / range * chartHeight).toFloat().coerceAtLeast(0f)
                        drawRoundRect(
                            color = color.copy(alpha = 0.8f),
                            topLeft = Offset(x, chartHeight - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(4f, 4f),
                            style = Fill
                        )
                    }
                }
            } else {
                // A gap in the series must read as a gap. Joining the points either
                // side of a missing stretch draws a straight line through data that
                // was never recorded, which on overnight SpO2 is most of the chart.
                var segment = mutableListOf<Offset>()

                fun flushSegment() {
                    if (segment.size >= 2) {
                        val path = Path().apply {
                            moveTo(segment.first().x, segment.first().y)
                            for (k in 1 until segment.size) lineTo(segment[k].x, segment[k].y)
                        }
                        val fillPath = Path().apply {
                            addPath(path)
                            lineTo(segment.last().x, chartHeight)
                            lineTo(segment.first().x, chartHeight)
                            close()
                        }
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(color.copy(alpha = 0.15f), Color.Transparent),
                                startY = 0f,
                                endY = chartHeight
                            )
                        )
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                    segment = mutableListOf()
                }

                val allPoints = mutableListOf<Offset>()
                data.forEachIndexed { index, value ->
                    if (value == null) {
                        flushSegment()
                    } else {
                        val point = Offset(leftPadding + index * xStep, yFor(value))
                        segment.add(point)
                        allPoints.add(point)
                    }
                }
                flushSegment()

                // An isolated reading would otherwise be invisible: a one-point
                // segment draws no stroke, so the markers double as its rendering.
                if (allPoints.size in 1..60) {
                    allPoints.forEach { pt ->
                        drawCircle(color = color, radius = 3.5f, center = pt)
                        drawCircle(color = Color.White, radius = 1.5f, center = pt)
                    }
                }
            }

            if (averageValue != null &&
                averageValue > scale.paddedMin &&
                averageValue < scale.paddedMax
            ) {
                val avgY = yFor(averageValue)
                drawLine(
                    color = color.copy(alpha = 0.5f),
                    start = Offset(leftPadding, avgY),
                    end = Offset(leftPadding + chartWidth, avgY),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            }
        }
    }
}

private fun computeScale(
    data: List<Double?>,
    rangeData: List<RangeData?>?,
    sharedMin: Double?,
    sharedMax: Double?,
    useBars: Boolean
): ChartScale? {
    val nonNullValues = data.filterNotNull()
    if (nonNullValues.isEmpty() && rangeData == null) return null

    val rawMax: Double
    val rawMin: Double

    if (rangeData != null) {
        val rangeNonNull = rangeData.filterNotNull()
        if (rangeNonNull.isEmpty() && nonNullValues.isEmpty()) return null
        rawMax = sharedMax ?: (rangeNonNull.maxOfOrNull { it.max } ?: 100.0)
        rawMin = sharedMin ?: (rangeNonNull.minOfOrNull { it.min } ?: 0.0)
    } else {
        rawMax = sharedMax ?: nonNullValues.max()
        rawMin = sharedMin ?: if (useBars) 0.0 else nonNullValues.min()
    }

    val rawRange = (rawMax - rawMin).coerceAtLeast(0.1)
    val paddedMax = if (sharedMax != null) rawMax else rawMax + rawRange * 0.1
    val paddedMin = if (sharedMin != null) {
        rawMin
    } else {
        if (useBars) 0.0 else rawMin - rawRange * 0.05
    }
    return ChartScale(paddedMin, paddedMax)
}

private fun formatAxisValue(value: Double): String = when {
    value >= 10000 -> "${(value / 1000).toInt()}k"
    value >= 1000 -> String.format("%.1fk", value / 1000)
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> String.format("%.1f", value)
}
