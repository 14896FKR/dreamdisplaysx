package com.dreamdisplayx.platform.server.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class OutlineGeometryTest {
    @Test
    fun oneBlockBoxHasEightCornersAndEveryEdgeSample() {
        val points = mutableListOf<Triple<Double, Double, Double>>()

        OutlineGeometry.forEachPoint(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) { x, y, z ->
            points += Triple(x, y, z)
        }

        assertEquals(36, points.size)
        val corners = points.filter { (x, y, z) ->
            (x == 0.0 || x == 1.0) && (y == 0.0 || y == 1.0) && (z == 0.0 || z == 1.0)
        }.toSet()
        assertEquals(8, corners.size)
    }
}
