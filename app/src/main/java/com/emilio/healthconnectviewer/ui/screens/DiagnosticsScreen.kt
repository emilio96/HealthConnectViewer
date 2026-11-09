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
import com.emilio.healthconnectviewer.data.MAX_USABLE_SAMPLING_SECONDS
import com.emilio.healthconnectviewer.data.OriginDiagnostic
import com.emilio.healthconnectviewer.data.TypeDiagnostic
import com.emilio.healthconnectviewer.ui.LoadState
import com.emilio.healthconnectviewer.ui.MainViewModel
import com.emilio.healthconnectviewer.ui.components.AppHeader
import com.emilio.healthconnectviewer.ui.components.ErrorBox
import com.emilio.healthconnectviewer.ui.components.LoadingBox
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What Health Connect actually holds, per data type and per writing app.
 *
 * The charts cannot distinguish "the permission was never granted" from "the watch
 * never synced this" from "the app read the wrong window" — all three render as an
 * empty chart. This screen separates them.
 */
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel, onOpenDrawer: () -> Unit) {
    val timeframe by viewModel.timeframe.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val state by viewModel.diagnosticsState.collectAsState()
    val isAuthorized by viewModel.isAuthorized.collectAsState()

    LaunchedEffect(selectedDate, isAuthorized) {
        viewModel.fetchDiagnostics()
    }

    val formatter = remember {
        DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        AppHeader(
            title = "Diagnostics",
            subtitle = "What Health Connect actually holds",
            selectedDate = selectedDate,
            timeframe = timeframe,
            onOpenDrawer = onOpenDrawer,
            onNavigateDate = { viewModel.navigateDate(it) },
            onSetToday = { viewModel.setDateToToday() },
            onSetTimeframe = { viewModel.setTimeframe(it) }
        )

        Text(
            "Window: noon the day before to noon the day after, so a whole night is " +
                "always inside it. Counts are raw records as written by each app — nothing " +
                "here is aggregated, filtered or interpolated.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        when (val s = state) {
            is LoadState.Loading -> LoadingBox(message = "Scanning Health Connect…")
            is LoadState.Failed -> ErrorBox(s.message, onRetry = { viewModel.fetchDiagnostics() })
            else -> diagnostics.forEach { diagnostic ->
                TypeCard(diagnostic, formatter)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun TypeCard(diagnostic: TypeDiagnostic, formatter: DateTimeFormatter) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    diagnostic.type.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    if (diagnostic.granted) "permission granted" else "PERMISSION MISSING",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (diagnostic.granted) Color(0xFF10B981) else Color(0xFFEF4444)
                )
            }

            when {
                diagnostic.error != null -> Text(
                    diagnostic.error,
                    fontSize = 12.sp,
                    color = Color(0xFFEF4444),
                    modifier = Modifier.padding(top = 6.dp)
                )

                !diagnostic.granted -> Text(
                    "Not readable, so nothing was requested. Grant it in Health Connect " +
                        "under App permissions.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                diagnostic.byOrigin.isEmpty() -> Text(
                    "Permission is granted and Health Connect returned nothing for this " +
                        "window. No app has written this data type — the watch either does " +
                        "not export it or has not synced.",
                    fontSize = 12.sp,
                    color = Color(0xFF92400E),
                    modifier = Modifier.padding(top = 6.dp)
                )

                else -> {
                    Text(
                        "${diagnostic.totalRecords} records, ${diagnostic.totalSamples} readings",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    diagnostic.byOrigin.forEach { origin ->
                        OriginRow(origin, formatter)
                    }
                }
            }
        }
    }
}

@Composable
private fun OriginRow(origin: OriginDiagnostic, formatter: DateTimeFormatter) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            origin.origin,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "${origin.records} records · ${origin.samples} readings",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (origin.first != null && origin.last != null) {
            Text(
                "${formatter.format(origin.first)} → ${formatter.format(origin.last)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SamplingRow(origin.medianGapSeconds)
    }
}

@Composable
private fun SamplingRow(medianGapSeconds: Double?) {
    if (medianGapSeconds == null) {
        Text(
            "single reading — no interval to measure",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF92400E)
        )
        return
    }

    // The same threshold the overnight analysis uses to decide whether an index is
    // meaningful, surfaced here so the cause is visible rather than inferred.
    val usable = medianGapSeconds <= MAX_USABLE_SAMPLING_SECONDS
    Text(
        "one reading every ~${formatGap(medianGapSeconds)}" +
            if (usable) "" else " — too sparse to resolve overnight events",
        fontSize = 11.sp,
        fontWeight = if (usable) FontWeight.Normal else FontWeight.SemiBold,
        color = if (usable) Color(0xFF10B981) else Color(0xFF92400E)
    )
}

private fun formatGap(seconds: Double): String = when {
    seconds < 90 -> "${seconds.toInt()} s"
    seconds < 5400 -> "${(seconds / 60).toInt()} min"
    else -> String.format("%.1f h", seconds / 3600)
}
