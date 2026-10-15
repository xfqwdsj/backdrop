package top.ltfan.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import top.ltfan.backdrop.interaction.InteractionLightEntry

/** A same-draw reference to the already processed backdrop layer owned by its draw node. */
internal class ProcessedBackdropFrame {
    var requested: Boolean = false
        private set

    var layer: GraphicsLayer? = null
        private set

    var layerOffset: Offset = Offset.Zero
        private set

    var sourceBounds: Rect = Rect.Zero
        private set

    var interactionLights: List<InteractionLightEntry> = emptyList()
        private set

    var receiverWindow: Any? = null
        private set

    var receiverCoordinates: LayoutCoordinates? = null
        private set

    fun begin(requested: Boolean) {
        this.requested = requested
        layer = null
        layerOffset = Offset.Zero
        sourceBounds = Rect.Zero
        interactionLights = emptyList()
        receiverWindow = null
        receiverCoordinates = null
    }

    fun publish(layer: GraphicsLayer, offset: Offset, size: Size) {
        if (!requested) return
        this.layer = layer
        layerOffset = offset
        sourceBounds = Rect(0f, 0f, size.width, size.height)
    }

    fun invalidateSource() {
        layer = null
        layerOffset = Offset.Zero
        sourceBounds = Rect.Zero
    }

    fun publishInteraction(
        lights: List<InteractionLightEntry>,
        window: Any?,
        coordinates: LayoutCoordinates?,
    ) {
        interactionLights = lights
        receiverWindow = window
        receiverCoordinates = coordinates
    }

    fun clear() {
        begin(false)
    }
}
