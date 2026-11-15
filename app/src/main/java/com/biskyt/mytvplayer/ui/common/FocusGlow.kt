package com.biskyt.mytvplayer.ui.common

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The highlight for the card with focus, from MyTVLauncher (the owner, 2026-11-15: larger, with a glowing edge in place of a
 * solid ring): the card grows by a tenth and a glow in [color] (the accent) shows around its edge. Both are one scale of
 * the card's drawing, with the glow's blurred shapes under it, on the GPU: a focus change animates them only, with no
 * composition and no new layout, so rows scroll without stutter. Put it on the card
 * ([shape] is the card's), outside its focus target, so that it sees the card's focus. Put [raiseOnFocus] on the row's
 * item, so that the card draws over the cards next to it.
 */
fun Modifier.focusGlow(shape: Shape, color: Color): Modifier = this then FocusGlowElement(shape, color)

/** Draws the row's item with focus over the others, so that its card grows and glows over theirs (see [focusGlow]). */
fun Modifier.raiseOnFocus(): Modifier = this then RaiseElement

private data class FocusGlowElement(val shape: Shape, val color: Color) : ModifierNodeElement<FocusGlowNode>() {
    override fun create() = FocusGlowNode(shape, color)

    override fun update(node: FocusGlowNode) {
        node.shape = shape
        node.color = color
        // The glow reads them while it draws: draw it again, with the new ones.
        node.invalidateDraw()
    }
}

private class FocusGlowNode(var shape: Shape, var color: Color) :
    Modifier.Node(),
    DrawModifierNode,
    FocusEventModifierNode {
    private var lift = Animatable(0f)
    private var focused = false
    private val soft = Paint()
    private val edge = Paint()
    private var blurDensity = 0f

    /** The card's outline, made again only when its size or shape changes, not on each frame of the animation. */
    private var outline: Outline? = null
    private var outlineSize = Size.Unspecified
    private var outlineShape: Shape? = null

    /**
     * The card and its glow, grown together by one transform of the drawing, so that they always match (in 0.8.1 the
     * card grew in a layer and the glow by its own transform, and on the TV the card did not fill the glow, the owner,
     * 2026-11-15). The glow is the card's shape, blurred outwards only, in two passes: a wide soft one and a narrow
     * bright one at the edge. The blur grows with the transform too.
     */
    override fun ContentDrawScope.draw() {
        val amount = lift.value
        if (amount <= 0f) return drawContent()
        scale(1f + LIFT_SCALE * amount) {
            // The blurs depend on the screen's density only: made again only if it changes.
            if (blurDensity != density) {
                blurDensity = density
                soft.asFrameworkPaint().apply {
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(GLOW_RADIUS.toPx(), BlurMaskFilter.Blur.OUTER)
                }
                edge.asFrameworkPaint().apply {
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(EDGE_RADIUS.toPx(), BlurMaskFilter.Blur.OUTER)
                }
            }
            val argb = color.copy(alpha = amount).toArgb()
            soft.asFrameworkPaint().color = argb
            edge.asFrameworkPaint().color = argb
            if (outline == null || outlineSize != size || outlineShape != shape) {
                outline = shape.createOutline(size, layoutDirection, this)
                outlineSize = size
                outlineShape = shape
            }
            outline?.let { o ->
                drawIntoCanvas {
                    it.drawOutline(o, soft)
                    it.drawOutline(o, edge)
                }
            }
            this@draw.drawContent()
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus == focused) return
        focused = focusState.hasFocus
        val target = if (focused) 1f else 0f
        coroutineScope.launch { lift.animateTo(target, tween(LIFT_MS)) }
    }

    /** A lazy list uses the card again for another item: it starts without the glow (its animation was stopped). */
    override fun onReset() {
        focused = false
        lift = Animatable(0f)
    }
}

private data object RaiseElement : ModifierNodeElement<RaiseNode>() {
    override fun create() = RaiseNode()

    override fun update(node: RaiseNode) = Unit
}

private class RaiseNode :
    Modifier.Node(),
    LayoutModifierNode,
    FocusEventModifierNode {
    private var focused = false

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = if (focused) 1f else 0f) }
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus == focused) return
        focused = focusState.hasFocus
        invalidatePlacement()
    }

    override fun onReset() {
        focused = false
    }
}

/** How much the card with focus grows: a tenth (the owner, 2026-11-15: larger than the 6% before). */
private const val LIFT_SCALE = 0.1f

private const val LIFT_MS = 150

/** How far the soft glow reaches out from the card's edge, and the narrow bright glow at the edge. */
private val GLOW_RADIUS = 12.dp
private val EDGE_RADIUS = 4.dp

/** Room above and below a row of cards for the card with focus to grow and glow into. */
val GLOW_ROOM = 20.dp

// (MyTVPlayer's own, below: MyTVLauncher's rows leave room in their layout instead.)

/**
 * A row or grid of glowing cards (see [focusGlow]) drawn [GLOW_ROOM] beyond its own edges, so that the card with focus
 * grows and glows there and is not cut off where the list ends. Its layout keeps its size: give the list
 * [glowRoomPadding] as its content padding, and its cards stay where they were.
 */
fun Modifier.glowRoom(vertical: Boolean = true): Modifier = layout { measurable, constraints ->
    val x = GLOW_ROOM.roundToPx()
    val y = if (vertical) x else 0
    fun Int.wider(by: Int) = if (this == Constraints.Infinity) this else this + 2 * by
    val placeable = measurable.measure(
        constraints.copy(maxWidth = constraints.maxWidth.wider(x), maxHeight = constraints.maxHeight.wider(y))
    )
    layout(placeable.width - 2 * x, placeable.height - 2 * y) { placeable.place(-x, -y) }
}

/**
 * A list's content padding with [glowRoom] (with [vertical] as there), and [more] (such as room at its end). A list that
 * scrolls up and down has no room drawn above and below it (its cards would scroll over what is there): [more] gives
 * its cards room inside it instead.
 */
fun glowRoomPadding(more: PaddingValues = PaddingValues(0.dp), vertical: Boolean = true) = PaddingValues(
    start = GLOW_ROOM + more.calculateStartPadding(LayoutDirection.Ltr),
    top = (if (vertical) GLOW_ROOM else 0.dp) + more.calculateTopPadding(),
    end = GLOW_ROOM + more.calculateEndPadding(LayoutDirection.Ltr),
    bottom = (if (vertical) GLOW_ROOM else 0.dp) + more.calculateBottomPadding()
)
