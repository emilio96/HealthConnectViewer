package com.emilio.healthconnectviewer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The three outcomes a data read can have, rendered so they cannot be confused
 * with one another. Previously all three collapsed into the same "no data" text.
 */

@Composable
fun LoadingBox(modifier: Modifier = Modifier, message: String = "Reading Health Connect…") {
    Box(
        modifier = modifier.fillMaxWidth().height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 2.dp)
            Text(
                message,
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun ErrorBox(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxWidth().height(200.dp).padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Something went wrong",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEF4444)
            )
            Text(
                message,
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onRetry != null) {
                Button(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

@Composable
fun EmptyBox(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(200.dp).padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Shown when the selected data type specifically has not been granted, which is
 * different from having no data and different again from having no access at all.
 */
@Composable
fun MissingPermissionNotice(
    typeTitle: String,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "$typeTitle is not shared with this app",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color(0xFF92400E)
            )
            Text(
                "Health Connect has not granted read access to $typeTitle, so this chart " +
                    "will stay empty regardless of what your devices recorded.",
                fontSize = 12.sp,
                color = Color(0xFF92400E),
                modifier = Modifier.padding(top = 4.dp)
            )
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Review permissions")
            }
        }
    }
}

/**
 * One line, shown only when the source being looked at is the one that published a
 * daily roll-up. A blanket warning on every steps chart was noise: the other sources
 * on the same screen are measured normally.
 */
@Composable
fun EstimatedDataNotice(sourceNames: List<String>, modifier: Modifier = Modifier) {
    if (sourceNames.isEmpty()) return
    Text(
        text = "${sourceNames.joinToString(", ")}: daily total spread over the day, " +
            "hourly shape estimated.",
        fontSize = 11.sp,
        color = Color(0xFF64748B),
        modifier = modifier.fillMaxWidth().padding(top = 6.dp)
    )
}

/** Short display name for a source package, e.g. com.app.cq.ring -> ring. */
fun sourceLabel(packageName: String): String = packageName.substringAfterLast(".")

/** Shown in place of the whole app when Health Connect itself is missing or stale. */
@Composable
fun HealthConnectUnavailable(
    updateRequired: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (updateRequired) "Health Connect needs updating" else "Health Connect is not available",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                if (updateRequired) {
                    "This device has a version of Health Connect that is too old to talk to " +
                        "this app. Update it from the Play Store and come back."
                } else {
                    "This app reads everything it shows from Health Connect, which is not " +
                        "installed or not supported on this device."
                },
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
