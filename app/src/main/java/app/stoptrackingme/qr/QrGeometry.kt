package app.stoptrackingme.qr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

data class QrQuadrilateral(
    val corners: List<QrPoint>,
    val edgeLengths: List<Float>,
) {
    val bounds: QrBounds = QrBounds(
        left = corners.minOf { it.x },
        top = corners.minOf { it.y },
        right = corners.maxOf { it.x },
        bottom = corners.maxOf { it.y },
    )

    val minimumEdgeLength: Float = edgeLengths.min()
}

sealed interface QrGeometryResult {
    data class Valid(val quadrilateral: QrQuadrilateral) : QrGeometryResult
    data class Invalid(val message: String) : QrGeometryResult
}

object QrGeometry {
    const val MINIMUM_ANGLE_DEGREES = 60f
    const val MAXIMUM_ANGLE_DEGREES = 120f
    const val MAXIMUM_OPPOSITE_EDGE_RATIO = 1.5f
    const val MINIMUM_MODULE_PIXELS = 3f
    const val QUIET_ZONE_MODULES = 4

    fun validate(
        points: List<QrPoint>,
        imageWidth: Int,
        imageHeight: Int,
    ): QrGeometryResult {
        if (points.size != 4 || points.distinct().size != 4) {
            return QrGeometryResult.Invalid("无法确定二维码的四个角点")
        }
        if (imageWidth <= 0 || imageHeight <= 0 || points.any { point ->
                !point.x.isFinite() || !point.y.isFinite() ||
                    point.x < 0f || point.y < 0f ||
                    point.x > imageWidth.toFloat() || point.y > imageHeight.toFloat()
            }
        ) {
            return QrGeometryResult.Invalid("二维码静区超出图片边界")
        }

        val centerX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val centerY = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        val ordered = points.sortedBy { atan2(it.y - centerY, it.x - centerX) }
            .let(::rotateToUpperLeft)

        val crosses = ordered.indices.map { index ->
            val first = ordered[index]
            val second = ordered[(index + 1) % ordered.size]
            val third = ordered[(index + 2) % ordered.size]
            cross(first, second, third)
        }
        if (crosses.any { abs(it) < 0.001f } ||
            !(crosses.all { it > 0f } || crosses.all { it < 0f })
        ) {
            return QrGeometryResult.Invalid("二维码角点不是凸四边形")
        }

        val edges = ordered.indices.map { index ->
            distance(ordered[index], ordered[(index + 1) % ordered.size])
        }
        if (edges.any { it < 1f }) {
            return QrGeometryResult.Invalid("二维码区域过小")
        }

        val angles = ordered.indices.map { index ->
            interiorAngle(
                previous = ordered[(index + ordered.size - 1) % ordered.size],
                current = ordered[index],
                next = ordered[(index + 1) % ordered.size],
            )
        }
        if (angles.any { it < MINIMUM_ANGLE_DEGREES || it > MAXIMUM_ANGLE_DEGREES }) {
            return QrGeometryResult.Invalid("图片角度过大，仅支持旋转和轻度透视")
        }

        if (ratio(edges[0], edges[2]) > MAXIMUM_OPPOSITE_EDGE_RATIO ||
            ratio(edges[1], edges[3]) > MAXIMUM_OPPOSITE_EDGE_RATIO
        ) {
            return QrGeometryResult.Invalid("图片透视过强，二维码相对边比例超出限制")
        }
        return QrGeometryResult.Valid(QrQuadrilateral(ordered, edges))
    }

    fun modulePixelSize(
        quadrilateral: QrQuadrilateral,
        encodedModuleCount: Int,
    ): Float {
        require(encodedModuleCount > 0)
        val totalModules = encodedModuleCount + QUIET_ZONE_MODULES * 2
        return quadrilateral.minimumEdgeLength / totalModules
    }

    private fun rotateToUpperLeft(points: List<QrPoint>): List<QrPoint> {
        val firstIndex = points.indices.minBy { points[it].x + points[it].y }
        return points.indices.map { offset -> points[(firstIndex + offset) % points.size] }
    }

    private fun cross(first: QrPoint, second: QrPoint, third: QrPoint): Float =
        (second.x - first.x) * (third.y - second.y) -
            (second.y - first.y) * (third.x - second.x)

    private fun distance(first: QrPoint, second: QrPoint): Float =
        hypot(second.x - first.x, second.y - first.y)

    private fun interiorAngle(previous: QrPoint, current: QrPoint, next: QrPoint): Float {
        val ax = previous.x - current.x
        val ay = previous.y - current.y
        val bx = next.x - current.x
        val by = next.y - current.y
        val denominator = hypot(ax, ay) * hypot(bx, by)
        if (denominator <= 0f) return 0f
        val cosine = ((ax * bx + ay * by) / denominator).coerceIn(-1f, 1f)
        return (acos(cosine) * 180f / PI).toFloat()
    }

    private fun ratio(first: Float, second: Float): Float =
        maxOf(first, second) / minOf(first, second)
}
