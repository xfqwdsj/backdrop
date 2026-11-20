package top.ltfan.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import top.ltfan.backdrop.internal.recordLayer

public fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this then LayerBackdropElement(backdrop)

private class LayerBackdropElement(val backdrop: LayerBackdrop) :
    ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop.sourceBounds = null
            node.backdrop.publishInvalidation()
            node.backdrop = backdrop
        }
        node.invalidateContent()
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false

        return true
    }

    override fun hashCode(): Int {
        return backdrop.hashCode()
    }
}

private class LayerBackdropNode(var backdrop: LayerBackdrop) :
    DrawModifierNode, GlobalPositionAwareModifierNode, DelegatingNode() {
    private var contentDirty = true
    private val contentReads = delegate(ContentReads())

    fun invalidateContent() {
        contentDirty = true
    }

    private inner class ContentReads : Modifier.Node(), ObserverModifierNode {
        override fun onObservedReadsChanged() {
            contentDirty = true
            this@LayerBackdropNode.invalidateDraw()
        }

        fun observe(block: () -> Unit) {
            observeReads(block)
        }
    }

    override fun ContentDrawScope.draw() {
        if (!contentDirty) {
            drawContent()
            return
        }
        contentDirty = false
        contentReads.observe {
            drawContent()
            recordLayer(backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
        }
        // Keep observable publication outside recording observation so it cannot dirty this record.
        val recorded = backdrop.graphicsLayer.size
        val bounds = Rect(0f, 0f, recorded.width.toFloat(), recorded.height.toFloat())
        if (backdrop.sourceBounds != bounds) backdrop.sourceBounds = bounds
        backdrop.publishRecording()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
            // The coordinate object is stable while its mapped position and transform can change.
            backdrop.publishInvalidation()
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
        backdrop.sourceBounds = null
        backdrop.publishInvalidation()
    }
}
