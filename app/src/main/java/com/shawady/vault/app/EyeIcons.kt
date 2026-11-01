package com.shawady.vault.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Show / hide password icons (kept as vectors so no icon library is needed). Easy to swap later. */
object EyeIcons {
    private const val EYE =
        "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
            "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
            "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"

    val Eye: ImageVector by lazy {
        ImageVector.Builder("Eye", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(EYE),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
            .build()
    }

    val EyeOff: ImageVector by lazy {
        ImageVector.Builder("EyeOff", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(EYE),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
            .addPath(
                pathData = addPathNodes("M3.5,3.5L20.5,20.5"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
            )
            .build()
    }
}
