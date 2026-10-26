package app.todo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.todo.ui.theme.LocalTodoPalette

enum class TodoIconKind {
    PLUS,
    CHECK,
    CLOSE,
    TRASH,
    BELL,
    MAIL,
    CLOUD,
    LOCK,
    INFO,
    BACK,
    REFRESH,
    SLIDERS,
    PIN,
    CALENDAR,
    CLOCK,
    FINGERPRINT,
    LIST,
    MORE,
}

/**
 * Icons are drawn with the Canvas API instead of pulling in material-icons:
 * the app only needs a dozen glyphs and this keeps the visual language (1.8dp
 * rounded strokes on a 24dp grid) identical to the neumorphic components.
 */
@Composable
fun TodoIcon(
    kind: TodoIconKind,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalTodoPalette.current.textPrimary,
    size: Dp = 22.dp,
    strokeWidth: Dp = 1.8.dp,
) {
    val semanticsModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }
    Canvas(semanticsModifier.size(size)) {
        val unit = this.size.minDimension / 24f
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun path(block: Path.() -> Unit) = Path().apply(block)

        when (kind) {
            TodoIconKind.PLUS -> {
                drawLine(tint, p(12f, 5f), p(12f, 19f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(5f, 12f), p(19f, 12f), stroke.width, StrokeCap.Round)
            }
            TodoIconKind.CHECK -> drawPath(
                path {
                    moveTo(5f * unit, 12.5f * unit)
                    lineTo(10f * unit, 17.5f * unit)
                    lineTo(19f * unit, 7f * unit)
                },
                tint,
                style = stroke,
            )
            TodoIconKind.CLOSE -> {
                drawLine(tint, p(6f, 6f), p(18f, 18f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(18f, 6f), p(6f, 18f), stroke.width, StrokeCap.Round)
            }
            TodoIconKind.TRASH -> {
                drawLine(tint, p(4f, 7f), p(20f, 7f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(9f, 7f), p(9f, 4.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(15f, 7f), p(15f, 4.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(9f, 4.5f), p(15f, 4.5f), stroke.width, StrokeCap.Round)
                drawPath(
                    path {
                        moveTo(6.5f * unit, 7f * unit)
                        lineTo(7.5f * unit, 20f * unit)
                        lineTo(16.5f * unit, 20f * unit)
                        lineTo(17.5f * unit, 7f * unit)
                    },
                    tint,
                    style = stroke,
                )
                drawLine(tint, p(10.5f, 10.5f), p(10.5f, 16.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(13.5f, 10.5f), p(13.5f, 16.5f), stroke.width, StrokeCap.Round)
            }
            TodoIconKind.BELL -> {
                drawArc(
                    color = tint,
                    startAngle = 190f,
                    sweepAngle = 160f,
                    useCenter = false,
                    topLeft = p(5f, 5f),
                    size = Size(14f * unit, 14f * unit),
                    style = stroke,
                )
                drawLine(tint, p(5.2f, 15.5f), p(18.8f, 15.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(12f, 4f), p(12f, 5.5f), stroke.width, StrokeCap.Round)
                drawCircle(tint, radius = 1.2f * unit, center = p(12f, 18.5f))
            }
            TodoIconKind.MAIL -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(3f, 6f),
                    size = Size(18f * unit, 12f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
                    style = stroke,
                )
                drawPath(
                    path {
                        moveTo(4f * unit, 7.5f * unit)
                        lineTo(12f * unit, 13f * unit)
                        lineTo(20f * unit, 7.5f * unit)
                    },
                    tint,
                    style = stroke,
                )
            }
            TodoIconKind.CLOUD -> {
                drawCircle(tint, radius = 3.6f * unit, center = p(9f, 14f))
                drawCircle(tint, radius = 4.4f * unit, center = p(15f, 13.4f))
                drawCircle(tint, radius = 4.2f * unit, center = p(12f, 10.6f))
                drawRect(tint, topLeft = p(5.4f, 13f), size = Size(13.2f * unit, 4.6f * unit))
            }
            TodoIconKind.LOCK -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(5f, 11f),
                    size = Size(14f * unit, 9f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
                    style = stroke,
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = p(8f, 5f),
                    size = Size(8f * unit, 8f * unit),
                    style = stroke,
                )
                drawLine(tint, p(8f, 9f), p(8f, 11f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(16f, 9f), p(16f, 11f), stroke.width, StrokeCap.Round)
            }
            TodoIconKind.INFO -> {
                drawCircle(tint, radius = 9f * unit, center = p(12f, 12f), style = stroke)
                drawLine(tint, p(12f, 11f), p(12f, 16.5f), stroke.width, StrokeCap.Round)
                drawCircle(tint, radius = 1.1f * unit, center = p(12f, 7.8f))
            }
            TodoIconKind.BACK -> drawPath(
                path {
                    moveTo(15f * unit, 5f * unit)
                    lineTo(8f * unit, 12f * unit)
                    lineTo(15f * unit, 19f * unit)
                },
                tint,
                style = stroke,
            )
            TodoIconKind.REFRESH -> {
                drawArc(
                    color = tint,
                    startAngle = 40f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = p(4f, 4f),
                    size = Size(16f * unit, 16f * unit),
                    style = stroke,
                )
                drawPath(
                    path {
                        moveTo(17.5f * unit, 3.5f * unit)
                        lineTo(20f * unit, 7.5f * unit)
                        lineTo(15.5f * unit, 8f * unit)
                    },
                    tint,
                    style = stroke,
                )
            }
            TodoIconKind.SLIDERS -> {
                listOf(8f to 8f, 12f to 15f, 16f to 10f).forEach { (y, knob) ->
                    drawLine(tint, p(4f, y), p(20f, y), stroke.width, StrokeCap.Round)
                    drawCircle(tint, radius = 2.2f * unit, center = p(knob, y), style = Stroke(stroke.width * 0.9f))
                }
            }
            TodoIconKind.PIN -> {
                drawLine(tint, p(12f, 3.5f), p(12f, 14f), stroke.width, StrokeCap.Round)
                drawCircle(tint, radius = 3.4f * unit, center = p(12f, 17.5f), style = stroke)
                drawLine(tint, p(8f, 7f), p(16f, 7f), stroke.width, StrokeCap.Round)
            }
            TodoIconKind.CALENDAR -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(4f, 6f),
                    size = Size(16f * unit, 14f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
                    style = stroke,
                )
                drawLine(tint, p(4f, 10.5f), p(20f, 10.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(8.5f, 3.5f), p(8.5f, 7f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(15.5f, 3.5f), p(15.5f, 7f), stroke.width, StrokeCap.Round)
                drawCircle(tint, radius = 1f * unit, center = p(9f, 14.5f))
                drawCircle(tint, radius = 1f * unit, center = p(13f, 14.5f))
            }
            TodoIconKind.CLOCK -> {
                drawCircle(tint, radius = 8.5f * unit, center = p(12f, 12f), style = stroke)
                drawLine(tint, p(12f, 7.5f), p(12f, 12f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(12f, 12f), p(15.5f, 14f), stroke.width, StrokeCap.Round)
            }
            TodoIconKind.FINGERPRINT -> {
                for (radius in listOf(4.5f, 6.5f, 8.5f)) {
                    drawArc(
                        color = tint,
                        startAngle = 200f,
                        sweepAngle = 240f,
                        useCenter = false,
                        topLeft = p(12f - radius, 12f - radius),
                        size = Size(radius * 2f * unit, radius * 2f * unit),
                        style = Stroke(stroke.width * 0.85f, cap = StrokeCap.Round),
                    )
                }
                drawLine(tint, p(12f, 12f), p(12f, 18f), stroke.width * 0.85f, StrokeCap.Round)
            }
            TodoIconKind.LIST -> {
                listOf(7f, 12f, 17f).forEach { y ->
                    drawCircle(tint, radius = 1.1f * unit, center = p(5f, y))
                    drawLine(tint, p(9f, y), p(20f, y), stroke.width, StrokeCap.Round)
                }
            }
            TodoIconKind.MORE -> {
                listOf(6f, 12f, 18f).forEach { x ->
                    drawCircle(tint, radius = 1.4f * unit, center = p(x, 12f))
                }
            }
        }
    }
}
