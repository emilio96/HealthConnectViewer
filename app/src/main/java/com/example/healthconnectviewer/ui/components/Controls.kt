package com.example.healthconnectviewer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.healthconnectviewer.data.AppDataType

@Composable
fun PillSelector(
    items: List<String>,
    selectedItems: Set<String>,
    onSelect: (String) -> Unit,
    multiSelect: Boolean = false,
    colorMap: Map<String, Color> = emptyMap(),
    /**
     * Origins with no data in the period on screen. They keep their slot — the tiles
     * must not reshuffle when the user pages to another day — but are dimmed and
     * cannot be selected. An empty set means "no information", so nothing is dimmed.
     */
    availableItems: Set<String>? = null
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it }) { item ->
            val hasData = availableItems == null || item in availableItems
            val isSelected = selectedItems.contains(item)
            val baseColor = colorMap[item] ?: MaterialTheme.colorScheme.primary

            val bgColor = when {
                !hasData -> Color(0xFFF8FAFC)
                isSelected -> baseColor.copy(alpha = 0.1f)
                else -> Color.White
            }
            val borderColor = when {
                !hasData -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                isSelected -> baseColor
                else -> MaterialTheme.colorScheme.outline
            }
            val textColor = when {
                !hasData -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                isSelected -> baseColor
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                    .clickable(enabled = hasData) { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (colorMap.containsKey(item)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isSelected && hasData) baseColor else Color.LightGray)
                    )
                    SpacerRaw(width = 6)
                }
                Text(
                    text = item,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun SpacerRaw(width: Int) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(width.dp))
}

@Composable
fun DataTypeSelector(
    selectedType: AppDataType,
    onSelect: (AppDataType) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(AppDataType.values()) { type ->
            val isSelected = type == selectedType
            
            val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
            val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .clickable { onSelect(type) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = type.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }
        }
    }
}
