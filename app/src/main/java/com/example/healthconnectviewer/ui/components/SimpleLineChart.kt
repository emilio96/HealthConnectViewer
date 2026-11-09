package com.example.healthconnectviewer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun SimpleLineChart(
    data: List<Double>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val maxVal = data.maxOrNull() ?: 1.0
        val minVal = data.minOrNull() ?: 0.0
        val range = (maxVal - minVal).coerceAtLeast(1.0)
        
        val width = size.width
        val height = size.height
        
        val xStep = width / (data.size - 1).coerceAtLeast(1).toFloat()
        
        val path = Path()
        
        data.forEachIndexed { index, value ->
            val x = index * xStep
            // Invert Y since 0 is at top
            val y = height - ((value - minVal) / range * height).toFloat()
            
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 4f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
