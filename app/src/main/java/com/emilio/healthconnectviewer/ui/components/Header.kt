package com.emilio.healthconnectviewer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilio.healthconnectviewer.ui.Timeframe
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun AppHeader(
    title: String,
    subtitle: String,
    selectedDate: ZonedDateTime,
    timeframe: Timeframe,
    onOpenDrawer: () -> Unit,
    onNavigateDate: (Long) -> Unit,
    onSetToday: () -> Unit,
    onSetTimeframe: (Timeframe) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        
        // Top row: Hamburger Menu + Titles
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Open Menu",
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onOpenDrawer() },
                tint = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))

        // Bottom row: Date Navigator (Left) and Timeframe (Right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Date Navigator
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("<", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.clickable { onNavigateDate(-1) }.padding(horizontal = 8.dp))
                
                val dateStr = when (timeframe) {
                    Timeframe.DAY -> selectedDate.format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
                    Timeframe.WEEK -> "Week of ${selectedDate.format(DateTimeFormatter.ofPattern("MMM dd"))}"
                    Timeframe.MONTH -> selectedDate.format(DateTimeFormatter.ofPattern("MMM yyyy"))
                }
                
                Text(
                    text = dateStr,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                
                Text(">", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.clickable { onNavigateDate(1) }.padding(horizontal = 8.dp))
                
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Today",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onSetToday() }
                )
            }

            // Timeframe Selector
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(2.dp)
            ) {
                Timeframe.values().forEach { tf ->
                    val isSelected = tf == timeframe
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) Color.White else Color.Transparent)
                            .clickable { onSetTimeframe(tf) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = tf.name.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
