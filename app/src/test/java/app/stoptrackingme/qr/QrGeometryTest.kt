package app.stoptrackingme.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrGeometryTest {
    @Test
    fun ordersShuffledCornersAndAcceptsRotation() {
        val result = QrGeometry.validate(
            points = listOf(
                QrPoint(100f, 50f),
                QrPoint(50f, 100f),
                QrPoint(50f, 0f),
                QrPoint(0f, 50f),
            ),
            imageWidth = 120,
            imageHeight = 120,
        ) as QrGeometryResult.Valid

        assertEquals(4, result.quadrilateral.corners.size)
        assertTrue(result.quadrilateral.edgeLengths.all { it > 70f })
    }

    @Test
    fun acceptsMildPerspectiveWithinDocumentedThresholds() {
        val result = QrGeometry.validate(
            points = listOf(
                QrPoint(20f, 25f),
                QrPoint(125f, 15f),
                QrPoint(135f, 120f),
                QrPoint(15f, 130f),
            ),
            imageWidth = 160,
            imageHeight = 150,
        )

        assertTrue(result is QrGeometryResult.Valid)
    }

    @Test
    fun rejectsConcaveAndOutOfBoundsQuadrilaterals() {
        val concave = QrGeometry.validate(
            listOf(
                QrPoint(10f, 10f),
                QrPoint(110f, 10f),
                QrPoint(45f, 45f),
                QrPoint(10f, 110f),
            ),
            120,
            120,
        )
        val outOfBounds = QrGeometry.validate(
            listOf(
                QrPoint(-1f, 10f),
                QrPoint(100f, 10f),
                QrPoint(100f, 100f),
                QrPoint(10f, 100f),
            ),
            120,
            120,
        )

        assertTrue(concave is QrGeometryResult.Invalid)
        assertTrue(outOfBounds is QrGeometryResult.Invalid)
    }

    @Test
    fun rejectsAnglesOutsideSixtyToOneHundredTwentyDegrees() {
        val result = QrGeometry.validate(
            listOf(
                QrPoint(10f, 10f),
                QrPoint(110f, 10f),
                QrPoint(170f, 70f),
                QrPoint(70f, 70f),
            ),
            180,
            100,
        )

        assertTrue(result is QrGeometryResult.Invalid)
        assertTrue((result as QrGeometryResult.Invalid).message.contains("角度"))
    }

    @Test
    fun rejectsOppositeEdgeRatioAboveOnePointFive() {
        val result = QrGeometry.validate(
            listOf(
                QrPoint(70f, 10f),
                QrPoint(170f, 10f),
                QrPoint(170f, 170f),
                QrPoint(10f, 170f),
            ),
            200,
            200,
        )

        assertTrue(result is QrGeometryResult.Invalid)
        assertTrue((result as QrGeometryResult.Invalid).message.contains("相对边"))
    }

    @Test
    fun includesFourModuleQuietZoneInDensityCheck() {
        val valid = QrGeometry.validate(
            listOf(
                QrPoint(0f, 0f),
                QrPoint(111f, 0f),
                QrPoint(111f, 111f),
                QrPoint(0f, 111f),
            ),
            111,
            111,
        ) as QrGeometryResult.Valid

        assertEquals(3f, QrGeometry.modulePixelSize(valid.quadrilateral, 29), 0.001f)
        assertTrue(QrGeometry.modulePixelSize(valid.quadrilateral, 33) < 3f)
    }
}
