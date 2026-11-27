package com.echoguide.audiolab

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class GeometryShape(val label: String) {
    HORIZONTAL_LINE("横线"),
    VERTICAL_LINE("竖线"),
    RING("圆环"),
    RECTANGLE("矩形平面"),
    PIT("凹陷圆形")
}

data class Vec3(val x: Float, val y: Float, val z: Float)

data class GeometryConfig(
    val shape: GeometryShape = GeometryShape.HORIZONTAL_LINE,
    val sourceCount: Int = 8,
    val distance: Float = 2f,
    val size: Float = 1.2f,
    val verticalOffset: Float = 0f,
    val pitDepth: Float = 0.4f
)

object GeometryGenerator {
    fun generate(config: GeometryConfig): List<Vec3> {
        val count = config.sourceCount.coerceIn(4, 32)
        val distance = config.distance.coerceIn(0.5f, 5f)
        val size = config.size.coerceIn(0.2f, 3f)
        val offset = config.verticalOffset.coerceIn(-5f, 5f)
        return when (config.shape) {
            GeometryShape.HORIZONTAL_LINE -> horizontalLine(count, distance, size, offset)
            GeometryShape.VERTICAL_LINE -> verticalLine(count, distance, size, offset)
            GeometryShape.RING -> ring(count, distance, size, offset)
            GeometryShape.RECTANGLE -> rectangle(count, distance, size, offset)
            GeometryShape.PIT -> pit(count, distance, size, offset, config.pitDepth.coerceIn(0.1f, 1.2f))
        }
    }

    private fun horizontalLine(count: Int, distance: Float, size: Float, y: Float): List<Vec3> =
        (0 until count).map { index ->
            val fraction = index.toFloat() / (count - 1)
            Vec3((fraction - 0.5f) * size, y, distance)
        }

    private fun verticalLine(count: Int, distance: Float, size: Float, centerY: Float): List<Vec3> =
        (0 until count).map { index ->
            val fraction = index.toFloat() / (count - 1)
            Vec3(0f, centerY + (fraction - 0.5f) * size, distance)
        }

    private fun ring(count: Int, distance: Float, size: Float, centerY: Float): List<Vec3> {
        val radius = size / 2f
        return (0 until count).map { index ->
            val angle = (2.0 * PI * index / count).toFloat()
            Vec3(radius * cos(angle), centerY + radius * sin(angle), distance)
        }
    }

    private fun rectangle(count: Int, distance: Float, size: Float, centerY: Float): List<Vec3> {
        val columns = ceil(sqrt(count * 1.5)).toInt().coerceAtLeast(2)
        val rows = ceil(count.toDouble() / columns).toInt().coerceAtLeast(2)
        return (0 until count).map { index ->
            val row = index / columns
            val column = index % columns
            val xf = column.toFloat() / (columns - 1)
            val yf = row.toFloat() / (rows - 1)
            Vec3((xf - 0.5f) * size, centerY + (0.5f - yf) * size * 0.65f, distance)
        }
    }

    private fun pit(count: Int, distance: Float, size: Float, centerY: Float, depth: Float): List<Vec3> {
        val radius = size / 2f
        val goldenAngle = PI * (3.0 - sqrt(5.0))
        return (0 until count).map { index ->
            val normalizedRadius = sqrt((index + 0.5f) / count.toFloat())
            val angle = (index * goldenAngle).toFloat()
            val radialDistance = radius * normalizedRadius
            Vec3(
                radialDistance * cos(angle),
                centerY - depth * (1f - normalizedRadius),
                distance + radius + radialDistance * sin(angle)
            )
        }
    }
}