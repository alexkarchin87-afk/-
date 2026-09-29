package com.voiceagent.oneplus13.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Искра — фирменный знак VoiceAgent.
 * Круг с 4-конечной искрой внутри (лёгкая, контурная версия — для заголовков панели).
 */
@Composable
fun SparkIcon(
    size: Dp = 24.dp,
    color: Color = Color.Black,
    strokeWidth: Dp = 1.5.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f

        drawCircle(
            color = color,
            radius = r * 0.85f,
            center = Offset(cx, cy),
            style = Stroke(width = strokeWidth.toPx())
        )

        val arm = r * 0.55f
        val path = Path().apply {
            moveTo(cx, cy - arm)
            lineTo(cx, cy + arm)
            moveTo(cx - arm, cy)
            lineTo(cx + arm, cy)
            moveTo(cx - arm * 0.45f, cy - arm * 0.45f)
            lineTo(cx + arm * 0.45f, cy + arm * 0.45f)
            moveTo(cx + arm * 0.45f, cy - arm * 0.45f)
            lineTo(cx - arm * 0.45f, cy + arm * 0.45f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth.toPx() * 0.8f, cap = StrokeCap.Round)
        )

        drawCircle(
            color = color,
            radius = strokeWidth.toPx() * 0.9f,
            center = Offset(cx, cy)
        )
    }
}

/**
 * Основной значок вызова — круглый бейдж с 4-лучевой "искрой" (гладкие вогнутые
 * грани, а не прямой крест) и четырьмя маленькими угловыми "рисками", как на
 * референсной иконке. Это то, что показывает [FloatingSparkButton] и заголовок
 * углового виджета в Windows-режиме.
 */
@Composable
fun SparkIconPro(
    size: Dp = 56.dp,
    badgeColor: Color = Color.Black,
    glyphColor: Color = Color.White
) {
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val center = Offset(cx, cy)

        drawCircle(color = badgeColor, radius = r, center = center)
        drawFourPointSparkle(center = center, outerRadius = r * 0.62f, color = glyphColor)
        drawCornerTicks(center = center, radius = r, color = glyphColor)
    }
}

/**
 * Гладкая 4-лучевая "искра": два вытянутых лепестка под 90°, каждый — кубическая
 * кривая, а не прямые линии. Даёт мягкий, "премиальный" силуэт вместо простого
 * креста, как на референсном фото.
 */
private fun DrawScope.drawFourPointSparkle(center: Offset, outerRadius: Float, color: Color) {
    val pinch = outerRadius * 0.16f
    val path = Path().apply {
        moveTo(center.x, center.y - outerRadius)
        cubicTo(
            center.x + pinch, center.y - pinch,
            center.x + outerRadius, center.y - pinch,
            center.x + outerRadius, center.y
        )
        cubicTo(
            center.x + outerRadius, center.y + pinch,
            center.x + pinch, center.y + pinch,
            center.x, center.y + outerRadius
        )
        cubicTo(
            center.x - pinch, center.y + pinch,
            center.x - outerRadius, center.y + pinch,
            center.x - outerRadius, center.y
        )
        cubicTo(
            center.x - outerRadius, center.y - pinch,
            center.x - pinch, center.y - pinch,
            center.x, center.y - outerRadius
        )
        close()
    }
    drawPath(path = path, color = color, style = Fill)

    // Маленькая яркая точка в центре, как блик — добавляет "искре" глубины.
    drawCircle(color = color.copy(alpha = 0.55f), radius = outerRadius * 0.16f, center = center)
}

/**
 * Четыре коротких штриха-риски по диагоналям (NE/SE/SW/NW), ближе к краю круга —
 * повторяет "уголки видоискателя" с референсного фото.
 */
private fun DrawScope.drawCornerTicks(center: Offset, radius: Float, color: Color) {
    val tickLength = radius * 0.22f
    val stroke = Stroke(width = radius * 0.05f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val angles = listOf(45.0, 135.0, 225.0, 315.0)
    val distance = radius * 0.62f

    angles.forEach { angleDeg ->
        val angle = Math.toRadians(angleDeg)
        val tickCenter = Offset(
            x = center.x + (distance * cos(angle)).toFloat(),
            y = center.y + (distance * sin(angle)).toFloat()
        )
        val dx = (tickLength / 2f * cos(angle)).toFloat()
        val dy = (tickLength / 2f * sin(angle)).toFloat()
        drawLine(
            color = color,
            start = Offset(tickCenter.x - dx, tickCenter.y - dy),
            end = Offset(tickCenter.x + dx, tickCenter.y + dy),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

/** Простой крестик закрытия — нарисован сам, чтобы не тянуть material-icons-extended. */
@Composable
fun CloseGlyph(size: Dp = 16.dp, color: Color = Color.Black) {
    Canvas(modifier = Modifier.size(size)) {
        val inset = this.size.minDimension * 0.2f
        val stroke = Stroke(width = this.size.minDimension * 0.09f, cap = StrokeCap.Round)
        drawLine(color, Offset(inset, inset), Offset(this.size.width - inset, this.size.height - inset), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(this.size.width - inset, inset), Offset(inset, this.size.height - inset), stroke.width, StrokeCap.Round)
    }
}

/** Простая иконка микрофона — тоже нарисована сама, без внешних зависимостей. */
@Composable
fun MicGlyph(size: Dp = 20.dp, color: Color = Color.Black.copy(alpha = 0.6f)) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val capsuleWidth = w * 0.34f
        val capsuleHeight = h * 0.5f
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)

        drawRoundRect(
            color = color,
            topLeft = Offset((w - capsuleWidth) / 2f, h * 0.06f),
            size = androidx.compose.ui.geometry.Size(capsuleWidth, capsuleHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(capsuleWidth / 2f, capsuleWidth / 2f)
        )

        val standTop = h * 0.06f + capsuleHeight + h * 0.06f
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.42f)
            quadraticBezierTo(w * 0.22f, standTop, w * 0.5f, standTop)
            quadraticBezierTo(w * 0.78f, standTop, w * 0.78f, h * 0.42f)
        }
        drawPath(path = path, color = color, style = stroke)
        drawLine(color, Offset(w * 0.5f, standTop), Offset(w * 0.5f, h * 0.94f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.94f), Offset(w * 0.68f, h * 0.94f), stroke.width, StrokeCap.Round)
    }
}

/** Стрелка "отправить" — своя реализация, чтобы не тянуть material-icons-extended.
 *  Общая для [WindowsCornerPopup] и [AgentSiriPanel], чтобы не дублировать. */
@Composable
fun SendArrowGlyph(size: Dp = 16.dp, color: Color = Color.White) {
    Canvas(modifier = Modifier.size(size)) {
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.5f, 0f)
            lineTo(this@Canvas.size.width, this@Canvas.size.height * 0.55f)
            lineTo(this@Canvas.size.width * 0.62f, this@Canvas.size.height * 0.55f)
            lineTo(this@Canvas.size.width * 0.62f, this@Canvas.size.height)
            lineTo(this@Canvas.size.width * 0.38f, this@Canvas.size.height)
            lineTo(this@Canvas.size.width * 0.38f, this@Canvas.size.height * 0.55f)
            lineTo(0f, this@Canvas.size.height * 0.55f)
            close()
        }
        drawPath(path = path, color = color, style = Fill)
    }
}
