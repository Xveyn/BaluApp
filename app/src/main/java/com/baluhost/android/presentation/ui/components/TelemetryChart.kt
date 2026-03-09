package com.baluhost.android.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.ChartValues
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelemetryChart(
    data: List<Pair<Long, Float>>,
    gradientColors: List<Color>,
    yAxisLabel: String,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(data) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = data.indices.toList(),
                    y = data.map { it.second.toDouble() }
                )
            }
        }
    }

    val lineColor = gradientColors.firstOrNull() ?: Color.White
    val axisLabelColor = Color(0xFF94A3B8)
    val guidelineColor = Color(0xFF1E293B)

    val timeFormatter = remember(data) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        CartesianValueFormatter { value, _, _ ->
            val index = value.toInt().coerceIn(0, data.size - 1)
            sdf.format(Date(data[index].first * 1000))
        }
    }

    val lineFill = remember(lineColor) {
        LineCartesianLayer.LineFill.single(fill(lineColor))
    }
    val areaFill = remember(lineColor) {
        LineCartesianLayer.AreaFill.single(fill(lineColor.copy(alpha = 0.15f)))
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    rememberLine(
                        fill = lineFill,
                        areaFill = areaFill
                    )
                )
            ),
            startAxis = rememberStartAxis(
                label = rememberTextComponent(
                    color = axisLabelColor,
                    textSize = 10.sp
                ),
                guideline = rememberLineComponent(
                    color = guidelineColor,
                    thickness = 0.5.dp
                )
            ),
            bottomAxis = rememberBottomAxis(
                label = rememberTextComponent(
                    color = axisLabelColor,
                    textSize = 10.sp
                ),
                guideline = null,
                valueFormatter = timeFormatter
            )
        ),
        modelProducer = modelProducer,
        modifier = modifier
    )
}
