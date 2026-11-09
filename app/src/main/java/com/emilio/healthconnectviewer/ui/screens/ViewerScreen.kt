package com.emilio.healthconnectviewer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilio.healthconnectviewer.data.AppDataType
import com.emilio.healthconnectviewer.ui.LoadState
import com.emilio.healthconnectviewer.ui.MainViewModel
import com.emilio.healthconnectviewer.ui.Timeframe
import com.emilio.healthconnectviewer.ui.components.AdvancedChart
import com.emilio.healthconnectviewer.ui.components.AppHeader
import com.emilio.healthconnectviewer.ui.components.DataTypeSelector
import com.emilio.healthconnectviewer.ui.components.EmptyBox
import com.emilio.healthconnectviewer.ui.components.ErrorBox
import com.emilio.healthconnectviewer.ui.components.EstimatedDataNotice
import com.emilio.healthconnectviewer.ui.components.LoadingBox
import com.emilio.healthconnectviewer.ui.components.MissingPermissionNotice
import com.emilio.healthconnectviewer.ui.components.PillSelector
import com.emilio.healthconnectviewer.ui.components.RangeData
import com.emilio.healthconnectviewer.ui.components.SleepChart
import com.emilio.healthconnectviewer.ui.components.sourceLabel

@Composable
fun ViewerScreen(viewModel: MainViewModel, onOpenDrawer: () -> Unit, onRequestPermissions: () -> Unit = {}) {
    val chartData by viewModel.chartData.collectAsState()
    val isAuthorized by viewModel.isAuthorized.collectAsState()
    val selectedDataType by viewModel.selectedDataType.collectAsState()
    val timeframe by viewModel.timeframe.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val loadState by viewModel.viewerState.collectAsState()
    val grantedTypes by viewModel.grantedTypes.collectAsState()
    val estimatedOrigins by viewModel.estimatedOrigins.collectAsState()
    val originsWithData by viewModel.originsWithData.collectAsState()

    val availableOrigins by viewModel.availableOrigins.collectAsState()
    val selectedOrigins by viewModel.selectedOrigins.collectAsState()
    val sleepSessions by viewModel.sleepSessions.collectAsState()
    val trendPercent by viewModel.trendPercent.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        AppHeader(
            title = "Viewer",
            subtitle = "Aggregated health data analysis",
            selectedDate = selectedDate,
            timeframe = timeframe,
            onOpenDrawer = onOpenDrawer,
            onNavigateDate = { viewModel.navigateDate(it) },
            onSetToday = { viewModel.setDateToToday() },
            onSetTimeframe = { viewModel.setTimeframe(it) }
        )

        if (!isAuthorized) {
            NotAuthorised(onRequestPermissions)
            return@Column
        }

        SourceSelectorCard(
            availableOrigins = availableOrigins,
            selectedOrigins = selectedOrigins,
            onSelect = { viewModel.setOrigin(it) },
            originsWithData = originsWithData,
            // Only the source actually on screen can be the estimated one here.
            estimatedNote = selectedOrigins.filter { it in estimatedOrigins }.map(::sourceLabel)
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
            }

            state is LoadState.Failed -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) { ErrorBox(state.message, onRetry = { viewModel.fetchData() }) }
            }

            selectedDataType == AppDataType.SLEEP -> {
                val filteredSessions = sleepSessions.filter { session ->
                    selectedOrigins.contains(session.metadata.dataOrigin.packageName)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    if (filteredSessions.isNotEmpty()) {
                        SleepChart(
                            sessions = filteredSessions,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        EmptyBox("No sleep data from the selected source for this period.")
                    }
                }
            }

            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    val origin = selectedOrigins.firstOrNull()
                    val series = remember(chartData, origin) {
                        chartData.map { dp -> origin?.let { dp.valuesByOrigin[it] } }
                    }

                    if (series.any { it != null }) {
                        SeriesChart(
                            series = series,
                            timeLabels = remember(chartData) { chartData.map { it.timeLabel } },
                            dataType = selectedDataType,
                            timeframe = timeframe,
                            trendPercent = trendPercent,
                            accent = Color(0xFF6366F1),
                            title = selectedDataType.title,
                            rangeData = rangeDataFor(chartData, origin, selectedDataType, timeframe)
                        )
                    } else {
                        EmptyBox(
                            "No ${selectedDataType.title.lowercase()} recorded in Health Connect " +
                                "for this period."
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NotAuthorised(onRequestPermissions: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
    ) {
        Text(
            text = "Health Connect requires explicit permission.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant Permissions")
        }
    }
}

@Composable
internal fun SourceSelectorCard(
    availableOrigins: List<String>,
    selectedOrigins: Set<String>,
    onSelect: (String) -> Unit,
    label: String = "Data Source",
    multiSelect: Boolean = false,
    colorMap: Map<String, Color> = emptyMap(),
    hint: String? = null,
    originsWithData: Set<String>? = null,
    estimatedNote: List<String> = emptyList()
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            if (availableOrigins.isNotEmpty()) {
                PillSelector(
                    items = availableOrigins,
                    selectedItems = selectedOrigins,
                    onSelect = onSelect,
                    multiSelect = multiSelect,
                    colorMap = colorMap,
                    availableItems = originsWithData
                )
                EstimatedDataNotice(estimatedNote)
                if (hint != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("No sources found for this period", fontSize = 14.sp)
            }
        }
    }
}

@Composable
internal fun DataTypeCard(selectedType: AppDataType, onSelect: (AppDataType) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Data Type", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            DataTypeSelector(selectedType = selectedType, onSelect = onSelect)
        }
    }
}

/**
 * Headline figure plus chart, shared by Viewer and Mixing so the two screens
 * cannot drift apart in how they summarise the same numbers.
 */
@Composable
internal fun SeriesChart(
    series: List<Double?>,
    timeLabels: List<String>,
    dataType: AppDataType,
    timeframe: Timeframe,
    trendPercent: Double?,
    accent: Color,
    title: String,
    rangeData: List<RangeData?>? = null
) {
    val nonNulls = series.filterNotNull()
    val summaryValue = if (nonNulls.isEmpty()) {
        0.0
    } else {
        if (dataType.isCumulative) nonNulls.sum() else nonNulls.average()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val display = if (dataType.isCumulative) {
                    Math.round(summaryValue).toString()
                } else {
                    String.format("%.1f", summaryValue)
                }
                Text(
                    "$display ${dataType.unit}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = accent
                )
                TrendBadge(trendPercent, dataType)
            }
        }

        AdvancedChart(
            data = series,
            color = accent,
            forceBars = dataType.isCumulative,
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            timeLabels = timeLabels,
            unit = dataType.unit,
            averageValue = if (!dataType.isCumulative && nonNulls.isNotEmpty()) nonNulls.average() else null,
            rangeData = rangeData
        )
    }
}

/** Min/max whiskers only make sense over a week or a month of non-cumulative data. */
internal fun rangeDataFor(
    chartData: List<com.emilio.healthconnectviewer.data.AggregatedDataPoint>,
    origin: String?,
    dataType: AppDataType,
    timeframe: Timeframe
): List<RangeData?>? {
    val isWeekOrMonth = timeframe == Timeframe.WEEK || timeframe == Timeframe.MONTH
    if (!isWeekOrMonth || dataType.isCumulative) return null

    return chartData.map { dp ->
        val v = origin?.let { dp.valuesByOrigin[it] }
        if (v != null && dp.minValue != null && dp.maxValue != null) {
            RangeData(min = dp.minValue, max = dp.maxValue, avg = v)
        } else {
            null
        }
    }
}
