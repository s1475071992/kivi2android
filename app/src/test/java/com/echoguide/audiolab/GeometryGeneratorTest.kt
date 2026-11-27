package com.echoguide.audiolab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GeometryGeneratorTest {
    @Test
    fun horizontalLineHasRequestedCountAndConstantDepth() {
        val points = GeometryGenerator.generate(GeometryConfig(GeometryShape.HORIZONTAL_LINE, 8, 2f, 1.6f))
        assertEquals(8, points.size)
        assertTrue(points.all { abs(it.y) < 1e-5f && abs(it.z - 2f) < 1e-5f })
        assertTrue(points.zipWithNext().all { (a, b) -> a.x < b.x })
    }

    @Test
    fun verticalLineHasRequestedCountAndConstantHorizontalPosition() {
        val points = GeometryGenerator.generate(GeometryConfig(GeometryShape.VERTICAL_LINE, 7, 3f, 1.2f))
        assertEquals(7, points.size)
        assertTrue(points.all { abs(it.x) < 1e-5f && abs(it.z - 3f) < 1e-5f })
    }

    @Test
    fun ringPointsLieOnCircle() {
        val points = GeometryGenerator.generate(GeometryConfig(GeometryShape.RING, 12, 2.5f, 1f))
        assertEquals(12, points.size)
        assertTrue(points.all { abs(sqrt(it.x * it.x + it.y * it.y) - 0.5f) < 1e-4f })
    }

    @Test
    fun rectanglePointsStayWithinBounds() {
        val points = GeometryGenerator.generate(GeometryConfig(GeometryShape.RECTANGLE, 16, 2f, 2f))
        assertEquals(16, points.size)
        assertTrue(points.all { abs(it.x) <= 1.0001f && it.y in -0.651f..0.651f })
    }

    @Test
    fun pitHasLowerInteriorThanOuterArea() {
        val points = GeometryGenerator.generate(GeometryConfig(GeometryShape.PIT, 24, 2f, 1.2f, pitDepth = 0.4f))
        assertEquals(24, points.size)
        assertTrue(points.minOf { it.y } < points.maxOf { it.y })
        assertTrue(points.all { it.z > 0f })
    }
}
