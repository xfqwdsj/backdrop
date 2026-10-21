package top.ltfan.backdrop

import androidx.compose.ui.layout.LayoutCoordinates
import top.ltfan.backdrop.interaction.InteractionLightEntry

/** A same-draw reference to the already processed backdrop image owned by its draw node. */
internal class ProcessedBackdropFrame {
    var requested: Boolean = false
        private set

    var image: BackdropPrimitiveImage? = null
        private set

    var interactionLights: List<InteractionLightEntry> = emptyList()
        private set

    var receiverWindow: Any? = null
        private set

    var receiverCoordinates: LayoutCoordinates? = null
        private set

    fun begin(requested: Boolean) {
        this.requested = requested
        image = null
        interactionLights = emptyList()
        receiverWindow = null
        receiverCoordinates = null
    }

    fun publish(image: BackdropPrimitiveImage) {
        if (requested) this.image = image
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
