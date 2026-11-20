package com.atelier.manim.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AtelierIcons {
    private fun icon(
        name: String,
        fill: Boolean = false,
        strokeWidth: Float = 1.6f,
        builder: PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = if (fill) SolidColor(Color.White) else null,
            stroke = if (fill) null else SolidColor(Color.White),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = builder
        )
    }.build()

    val Play by lazy {
        icon("Play", fill = true) {
            moveTo(7.5f, 5.2f)
            lineTo(18.8f, 12f)
            lineTo(7.5f, 18.8f)
            close()
        }
    }

    val Pause by lazy {
        icon("Pause", fill = true) {
            moveTo(7f, 5.5f); lineTo(10f, 5.5f); lineTo(10f, 18.5f); lineTo(7f, 18.5f); close()
            moveTo(14f, 5.5f); lineTo(17f, 5.5f); lineTo(17f, 18.5f); lineTo(14f, 18.5f); close()
        }
    }

    val Stop by lazy {
        icon("Stop", fill = true) {
            moveTo(6.5f, 6.5f); lineTo(17.5f, 6.5f); lineTo(17.5f, 17.5f); lineTo(6.5f, 17.5f); close()
        }
    }

    val StepBack by lazy {
        icon("StepBack") {
            moveTo(7f, 6f); lineTo(7f, 18f)
            moveTo(17f, 6.5f); lineTo(10.5f, 12f); lineTo(17f, 17.5f)
        }
    }

    val StepForward by lazy {
        icon("StepForward") {
            moveTo(17f, 6f); lineTo(17f, 18f)
            moveTo(7f, 6.5f); lineTo(13.5f, 12f); lineTo(7f, 17.5f)
        }
    }

    val Loop by lazy {
        icon("Loop") {
            moveTo(17f, 4f); lineTo(20f, 7f); lineTo(17f, 10f)
            moveTo(4f, 11f); lineTo(4f, 9f); lineTo(6f, 7f); lineTo(20f, 7f)
            moveTo(7f, 20f); lineTo(4f, 17f); lineTo(7f, 14f)
            moveTo(20f, 13f); lineTo(20f, 15f); lineTo(18f, 17f); lineTo(4f, 17f)
        }
    }

    val Expand by lazy {
        icon("Expand") {
            moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
            moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
            moveTo(20f, 4f); lineTo(14f, 10f)
            moveTo(4f, 20f); lineTo(10f, 14f)
        }
    }

    val Collapse by lazy {
        icon("Collapse") {
            moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f)
            moveTo(20f, 10f); lineTo(14f, 10f); lineTo(14f, 4f)
            moveTo(14f, 10f); lineTo(20f, 4f)
            moveTo(10f, 14f); lineTo(4f, 20f)
        }
    }

    val Grid by lazy {
        icon("Grid") {
            moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            moveTo(9.3f, 4f); lineTo(9.3f, 20f)
            moveTo(14.7f, 4f); lineTo(14.7f, 20f)
            moveTo(4f, 9.3f); lineTo(20f, 9.3f)
            moveTo(4f, 14.7f); lineTo(20f, 14.7f)
        }
    }

    val Save by lazy {
        icon("Save") {
            moveTo(12f, 4f); lineTo(12f, 15f)
            moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
            moveTo(5f, 19f); lineTo(19f, 19f)
        }
    }

    val Share by lazy {
        icon("Share") {
            moveTo(12f, 15f); lineTo(12f, 4f)
            moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
            moveTo(5f, 14f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 14f)
        }
    }

    val Folder by lazy {
        icon("Folder") {
            moveTo(3.5f, 6.5f); lineTo(9.5f, 6.5f); lineTo(11.5f, 8.5f)
            lineTo(20.5f, 8.5f); lineTo(20.5f, 18.5f); lineTo(3.5f, 18.5f); close()
        }
    }

    val History by lazy {
        icon("History") {
            moveTo(4f, 12f); arcTo(8f, 8f, 0f, true, true, 6.3f, 17.6f)
            moveTo(4f, 7f); lineTo(4f, 12f); lineTo(9f, 12f)
            moveTo(12f, 8f); lineTo(12f, 12.2f); lineTo(15f, 14f)
        }
    }

    val Terminal by lazy {
        icon("Terminal") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(7.5f, 9.5f); lineTo(10.5f, 12f); lineTo(7.5f, 14.5f)
            moveTo(12.5f, 14.5f); lineTo(16.5f, 14.5f)
        }
    }

    val Settings by lazy {
        icon("Settings") {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 17f); lineTo(20f, 17f)
            moveTo(9f, 5f); lineTo(9f, 9f)
            moveTo(15f, 10f); lineTo(15f, 14f)
            moveTo(10f, 15f); lineTo(10f, 19f)
        }
    }

    val Plus by lazy {
        icon("Plus") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
    }

    val Close by lazy {
        icon("Close") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    val Undo by lazy {
        icon("Undo") {
            moveTo(8f, 7f); lineTo(4f, 11f); lineTo(8f, 15f)
            moveTo(4f, 11f); lineTo(15f, 11f)
            arcTo(4f, 4f, 0f, false, true, 15f, 19f); lineTo(11f, 19f)
        }
    }

    val Redo by lazy {
        icon("Redo") {
            moveTo(16f, 7f); lineTo(20f, 11f); lineTo(16f, 15f)
            moveTo(20f, 11f); lineTo(9f, 11f)
            arcTo(4f, 4f, 0f, false, false, 9f, 19f); lineTo(13f, 19f)
        }
    }

    val Search by lazy {
        icon("Search") {
            moveTo(11f, 4.5f); arcTo(6.5f, 6.5f, 0f, true, true, 10.99f, 4.5f)
            moveTo(16f, 16f); lineTo(20f, 20f)
        }
    }

    val Trash by lazy {
        icon("Trash") {
            moveTo(5f, 7f); lineTo(19f, 7f)
            moveTo(9f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7f)
            moveTo(7f, 7f); lineTo(8f, 19.5f); lineTo(16f, 19.5f); lineTo(17f, 7f)
        }
    }

    val Check by lazy {
        icon("Check") {
            moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7.5f)
        }
    }

    val Refresh by lazy {
        icon("Refresh") {
            moveTo(19.5f, 12f); arcTo(7.5f, 7.5f, 0f, true, true, 17.3f, 6.7f)
            moveTo(18f, 3.5f); lineTo(18f, 7.5f); lineTo(14f, 7.5f)
        }
    }

    val StageToggle by lazy {
        icon("StageToggle") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(4f, 12f); lineTo(20f, 12f)
        }
    }

    val Copy by lazy {
        icon("Copy") {
            moveTo(9f, 9f); lineTo(19f, 9f); lineTo(19f, 19f); lineTo(9f, 19f); close()
            moveTo(5f, 15f); lineTo(5f, 5f); lineTo(15f, 5f)
        }
    }

    val Inspect by lazy {
        icon("Inspect") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(8f, 9f); lineTo(16f, 9f)
            moveTo(8f, 12.5f); lineTo(14f, 12.5f)
            moveTo(8f, 16f); lineTo(12f, 16f)
        }
    }

    val Paste by lazy {
        icon("Paste") {
            moveTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f); lineTo(9f, 7f); close()
            moveTo(9f, 5.5f); lineTo(6f, 5.5f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 5.5f); lineTo(15f, 5.5f)
        }
    }

    val Outline by lazy {
        icon("Outline") {
            moveTo(4f, 6f); lineTo(20f, 6f)
            moveTo(8f, 12f); lineTo(20f, 12f)
            moveTo(11f, 18f); lineTo(20f, 18f)
            moveTo(4f, 12f); lineTo(5f, 12f)
            moveTo(4f, 18f); lineTo(8f, 18f)
        }
    }

    val Formula by lazy {
        icon("Formula") {
            moveTo(18f, 5f); lineTo(7f, 5f); lineTo(13.5f, 12f); lineTo(7f, 19f); lineTo(18f, 19f)
        }
    }

    val GitBranch by lazy {
        icon("GitBranch") {
            moveTo(7f, 6f); lineTo(7f, 18f)
            moveTo(17f, 8f); lineTo(17f, 11f); lineTo(7f, 15f)
            moveTo(7f, 4f); arcTo(2f, 2f, 0f, true, true, 6.99f, 4f)
            moveTo(17f, 4f); arcTo(2f, 2f, 0f, true, true, 16.99f, 4f)
            moveTo(7f, 17f); arcTo(2f, 2f, 0f, true, true, 6.99f, 17f)
        }
    }
}
