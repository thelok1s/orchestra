package io.github.thelok1s.orchestra.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import android.graphics.Path as AndroidPath

/** M3 Expressive spring curve — overshoots and settles (design token --md-easing-spring). */
internal val SpringEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * A [RoundedPolygon] as a Compose [Shape]. MaterialShapes polygons are normalized to the unit
 * square, so the outline just scales the path to the requested size. (material3's own
 * `toShape()` is @Composable in 1.5.0-alpha20, which a plain shape pool can't call.)
 */
private class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath().asComposePath()
        val matrix = Matrix()
        matrix.scale(size.width, size.height, 1f)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val androidPath = AndroidPath()
        morph.toPath(progress, androidPath)
        val path = androidPath.asComposePath()
        val matrix = Matrix()
        matrix.scale(size.width, size.height, 1f)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun RoundedPolygon.asShape(): Shape = PolygonShape(this)

/** Shape pool for per-device icon chips (mirrors the design kit's MaterialShape library). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ShapePool: List<Shape> by lazy {
    listOf(
        MaterialShapes.Square, MaterialShapes.Circle,
        MaterialShapes.Slanted, MaterialShapes.Gem,
        MaterialShapes.Pentagon, MaterialShapes.Sunny,
        MaterialShapes.Cookie7Sided, MaterialShapes.Cookie4Sided,
        MaterialShapes.Cookie6Sided, MaterialShapes.Clover4Leaf,
        MaterialShapes.Clover8Leaf
    ).map { it.asShape() }
}

/** Deterministic shape from a seed string (stable across recompositions and launches). */
internal fun shapeForSeed(seed: String): Shape {
    var h = 0
    for (ch in seed) h = h * 31 + ch.code
    return ShapePool[((h % ShapePool.size) + ShapePool.size) % ShapePool.size]
}

/** An icon centered on a filled Material shape — replaces the old CircleShape chips. */
@Composable
internal fun ShapeChip(
    shape: Shape,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier.size(size).clip(shape).background(color),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Staggered spring entrance (translate-up + scale + fade), delay = index * 70ms. */
@Composable
internal fun Rise(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(260, delayMillis = index * 70)) +
            slideInVertically(tween(520, delayMillis = index * 70, easing = SpringEasing)) { it / 5 } +
            scaleIn(tween(520, delayMillis = index * 70, easing = SpringEasing), initialScale = 0.96f),
    ) { content() }
}
