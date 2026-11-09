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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilio.healthconnectviewer.data.AnalysisQuality
import com.emilio.healthconnectviewer.data.DESAT_DROP_PERCENT
import com.emilio.healthconnectviewer.data.MAX_USABLE_SAMPLING_SECONDS
import com.emilio.healthconnectviewer.data.SleepWindowStrategy
import com.emilio.healthconnectviewer.ui.AnalysisResult
import com.emilio.healthconnectviewer.ui.LoadState
import com.emilio.healthconnectviewer.ui.MainViewModel
import com.emilio.healthconnectviewer.ui.components.AdvancedChart
import com.emilio.healthconnectviewer.ui.components.AppHeader
import com.emilio.healthconnectviewer.ui.components.EmptyBox
import com.emilio.healthconnectviewer.ui.components.ErrorBox
import com.emilio.healthconnectviewer.ui.components.LoadingBox

@Composable
fun AnalysisScreen(viewModel: MainViewModel, onOpenDrawer: () -> Unit) {
    val timeframe by viewModel.timeframe.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val analysisResult by viewModel.analysisResult.collectAsState()
    val analysisState by viewModel.analysisState.collectAsState()
    val isAuthorized by viewModel.isAuthorized.collectAsState()

    // Depends on isAuthorized too: without it the screen sat on "Loading" forever
    // whenever it was first opened before permissions had been granted.
    LaunchedEffect(selectedDate, isAuthorized) {
        viewModel.fetchAnalysisData()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        AppHeader(
            title = "Analysis",
            subtitle = "Overnight oximetry screening",
            selectedDate = selectedDate,
            timeframe = timeframe,
            onOpenDrawer = onOpenDrawer,
            onNavigateDate = { viewModel.navigateDate(it) },
            onSetToday = { viewModel.setDateToToday() },
            onSetTimeframe = { viewModel.setTimeframe(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        MedicalDisclaimer()

        Spacer(modifier = Modifier.height(12.dp))

        val state = analysisState
        val result = analysisResult

        when {
            state is LoadState.Loading -> LoadingBox(message = "Analysing last night…")
            state is LoadState.Failed -> ErrorBox(state.message, onRetry = { viewModel.fetchAnalysisData() })
            result == null -> EmptyBox("No overnight data available for this date.")
            else -> AnalysisContent(result)
        }
    }
}

@Composable
private fun MedicalDisclaimer() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Not a medical device",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF991B1B)
            )
            Text(
                "This screen counts drops in the oxygen saturation your wearable recorded. " +
                    "That is an approximation of the oxygen desaturation index, which is related " +
                    "to but not the same as the apnea-hypopnea index a sleep study measures. " +
                    "It cannot diagnose sleep apnea and must not be used to rule it out. " +
                    "Talk to a clinician about anything that concerns you.",
                fontSize = 11.sp,
                color = Color(0xFF991B1B),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun AnalysisContent(result: AnalysisResult) {
    val analysis = result.desaturation

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Sleep window: ${result.sleepStart} – ${result.sleepEnd}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                windowExplanation(result.windowStrategy),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (result.sourcesUsed.isNotEmpty()) {
                Text(
                    "Sources: ${result.sourcesUsed.joinToString(", ")}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    QualityNotice(result)

    Spacer(modifier = Modifier.height(12.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Screening result",
            value = result.riskLabel,
            valueColor = Color(result.riskColor)
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Desat. index /h",
            value = analysis.indexPerHour?.let { String.format("%.1f", it) } ?: "—",
            caption = "${analysis.events.size} events scored"
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Readings",
            value = analysis.sampleCount.toString(),
            caption = analysis.medianSamplingSeconds
                ?.let { "every ~${formatSeconds(it)}" }
                ?: "no interval"
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Avg SpO2",
            value = result.avgSpo2?.let { String.format("%.1f%%", it) } ?: "N/A",
            valueColor = Color(0xFF06B6D4)
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Min SpO2",
            value = result.minSpo2?.let { String.format("%.1f%%", it) } ?: "N/A",
            valueColor = Color(0xFFEF4444)
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Avg HR",
            value = result.avgHr?.let { String.format("%.0f bpm", it) } ?: "N/A",
            valueColor = Color(0xFFF59E0B)
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    OvernightChart(
        title = "Overnight SpO2 (%)",
        titleColor = Color(0xFF06B6D4),
        values = remember(result.spo2Data) { result.spo2Data.map { it.mixedValue } },
        labels = remember(result.spo2Data) { result.spo2Data.map { it.timeLabel } },
        unit = "%",
        averageValue = result.avgSpo2,
        emptyMessage = "No SpO2 data recorded during this sleep window."
    )

    Spacer(modifier = Modifier.height(16.dp))

    OvernightChart(
        title = "Overnight Heart Rate (bpm)",
        titleColor = Color(0xFFEF4444),
        values = remember(result.hrData) { result.hrData.map { it.mixedValue } },
        labels = remember(result.hrData) { result.hrData.map { it.timeLabel } },
        unit = "bpm",
        averageValue = result.avgHr,
        emptyMessage = "No heart rate data recorded during this sleep window."
    )
}

/**
 * Says out loud why the index is or is not being shown. A sparse night now reports
 * that it is sparse instead of turning a handful of averaged readings into a
 * confident severity band.
 */
@Composable
private fun QualityNotice(result: AnalysisResult) {
    val analysis = result.desaturation
    val (background, textColor, message) = when (analysis.quality) {
        AnalysisQuality.ADEQUATE -> Triple(
            Color(0xFFECFDF5),
            Color(0xFF065F46),
            "Scored ${analysis.events.size} desaturations of at least " +
                "${DESAT_DROP_PERCENT.toInt()}% below the preceding baseline, over " +
                "${String.format("%.1f", analysis.monitoredHours)} h of monitored sleep."
        )
        AnalysisQuality.TOO_SPARSE -> Triple(
            Color(0xFFFEF3C7),
            Color(0xFF92400E),
            "No index reported: this night was sampled roughly every " +
                "${analysis.medianSamplingSeconds?.let { formatSeconds(it) } ?: "?"}, and a " +
                "respiratory desaturation lasts tens of seconds. Readings that far apart " +
                "cannot resolve individual events — anything above " +
                "${MAX_USABLE_SAMPLING_SECONDS.toInt()} s between samples is not usable. " +
                "Continuous overnight SpO2 needs to be enabled on the wearable."
        )
        AnalysisQuality.INSUFFICIENT_DATA -> Triple(
            Color(0xFFF1F5F9),
            Color(0xFF475569),
            "Not enough SpO2 readings in this window to establish a baseline " +
                "(${analysis.sampleCount} found)."
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            message,
            fontSize = 11.sp,
            color = textColor,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color(0xFF0F172A),
    caption: String? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = valueColor)
            if (caption != null) {
                Text(
                    caption,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OvernightChart(
    title: String,
    titleColor: Color,
    values: List<Double?>,
    labels: List<String>,
    unit: String,
    averageValue: Double?,
    emptyMessage: String
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Spacer(modifier = Modifier.height(8.dp))

            if (values.any { it != null }) {
                AdvancedChart(
                    data = values,
                    color = titleColor,
                    forceBars = false,
                    modifier = Modifier.fillMaxSize(),
                    timeLabels = labels,
                    unit = unit,
                    averageValue = averageValue
                )
            } else {
                EmptyBox(emptyMessage, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun windowExplanation(strategy: SleepWindowStrategy): String = when (strategy) {
    SleepWindowStrategy.SINGLE_SOURCE ->
        "One source reported sleep, so its own window is used."
    SleepWindowStrategy.INTERSECTION ->
        "Several sources reported sleep; the stretch they all agree on is used."
    SleepWindowStrategy.LONGEST_NO_OVERLAP ->
        "Several sources reported sleep but they barely overlap, so the longest " +
            "single window is used. Treat the averages below with caution."
    SleepWindowStrategy.FALLBACK ->
        "No sleep session was recorded for this night, so a default overnight " +
            "window is used. The index below is not anchored to measured sleep."
}

private fun formatSeconds(seconds: Double): String = when {
    seconds < 90 -> "${seconds.toInt()} s"
    seconds < 5400 -> "${(seconds / 60).toInt()} min"
    else -> String.format("%.1f h", seconds / 3600)
}
