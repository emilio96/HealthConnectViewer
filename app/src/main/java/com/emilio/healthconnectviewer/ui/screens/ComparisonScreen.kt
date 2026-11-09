package com.emilio.healthconnectviewer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilio.healthconnectviewer.data.AggregatedDataPoint
import com.emilio.healthconnectviewer.data.AppDataType
import com.emilio.healthconnectviewer.ui.LoadState
import com.emilio.healthconnectviewer.ui.MainViewModel
import com.emilio.healthconnectviewer.ui.components.AdvancedChart
import com.emilio.healthconnectviewer.ui.components.AppHeader
import com.emilio.healthconnectviewer.ui.components.EmptyBox
import com.emilio.healthconnectviewer.ui.components.ErrorBox
import com.emilio.healthconnectviewer.ui.components.LoadingBox
import com.emilio.healthconnectviewer.ui.components.MissingPermissionNotice
import com.emilio.healthconnectviewer.ui.components.SleepChart
import com.emilio.healthconnectviewer.ui.components.SleepComparisonTable
import kotlin.math.abs
import kotlin.math.sqrt

private val chartColors = listOf(
    Color(0xFF6366F1),
    Color(0xFFEF4444),
    Color(0xFF10B981),
    Color(0xFFF59E0B)
)

@Composable
fun ComparisonScreen(
    viewModel: MainViewModel,
    onOpenDrawer: () -> Unit,
    onRequestPermissions: () -> Unit = {}
) {
    val chartData by viewModel.chartData.collectAsState()
    val isAuthorized by viewModel.isAuthorized.collectAsState()
    val selectedDataType by viewModel.selectedDataType.collectAsState()
    val timeframe by viewModel.timeframe.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val loadState by viewModel.viewerState.collectAsState()
    val grantedTypes by viewModel.grantedTypes.collectAsState()
    val originsWithData by viewModel.originsWithData.collectAsState()

    val availableOrigins by viewModel.availableOrigins.collectAsState()
    val selectedOrigins by viewModel.selectedOrigins.collectAsState()
    val sleepSessions by viewModel.sleepSessions.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        AppHeader(
            title = "Comparison",
            subtitle = "Compare data across multiple sources",
            selectedDate = selectedDate,
            timeframe = timeframe,
            onOpenDrawer = onOpenDrawer,
            onNavigateDate = { viewModel.navigateDate(it) },
            onSetToday = { viewModel.setDateToToday() },
            onSetTimeframe = { viewModel.setTimeframe(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!isAuthorized) {
            NotAuthorised(onRequestPermissions)
            return@Column
        }

        val colorMap = remember(availableOrigins) {
            availableOrigins.mapIndexed { i, o -> o to chartColors[i % chartColors.size] }.toMap()
        }

        SourceSelectorCard(
            availableOrigins = availableOrigins,
            selectedOrigins = selectedOrigins,
            onSelect = { viewModel.toggleOrigin(it) },
            label = "Select 2 or more Data Sources",
            multiSelect = true,
            colorMap = colorMap,
            originsWithData = originsWithData
        )

        Spacer(modifier = Modifier.height(12.dp))

        DataTypeCard(selectedDataType) { viewModel.setDataType(it) }

        if (selectedDataType !in grantedTypes) {
            Spacer(modifier = Modifier.height(12.dp))
            MissingPermissionNotice(selectedDataType.title, onRequestPermissions)
        }

        Spacer(modifier = Modifier.height(24.dp))

        val state = loadState
        when {
            state is LoadState.Loading -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) { LoadingBox() }
                return@Column
            }

            state is LoadState.Failed -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) { ErrorBox(state.message, onRetry = { viewModel.fetchData() }) }
                return@Column
            }
        }

        // Ordered by the same rule as the pills, so "source A" and "source B" keep
        // meaning the same thing as the user toggles selections on and off.
        val orderedSelection = availableOrigins.filter { it in selectedOrigins }

        if (selectedDataType == AppDataType.SLEEP) {
            SleepComparison(
                sleepSessions = sleepSessions,
                selectedOrigins = orderedSelection
            )
        } else {
            OverlappingSourcesCard(
                chartData = chartData,
                selectedOrigins = orderedSelection,
                colorMap = colorMap,
                dataType = selectedDataType
            )

            Spacer(modifier = Modifier.height(24.dp))

            DeviceCorrelationCard(
                chartData = chartData,
                selectedOrigins = orderedSelection
            )
        }
    }
}

@Composable
private fun SleepComparison(
    sleepSessions: List<androidx.health.connect.client.records.SleepSessionRecord>,
    selectedOrigins: List<String>
) {
    val filteredSessions = sleepSessions.filter { session ->
        selectedOrigins.contains(session.metadata.dataOrigin.packageName)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (filteredSessions.isNotEmpty()) {
            Column(modifier = Modifier.padding(16.dp)) {
                selectedOrigins.forEach { origin ->
                    val originSessions = filteredSessions.filter {
                        it.metadata.dataOrigin.packageName == origin
                    }
                    if (originSessions.isNotEmpty()) {
                        Text(
                            text = origin.substringAfterLast("."),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        SleepChart(sessions = originSessions, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        } else {
            EmptyBox("No sleep data from the selected sources for this period.")
        }
    }

    if (selectedOrigins.size >= 2 && filteredSessions.isNotEmpty()) {
        val originList = selectedOrigins
        val sessionsA = filteredSessions.filter { it.metadata.dataOrigin.packageName == originList[0] }
        val sessionsB = filteredSessions.filter { it.metadata.dataOrigin.packageName == originList[1] }

        if (sessionsA.isNotEmpty() && sessionsB.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                SleepComparisonTable(
                    sessionsA = sessionsA,
                    sessionsB = sessionsB,
                    sourceAName = originList[0].substringAfterLast("."),
                    sourceBName = originList[1].substringAfterLast("."),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun OverlappingSourcesCard(
    chartData: List<AggregatedDataPoint>,
    selectedOrigins: List<String>,
    colorMap: Map<String, Color>,
    dataType: AppDataType
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(380.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Overlapping Sources", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))

            if (selectedOrigins.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    selectedOrigins.forEach { origin ->
                        val c = colorMap[origin] ?: chartColors.first()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Canvas(modifier = Modifier.height(10.dp)) {
                                drawCircle(color = c, radius = 5f)
                            }
                            Text(
                                text = origin.substringAfterLast("."),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val hasAnyValue = chartData.any { dp ->
                selectedOrigins.any { dp.valuesByOrigin.containsKey(it) }
            }

            if (hasAnyValue) {
                // All the overlaid series must share one vertical scale, otherwise
                // two sources with different ranges appear to agree when they don't.
                val allValues = selectedOrigins.flatMap { origin ->
                    chartData.mapNotNull { it.valuesByOrigin[origin] }
                }
                val globalMin = if (dataType.isCumulative) 0.0 else (allValues.minOrNull() ?: 0.0)
                val globalMax = allValues.maxOrNull() ?: 100.0
                val globalRange = (globalMax - globalMin).coerceAtLeast(0.1)
                val paddedMin = if (dataType.isCumulative) 0.0 else globalMin - globalRange * 0.05
                val paddedMax = globalMax + globalRange * 0.1

                val timeLabels = remember(chartData) { chartData.map { it.timeLabel } }

                Box(modifier = Modifier.fillMaxSize()) {
                    selectedOrigins.forEachIndexed { index, origin ->
                        val c = colorMap[origin] ?: chartColors[index % chartColors.size]
                        AdvancedChart(
                            data = chartData.map { it.valuesByOrigin[origin] },
                            color = c,
                            forceBars = dataType.isCumulative,
                            modifier = Modifier.fillMaxSize(),
                            timeLabels = if (index == 0) timeLabels else emptyList(),
                            unit = dataType.unit,
                            showAxes = index == 0,
                            reserveAxesPadding = true, // every layer must share the same padding
                            sharedMin = paddedMin,
                            sharedMax = paddedMax
                        )
                    }
                }
            } else {
                EmptyBox("No data from the selected sources for this period.", Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun DeviceCorrelationCard(
    chartData: List<AggregatedDataPoint>,
    selectedOrigins: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Device Correlation", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            if (selectedOrigins.size < 2 || chartData.isEmpty()) {
                EmptyBox("Select at least 2 sources to see correlation.")
                return@Column
            }

            val originList = selectedOrigins
            val originA = originList[0]
            val originB = originList[1]
            val nameA = originA.substringAfterLast(".")
            val nameB = originB.substringAfterLast(".")

            Text("$nameA vs $nameB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val pairs = remember(chartData, originA, originB) {
                chartData.mapNotNull { dp ->
                    val a = dp.valuesByOrigin[originA]
                    val b = dp.valuesByOrigin[originB]
                    if (a != null && b != null) a to b else null
                }
            }

            if (pairs.size < 2) {
                EmptyBox("Not enough overlapping data points (need at least 2).")
                return@Column
            }

            val n = pairs.size
            val sumX = pairs.sumOf { it.first }
            val sumY = pairs.sumOf { it.second }
            val sumXY = pairs.sumOf { it.first * it.second }
            val sumX2 = pairs.sumOf { it.first * it.first }
            val sumY2 = pairs.sumOf { it.second * it.second }

            val numerator = n * sumXY - sumX * sumY
            val denom = sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY))
            val r = if (denom > 0) numerator / denom else 0.0

            val rColor = when {
                abs(r) > 0.7 -> Color(0xFF10B981)
                abs(r) > 0.4 -> Color(0xFFF59E0B)
                else -> Color(0xFFEF4444)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "r = ${String.format("%.3f", r)} ($n paired readings)",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = rColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            ScatterPlot(pairs = pairs, xLabel = nameA)
        }
    }
}

@Composable
private fun ScatterPlot(pairs: List<Pair<Double, Double>>, xLabel: String) {
    val axisPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 24f
            isAntiAlias = true
        }
    }
    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#64748B")
            textSize = 22f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    val allVals = pairs.flatMap { listOf(it.first, it.second) }
    val rawMin = allVals.min()
    val rawMax = allVals.max()
    val padding = ((rawMax - rawMin) * 0.1).coerceAtLeast(1.0)
    val domainMin = rawMin - padding
    val domainMax = rawMax + padding
    val domainRange = (domainMax - domainMin).coerceAtLeast(0.1)

    Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        val axisPad = 55f
        val chartW = size.width - axisPad
        val chartH = size.height - 40f
        if (chartW <= 0 || chartH <= 0) return@Canvas

        axisPaint.textAlign = android.graphics.Paint.Align.RIGHT
        for (i in 0..4) {
            val y = chartH - (i * chartH / 4)
            val value = domainMin + (i.toDouble() / 4) * domainRange
            drawLine(
                Color(0xFFE2E8F0),
                Offset(axisPad, y),
                Offset(axisPad + chartW, y),
                1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.0f", value), axisPad - 8f, y + 8f, axisPaint
            )
        }

        axisPaint.textAlign = android.graphics.Paint.Align.CENTER
        for (i in 0..4) {
            val x = axisPad + (i * chartW / 4)
            val value = domainMin + (i.toDouble() / 4) * domainRange
            drawLine(
                Color(0xFFE2E8F0),
                Offset(x, 0f),
                Offset(x, chartH),
                1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.0f", value), x, chartH + 28f, axisPaint
            )
        }

        drawContext.canvas.nativeCanvas.drawText(
            xLabel, axisPad + chartW / 2, chartH + 38f, labelPaint
        )

        // Line of perfect agreement
        drawLine(
            Color(0xFF94A3B8).copy(alpha = 0.5f),
            Offset(axisPad, chartH),
            Offset(axisPad + chartW, 0f),
            2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        )

        pairs.forEach { (x, y) ->
            val px = axisPad + ((x - domainMin) / domainRange * chartW).toFloat()
            val py = chartH - ((y - domainMin) / domainRange * chartH).toFloat()
            drawCircle(Color(0xFF6366F1).copy(alpha = 0.5f), 10f, Offset(px, py))
            drawCircle(Color(0xFF6366F1), 5f, Offset(px, py))
        }
    }
}
