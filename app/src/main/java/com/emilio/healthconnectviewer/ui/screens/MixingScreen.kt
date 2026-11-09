package com.emilio.healthconnectviewer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilio.healthconnectviewer.data.AppDataType
import com.emilio.healthconnectviewer.ui.LoadState
import com.emilio.healthconnectviewer.ui.MainViewModel
import com.emilio.healthconnectviewer.ui.Timeframe
import com.emilio.healthconnectviewer.ui.components.AppHeader
import com.emilio.healthconnectviewer.ui.components.EmptyBox
import com.emilio.healthconnectviewer.ui.components.ErrorBox
import com.emilio.healthconnectviewer.ui.components.LoadingBox
import com.emilio.healthconnectviewer.ui.components.MissingPermissionNotice
import com.emilio.healthconnectviewer.ui.components.RangeData
import com.emilio.healthconnectviewer.ui.components.SleepChart
import com.emilio.healthconnectviewer.ui.components.sourceLabel
import kotlin.math.abs

@Composable
fun MixingScreen(
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
    val estimatedOrigins by viewModel.estimatedOrigins.collectAsState()
    val originsWithData by viewModel.originsWithData.collectAsState()

    val availableOrigins by viewModel.availableOrigins.collectAsState()
    val selectedOrigins by viewModel.selectedOrigins.collectAsState()
    val sleepSessions by viewModel.sleepSessions.collectAsState()
    val aggregatedStepsData by viewModel.aggregatedStepsData.collectAsState()
    val trendPercent by viewModel.trendPercent.collectAsState()

    val accent = Color(0xFF10B981)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        AppHeader(
            title = "Mixing",
            subtitle = "Merge multiple sources into a single timeline",
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

        SourceSelectorCard(
            availableOrigins = availableOrigins,
            selectedOrigins = selectedOrigins,
            onSelect = { viewModel.toggleOrigin(it) },
            label = "Data Sources for Aggregation",
            multiSelect = true,
            hint = "Select sources to include in the aggregated view.",
            originsWithData = originsWithData,
            // Steps on this screen come from the deduplicated aggregation Health
            // Connect does itself, so nothing there is interpolated.
            estimatedNote = if (selectedDataType == AppDataType.STEPS) {
                emptyList()
            } else {
                selectedOrigins.filter { it in estimatedOrigins }.map(::sourceLabel)
            }
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
                        SleepChart(sessions = filteredSessions, modifier = Modifier.padding(16.dp))
                    } else {
                        EmptyBox("No sleep data from the selected sources for this period.")
                    }
                }
            }

            selectedDataType == AppDataType.STEPS -> {
                Card(
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    if (aggregatedStepsData.isNotEmpty()) {
                        SeriesChart(
                            series = remember(aggregatedStepsData) {
                                aggregatedStepsData.map { it.mixedValue }
                            },
                            timeLabels = remember(aggregatedStepsData) {
                                aggregatedStepsData.map { it.timeLabel }
                            },
                            dataType = selectedDataType,
                            timeframe = timeframe,
                            trendPercent = trendPercent,
                            accent = accent,
                            title = "Mixed (Deduplicated) Steps"
                        )
                    } else {
                        EmptyBox("No steps recorded in Health Connect for this period.")
                    }
                }
            }

            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    // The mix is recomputed from the origins the user actually
                    // selected rather than reusing the pre-computed all-sources value.
                    val series = remember(chartData, selectedOrigins) {
                        chartData.map { dp ->
                            val values = selectedOrigins.mapNotNull { dp.valuesByOrigin[it] }
                            if (values.isEmpty()) null else values.average()
                        }
                    }

                    if (series.any { it != null }) {
                        val rangeBars = if (
                            (timeframe == Timeframe.WEEK || timeframe == Timeframe.MONTH) &&
                            !selectedDataType.isCumulative
                        ) {
                            chartData.map { dp ->
                                val values = selectedOrigins.mapNotNull { dp.valuesByOrigin[it] }
                                if (values.isEmpty()) {
                                    null
                                } else {
                                    RangeData(
                                        min = dp.minValue ?: values.min(),
                                        max = dp.maxValue ?: values.max(),
                                        avg = values.average()
                                    )
                                }
                            }
                        } else {
                            null
                        }

                        SeriesChart(
                            series = series,
                            timeLabels = remember(chartData) { chartData.map { it.timeLabel } },
                            dataType = selectedDataType,
                            timeframe = timeframe,
                            trendPercent = trendPercent,
                            accent = accent,
                            title = "Mixed (Averaged) ${selectedDataType.title}",
                            rangeData = rangeBars
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
fun TrendBadge(trendPercent: Double?, dataType: AppDataType = AppDataType.STEPS) {
    if (trendPercent == null) return

    val isPositive = trendPercent > 0
    // Health-oriented reading: a lower resting heart rate is an improvement,
    // more steps are an improvement.
    val isGood = when (dataType) {
        AppDataType.HEART_RATE -> !isPositive
        else -> isPositive
    }
    val trendColor = if (isGood) Color(0xFF10B981) else Color(0xFFEF4444)
    val arrow = if (isPositive) "↑" else "↓"

    Text(
        text = "$arrow ${String.format("%.1f", abs(trendPercent))}%",
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = trendColor
    )
}
