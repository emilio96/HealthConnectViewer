package com.emilio.healthconnectviewer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.records.SleepSessionRecord
import com.emilio.healthconnectviewer.data.GapFill
import com.emilio.healthconnectviewer.data.reconstructStages
import com.emilio.healthconnectviewer.data.stageTypeToIndex

/**
 * Confusion matrix comparing sleep stages between two sources.
 * 
 * Each cell (i, j) shows: of the time Source A was in stage i,
 * what percentage of that time was Source B in stage j?
 * 
 * Rows = Source A stages, Columns = Source B stages.
 * Row percentages sum to 100%.
 */

private val stageNames = listOf("Deep", "Light", "REM", "Awake")

/**
 * Build confusion matrix from two concurrent sleep sessions.
 * Returns a 4x4 array where [i][j] = percentage of time
 * when source A was in stage i AND source B was in stage j.
 * Each row sums to 100%.
 */
private fun buildConfusionMatrix(
    sessionsA: List<SleepSessionRecord>,
    sessionsB: List<SleepSessionRecord>
): ConfusionResult? {
    // Both sides go through the same reconstruction the hypnogram uses. Scoring the
    // raw stages instead would compare a source that reports Awake against one that
    // leaves it as a gap, and the missing stage would silently vanish from the
    // matrix rather than counting as disagreement.
    val a = reconstructStages(sessionsA)
    val b = reconstructStages(sessionsB)
    val stagesA = a.stages
    val stagesB = b.stages

    if (stagesA.isEmpty() || stagesB.isEmpty()) return null

    // Count overlap minutes between each pair of stage types
    // matrix[i][j] = total minutes where A=stage_i and B=stage_j
    val matrix = Array(4) { DoubleArray(4) }

    // Both sides are sorted, so B only needs to be scanned from the first stage
    // that can still overlap the current A stage.
    var scanStart = 0
    for (stageA in stagesA) {
        val aStart = stageA.startTime.toEpochMilli()
        val aEnd = stageA.endTime.toEpochMilli()
        val aIdx = stageTypeToIndex(stageA.stageType)

        while (scanStart < stagesB.size && stagesB[scanStart].endTime.toEpochMilli() <= aStart) {
            scanStart++
        }

        var j = scanStart
        while (j < stagesB.size && stagesB[j].startTime.toEpochMilli() < aEnd) {
            val stageB = stagesB[j]
            val overlapStart = maxOf(aStart, stageB.startTime.toEpochMilli())
            val overlapEnd = minOf(aEnd, stageB.endTime.toEpochMilli())

            if (overlapEnd > overlapStart) {
                matrix[aIdx][stageTypeToIndex(stageB.stageType)] += (overlapEnd - overlapStart) / 60000.0
            }
            j++
        }
    }
    
    // Convert to row percentages
    val percentMatrix = Array(4) { DoubleArray(4) }
    for (i in 0..3) {
        val rowSum = matrix[i].sum()
        if (rowSum > 0) {
            for (j in 0..3) {
                percentMatrix[i][j] = (matrix[i][j] / rowSum) * 100.0
            }
        }
    }
    
    return ConfusionResult(percentMatrix, a.gapFill, b.gapFill)
}

private fun gapFillLabel(sourceName: String, fill: GapFill): String? = when (fill) {
    GapFill.NONE -> null
    GapFill.AWAKE -> "$sourceName never reports Awake, so its unlabelled gaps are scored as Awake"
    GapFill.REM -> "$sourceName never reports REM, so its unlabelled gaps are scored as REM"
}

private data class ConfusionResult(
    val matrix: Array<DoubleArray>,
    val gapFillA: GapFill,
    val gapFillB: GapFill
)

@Composable
fun SleepComparisonTable(
    sessionsA: List<SleepSessionRecord>,
    sessionsB: List<SleepSessionRecord>,
    sourceAName: String,
    sourceBName: String,
    modifier: Modifier = Modifier
) {
    val result = buildConfusionMatrix(sessionsA, sessionsB)

    if (result == null) {
        Box(modifier = modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
            Text("Insufficient stage data for comparison.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val matrix = result.matrix
    val headerColor = Color(0xFF93C5FD) // Light blue
    val diagColor = Color(0xFFBBF7D0)   // Light green for diagonal (agreement)
    val cellBorder = Color(0xFFE2E8F0)
    
    Column(modifier = modifier.fillMaxWidth()) {
        // Title
        Text(
            "Sleep Stage Comparison",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Rows: ${sourceAName} • Columns: ${sourceBName}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Each row shows what % of time $sourceBName was in each stage when $sourceAName was in a given stage.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val reconstructed = listOfNotNull(
            gapFillLabel(sourceAName, result.gapFillA),
            gapFillLabel(sourceBName, result.gapFillB)
        )
        if (reconstructed.isNotEmpty()) {
            Text(
                "Includes reconstructed stages: ${reconstructed.joinToString("; ")}.",
                fontSize = 11.sp,
                color = Color(0xFF92400E)
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Table
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, cellBorder, RoundedCornerShape(8.dp))
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                // Empty top-left cell
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFFF1F5F9))
                        .border(0.5.dp, cellBorder)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("", fontSize = 12.sp)
                }
                // Column headers (Source B stages)
                stageNames.forEach { name ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(headerColor)
                            .border(0.5.dp, cellBorder)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E3A5F)
                        )
                    }
                }
            }
            
            // Data rows (Source A stages)
            stageNames.forEachIndexed { rowIdx, rowName ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    // Row header
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(headerColor)
                            .border(0.5.dp, cellBorder)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            rowName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E3A5F)
                        )
                    }
                    
                    // Data cells
                    stageNames.forEachIndexed { colIdx, _ ->
                        val value = matrix[rowIdx][colIdx]
                        val isDiagonal = rowIdx == colIdx
                        val bgColor = when {
                            isDiagonal && value > 30 -> diagColor
                            isDiagonal -> Color(0xFFD1FAE5)
                            value > 40 -> Color(0xFFFEF3C7) // High off-diagonal → warning yellow
                            else -> Color.White
                        }
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(bgColor)
                                .border(0.5.dp, cellBorder)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (value > 0.05) "${String.format("%.1f", value)}%" else "0%",
                                fontSize = 12.sp,
                                fontWeight = if (isDiagonal) FontWeight.Bold else FontWeight.Normal,
                                color = if (isDiagonal) Color(0xFF065F46) else Color(0xFF475569),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Agreement score
        val agreement = (0..3).sumOf { matrix[it][it] } / 4.0
        val agreementColor = when {
            agreement > 70 -> Color(0xFF10B981)
            agreement > 40 -> Color(0xFFF59E0B)
            else -> Color(0xFFEF4444)
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Overall Agreement: ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${String.format("%.1f", agreement)}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = agreementColor
            )
        }
    }
}
