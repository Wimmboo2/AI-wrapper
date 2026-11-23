package dev.wimmboo.omni.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-ported vector icons matching every inline SVG in index.html 1:1.
 * Note: Speaker / TTS icon is intentionally omitted per spec.
 */
object OmniIcons {

    private fun strokeIcon(
        name: String,
        strokeWidth: Float = 1.75f,
        block: ImageVector.Builder.(SolidColor, Float) -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        block(SolidColor(Color.White), strokeWidth)
    }.build()

    private fun ImageVector.Builder.strokePath(
        stroke: SolidColor,
        strokeWidth: Float = 1.75f,
        pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) {
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBuilder,
        )
    }

    val Spark: ImageVector by lazy {
        ImageVector.Builder(
            name = "Spark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(12f, 1.5f)
                lineToRelative(1.4f, 5.3f)
                arcToRelative(3f, 3f, 0f, false, false, 2.1f, 2.1f)
                lineTo(21f, 10.3f)
                lineToRelative(-5.5f, 1.4f)
                arcToRelative(3f, 3f, 0f, false, false, -2.1f, 2.1f)
                lineTo(12f, 19.1f)
                lineToRelative(-1.4f, -5.3f)
                arcToRelative(3f, 3f, 0f, false, false, -2.1f, -2.1f)
                lineTo(3f, 10.3f)
                lineToRelative(5.5f, -1.4f)
                arcToRelative(3f, 3f, 0f, false, false, 2.1f, -2.1f)
                lineTo(12f, 1.5f)
                close()
            }
        }.build()
    }

    val Sidebar: ImageVector by lazy {
        strokeIcon("Sidebar") { s, w ->
            strokePath(s, w) {
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(9f, 3f)
                verticalLineTo(21f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("Plus") { s, w ->
            strokePath(s, w) {
                moveTo(12f, 5f)
                verticalLineTo(19f)
                moveTo(5f, 12f)
                horizontalLineTo(19f)
            }
        }
    }

    val Sliders: ImageVector by lazy {
        strokeIcon("Sliders") { s, w ->
            strokePath(s, w) {
                moveTo(4f, 21f); verticalLineTo(14f)
                moveTo(4f, 10f); verticalLineTo(3f)
                moveTo(12f, 21f); verticalLineTo(12f)
                moveTo(12f, 8f); verticalLineTo(3f)
                moveTo(20f, 21f); verticalLineTo(16f)
                moveTo(20f, 12f); verticalLineTo(3f)
                moveTo(1f, 14f); horizontalLineTo(7f)
                moveTo(9f, 8f); horizontalLineTo(15f)
                moveTo(17f, 16f); horizontalLineTo(23f)
            }
        }
    }

    val Incognito: ImageVector by lazy {
        strokeIcon("Incognito") { s, w ->
            strokePath(s, w) {
                moveTo(4f, 9.5f)
                curveToRelative(2.2f, -2.8f, 4.8f, -4f, 8f, -4f)
                reflectiveCurveToRelative(5.8f, 1.2f, 8f, 4f)
                moveTo(3f, 14.5f)
                horizontalLineToRelative(4.2f)
                arcToRelative(2.8f, 2.8f, 0f, false, true, 5.6f, 0f)
                horizontalLineToRelative(0.4f)
                arcToRelative(2.8f, 2.8f, 0f, false, true, 5.6f, 0f)
                horizontalLineTo(23f)
                moveTo(10.4f, 14.5f)
                horizontalLineToRelative(3.2f)
                // left lens circle cx=8, cy=14.5, r=2.4
                moveTo(10.4f, 14.5f)
                arcToRelative(2.4f, 2.4f, 0f, true, true, -4.8f, 0f)
                arcToRelative(2.4f, 2.4f, 0f, true, true, 4.8f, 0f)
                // right lens circle cx=16, cy=14.5, r=2.4
                moveTo(18.4f, 14.5f)
                arcToRelative(2.4f, 2.4f, 0f, true, true, -4.8f, 0f)
                arcToRelative(2.4f, 2.4f, 0f, true, true, 4.8f, 0f)
            }
        }
    }

    val User: ImageVector by lazy {
        strokeIcon("User") { s, w ->
            strokePath(s, w) {
                moveTo(20f, 21f)
                verticalLineToRelative(-2f)
                arcToRelative(4f, 4f, 0f, false, false, -4f, -4f)
                horizontalLineTo(8f)
                arcToRelative(4f, 4f, 0f, false, false, -4f, 4f)
                verticalLineToRelative(2f)
                moveTo(16f, 7f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight", 2f) { s, w ->
            strokePath(s, w) {
                moveTo(9f, 18f)
                lineTo(15f, 12f)
                lineTo(9f, 6f)
            }
        }
    }

    val ChevronLeft: ImageVector by lazy {
        strokeIcon("ChevronLeft", 2f) { s, w ->
            strokePath(s, w) {
                moveTo(15f, 18f)
                lineTo(9f, 12f)
                lineTo(15f, 6f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("ChevronDown", 2f) { s, w ->
            strokePath(s, w) {
                moveTo(6f, 9f)
                lineTo(12f, 15f)
                lineTo(18f, 9f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") { s, w ->
            strokePath(s, w) {
                moveTo(3f, 6f); horizontalLineTo(21f)
                moveTo(19f, 6f); verticalLineToRelative(14f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(7f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(6f)
                moveTo(8f, 6f); verticalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineToRelative(2f)
            }
        }
    }

    val Pin: ImageVector by lazy {
        strokeIcon("Pin") { s, w ->
            strokePath(s, w) {
                moveTo(12f, 17f); verticalLineTo(22f)
                moveTo(9f, 10.76f)
                arcToRelative(2f, 2f, 0f, false, true, -1.11f, 1.79f)
                lineToRelative(-1.78f, 0.9f)
                arcTo(2f, 2f, 0f, false, false, 5f, 15.24f)
                verticalLineTo(16f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
                horizontalLineToRelative(12f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, -1f)
                verticalLineToRelative(-0.76f)
                arcToRelative(2f, 2f, 0f, false, false, -1.11f, -1.79f)
                lineToRelative(-1.78f, -0.9f)
                arcTo(2f, 2f, 0f, false, true, 15f, 10.76f)
                verticalLineTo(6f)
                horizontalLineToRelative(1f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, -4f)
                horizontalLineTo(8f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, 4f)
                horizontalLineToRelative(1f)
                close()
            }
        }
    }

    val Send: ImageVector by lazy {
        strokeIcon("Send", 2.25f) { s, w ->
            strokePath(s, w) {
                moveTo(12f, 19f); verticalLineTo(5f)
                moveTo(5f, 12f); lineTo(12f, 5f); lineTo(19f, 12f)
            }
        }
    }

    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(8f, 6f)
                horizontalLineTo(16f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(16f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(8f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(8f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
        }.build()
    }

    val Copy: ImageVector by lazy {
        strokeIcon("Copy") { s, w ->
            strokePath(s, w) {
                moveTo(11f, 9f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(11f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(11f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(5f, 15f); horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineToRelative(9f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineToRelative(1f)
            }
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check", 2f) { s, w ->
            strokePath(s, w) {
                moveTo(20f, 6f)
                lineTo(9f, 17f)
                lineTo(4f, 12f)
            }
        }
    }

    val Edit: ImageVector by lazy {
        strokeIcon("Edit") { s, w ->
            strokePath(s, w) {
                moveTo(12f, 20f); horizontalLineTo(21f)
                moveTo(16.5f, 3.5f)
                arcToRelative(2.12f, 2.12f, 0f, false, true, 3f, 3f)
                lineTo(7f, 19f)
                lineToRelative(-4f, 1f)
                lineToRelative(1f, -4f)
                lineTo(16.5f, 3.5f)
                close()
            }
        }
    }

    val Eye: ImageVector by lazy {
        strokeIcon("Eye") { s, w ->
            strokePath(s, w) {
                moveTo(1f, 12f)
                reflectiveCurveToRelative(4f, -8f, 11f, -8f)
                reflectiveCurveToRelative(11f, 8f, 11f, 8f)
                reflectiveCurveToRelative(-4f, 8f, -11f, 8f)
                reflectiveCurveToRelative(-11f, -8f, -11f, -8f)
                close()
                moveTo(15f, 12f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            }
        }
    }

    val EyeOff: ImageVector by lazy {
        strokeIcon("EyeOff") { s, w ->
            strokePath(s, w) {
                moveTo(17.94f, 17.94f)
                arcTo(10.07f, 10.07f, 0f, false, true, 12f, 20f)
                curveToRelative(-7f, 0f, -11f, -8f, -11f, -8f)
                arcToRelative(18.45f, 18.45f, 0f, false, true, 5.06f, -5.94f)
                moveTo(9.9f, 4.24f)
                arcTo(9.12f, 9.12f, 0f, false, true, 12f, 4f)
                curveToRelative(7f, 0f, 11f, 8f, 11f, 8f)
                arcToRelative(18.5f, 18.5f, 0f, false, true, -2.16f, 3.19f)
                moveTo(1f, 1f); lineTo(23f, 23f)
            }
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close", 2f) { s, w ->
            strokePath(s, w) {
                moveTo(18f, 6f); lineTo(6f, 18f)
                moveTo(6f, 6f); lineTo(18f, 18f)
            }
        }
    }

    val Retry: ImageVector by lazy {
        strokeIcon("Retry") { s, w ->
            strokePath(s, w) {
                moveTo(23f, 4f); verticalLineTo(10f); horizontalLineTo(17f)
                moveTo(20.49f, 15f)
                arcToRelative(9f, 9f, 0f, true, true, -2.12f, -9.36f)
                lineTo(23f, 10f)
            }
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("Search") { s, w ->
            strokePath(s, w) {
                moveTo(19f, 11f)
                arcToRelative(8f, 8f, 0f, true, true, -16f, 0f)
                arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
                moveTo(21f, 21f); lineTo(16.65f, 16.65f)
            }
        }
    }

    val Mic: ImageVector by lazy {
        strokeIcon("Mic") { s, w ->
            strokePath(s, w) {
                moveTo(12f, 1f)
                arcToRelative(3f, 3f, 0f, false, false, -3f, 3f)
                verticalLineToRelative(8f)
                arcToRelative(3f, 3f, 0f, false, false, 6f, 0f)
                verticalLineTo(4f)
                arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
                close()
                moveTo(19f, 10f); verticalLineToRelative(2f)
                arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
                verticalLineToRelative(-2f)
                moveTo(12f, 19f); verticalLineTo(23f)
                moveTo(8f, 23f); horizontalLineTo(16f)
            }
        }
    }

    val Download: ImageVector by lazy {
        strokeIcon("Download") { s, w ->
            strokePath(s, w) {
                moveTo(21f, 15f); verticalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineToRelative(-4f)
                moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                moveTo(12f, 15f); verticalLineTo(3f)
            }
        }
    }

    val Upload: ImageVector by lazy {
        strokeIcon("Upload") { s, w ->
            strokePath(s, w) {
                moveTo(21f, 15f); verticalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineToRelative(-4f)
                moveTo(17f, 8f); lineTo(12f, 3f); lineTo(7f, 8f)
                moveTo(12f, 3f); verticalLineTo(15f)
            }
        }
    }

    val Play: ImageVector by lazy {
        strokeIcon("Play") { s, w ->
            strokePath(s, w) {
                moveTo(5f, 3f); lineTo(19f, 12f); lineTo(5f, 21f); close()
            }
        }
    }

    val Code: ImageVector by lazy {
        strokeIcon("Code") { s, w ->
            strokePath(s, w) {
                moveTo(16f, 18f); lineTo(22f, 12f); lineTo(16f, 6f)
                moveTo(8f, 6f); lineTo(2f, 12f); lineTo(8f, 18f)
            }
        }
    }

    val Terminal: ImageVector by lazy {
        strokeIcon("Terminal") { s, w ->
            strokePath(s, w) {
                moveTo(4f, 17f); lineTo(10f, 11f); lineTo(4f, 5f)
                moveTo(12f, 19f); horizontalLineTo(20f)
            }
        }
    }

    val Wrap: ImageVector by lazy {
        strokeIcon("Wrap") { s, w ->
            strokePath(s, w) {
                moveTo(3f, 6f); horizontalLineTo(21f)
                moveTo(3f, 12f); horizontalLineTo(18f)
                arcToRelative(3f, 3f, 0f, true, true, 0f, 6f)
                horizontalLineToRelative(-4f)
                moveTo(16f, 16f); lineTo(14f, 18f); lineTo(16f, 20f)
                moveTo(3f, 18f); horizontalLineTo(10f)
            }
        }
    }

    val ExternalLink: ImageVector by lazy {
        strokeIcon("ExternalLink", 2f) { s, w ->
            strokePath(s, w) {
                moveTo(18f, 13f); verticalLineToRelative(6f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(8f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineToRelative(6f)
                moveTo(15f, 3f); horizontalLineTo(21f); verticalLineTo(9f)
                moveTo(10f, 14f); lineTo(21f, 3f)
            }
        }
    }

    val Link: ImageVector by lazy {
        strokeIcon("Link") { s, w ->
            strokePath(s, w) {
                moveTo(10f, 13f)
                arcToRelative(5f, 5f, 0f, false, false, 7.54f, 0.54f)
                lineToRelative(3f, -3f)
                arcToRelative(5f, 5f, 0f, false, false, -7.07f, -7.07f)
                lineToRelative(-1.72f, 1.71f)
                moveTo(14f, 11f)
                arcToRelative(5f, 5f, 0f, false, false, -7.54f, -0.54f)
                lineToRelative(-3f, 3f)
                arcToRelative(5f, 5f, 0f, false, false, 7.07f, 7.07f)
                lineToRelative(1.71f, -1.71f)
            }
        }
    }

    val Longer: ImageVector by lazy {
        strokeIcon("Longer") { s, w ->
            strokePath(s, w) {
                moveTo(12f, 5f); verticalLineTo(19f)
                moveTo(6f, 13f); lineTo(12f, 19f); lineTo(18f, 13f)
            }
        }
    }

    val Shorter: ImageVector by lazy {
        strokeIcon("Shorter") { s, w ->
            strokePath(s, w) {
                moveTo(12f, 19f); verticalLineTo(5f)
                moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f)
            }
        }
    }

    val Learn: ImageVector by lazy {
        strokeIcon("Learn") { s, w ->
            strokePath(s, w) {
                moveTo(22f, 10f); verticalLineToRelative(6f)
                moveTo(2f, 10f); lineToRelative(10f, -5f); lineToRelative(10f, 5f); lineToRelative(-10f, 5f); close()
                moveTo(6f, 12f); verticalLineToRelative(5f)
                curveToRelative(3f, 3f, 9f, 3f, 12f, 0f)
                verticalLineToRelative(-5f)
            }
        }
    }

    val Coffee: ImageVector by lazy {
        strokeIcon("Coffee") { s, w ->
            strokePath(s, w) {
                moveTo(18f, 8f); horizontalLineToRelative(1f)
                arcToRelative(4f, 4f, 0f, false, true, 0f, 8f)
                horizontalLineToRelative(-1f)
                moveTo(2f, 8f); horizontalLineToRelative(16f); verticalLineToRelative(9f)
                arcToRelative(4f, 4f, 0f, false, true, -4f, 4f)
                horizontalLineTo(6f)
                arcToRelative(4f, 4f, 0f, false, true, -4f, -4f)
                verticalLineTo(8f); close()
                moveTo(6f, 1f); verticalLineTo(4f)
                moveTo(10f, 1f); verticalLineTo(4f)
                moveTo(14f, 1f); verticalLineTo(4f)
            }
        }
    }

    val HelpCircle: ImageVector by lazy {
        strokeIcon("HelpCircle") { s, w ->
            strokePath(s, w) {
                moveTo(22f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                moveTo(9.09f, 9f)
                arcToRelative(3f, 3f, 0f, false, true, 5.83f, 1f)
                curveToRelative(0f, 2f, -3f, 3f, -3f, 3f)
                moveTo(12f, 17f); horizontalLineToRelative(0.01f)
            }
        }
    }
}
