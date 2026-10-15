package top.ltfan.backdrop.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Registers this node as a source of window-local interaction light. Physical pointer input owns
 * pointer-light lifetimes regardless of [interactionSource]. A supplied [interactionSource] also
 * provides synthetic press and focus interactions; a press matching one active pointer reuses its
 * light. A raw source observes focus for keyboard input without lighting on focus alone. Place this
 * modifier before the focusable modifier to observe focus and keyboard input. Interior light is
 * added with native Plus blending without allocating an extra layer.
 */
public fun Modifier.interactionLightSource(
    interactionSource: InteractionSource? = null,
    light: (() -> InteractionLight)? = null,
): Modifier = then(InteractionLightSourceElement(interactionSource, light))

private class InteractionLightSourceElement(
    val interactionSource: InteractionSource?,
    val light: (() -> InteractionLight)?,
) : ModifierNodeElement<InteractionLightSourceNode>() {
    override fun create(): InteractionLightSourceNode =
        InteractionLightSourceNode(interactionSource, light)

    override fun update(node: InteractionLightSourceNode) {
        node.update(interactionSource, light)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "interactionLightSource"
        properties["interactionSource"] = interactionSource
        properties["light"] = light
    }

    override fun equals(other: Any?): Boolean =
        other is InteractionLightSourceElement &&
            interactionSource === other.interactionSource &&
            light === other.light

    override fun hashCode(): Int = 31 * interactionSource.hashCode() + light.hashCode()
}

private class InteractionLightSourceNode(
    private var interactionSource: InteractionSource?,
    private var light: (() -> InteractionLight)?,
) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    GlobalPositionAwareModifierNode,
    PointerInputModifierNode,
    KeyInputModifierNode,
    FocusEventModifierNode {

    private var registry: InteractionRegistry? = null
    private var window: WindowInfo? = null
    private var coordinates: LayoutCoordinates? = null
    private var config: InteractionLight = InteractionLight.Default
    private var sourceJob: Job? = null
    private var focused = false
    private var pointerDrivenFocus = false
    private val active = mutableMapOf<Any, InteractionLightEntry>()
    private val localPositions = mutableMapOf<Any, Offset>()
    private val windowAnchoredTokens = mutableSetOf<Any>()
    private val pointerTokens = mutableMapOf<PointerId, Any>()
    private val releaseJobs = mutableMapOf<Any, Job>()
    private val pointerDowns = mutableMapOf<PointerId, Offset>()
    private val pointerStartPositions = mutableMapOf<PointerId, Offset>()
    private val trackedPressPointers = mutableMapOf<Any, PointerId>()
    private val pressTokens = mutableMapOf<PressInteraction.Press, Any>()
    private val pointerOwnedPresses = mutableMapOf<PressInteraction.Press, PointerId>()
    private val externalFocusTokens = mutableMapOf<FocusInteraction.Focus, Any>()
    private val keyboardTokens = mutableMapOf<Key, Any>()

    override fun onAttach() {
        observeConfigAndBindings()
        restartCollection()
    }

    fun update(interactionSource: InteractionSource?, light: (() -> InteractionLight)?) {
        if (this.interactionSource !== interactionSource) {
            clearSources()
            sourceJob?.cancel()
            this.interactionSource = interactionSource
            restartCollection()
        }
        if (this.light !== light) {
            this.light = light
            observeConfigAndBindings()
        }
    }

    override fun onObservedReadsChanged() {
        observeConfigAndBindings()
    }

    private fun observeConfigAndBindings() {
        var nextRegistry: InteractionRegistry? = null
        var nextWindow: WindowInfo? = null
        var nextConfig: InteractionLight = InteractionLight.Default
        observeReads {
            nextRegistry = currentValueOf(LocalInteractionRegistry)
            nextWindow = currentValueOf(LocalWindowInfo)
            nextConfig = (light ?: { InteractionLight.Default })()
        }
        val scopeChanged = registry !== nextRegistry || window !== nextWindow
        if (scopeChanged) {
            clearSources()
            registry = nextRegistry
            window = nextWindow
        }
        config = nextConfig
        applyConfig()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        window = currentValueOf(LocalWindowInfo)
        updatePositions()
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (pass != PointerEventPass.Initial) return
        for (change in pointerEvent.changes) {
            pointerDrivenFocus = true
            endFocusSources()
            when {
                change.changedToDownIgnoreConsumed() -> {
                    pointerDowns[change.id] = change.position
                    pointerStartPositions[change.id] = change.position
                    val token = window?.let { PhysicalPointerToken(it, change.id) } ?: Any()
                    if (putSource(token, change.position, windowAnchored = true)) {
                        pointerTokens[change.id] = token
                    }
                }
                change.pressed -> {
                    pointerDowns[change.id] = change.position
                    pointerTokens[change.id]?.let { token ->
                        updateWindowAnchoredEntry(token, change.position)
                    }
                    updateTrackedPointerEntries(change.id, change.position)
                }
                change.changedToUpIgnoreConsumed() -> {
                    pointerTokens[change.id]?.let { token ->
                        updateWindowAnchoredEntry(token, change.position)
                    }
                    pointerDowns.remove(change.id)
                    pointerStartPositions.remove(change.id)
                    pointerTokens.remove(change.id)?.let(::releaseSource)
                    updateTrackedPointerEntries(change.id, change.position, remove = true)
                }
            }
        }
    }

    override fun onCancelPointerInput() {
        pointerTokens.values.toList().forEach(::releaseSource)
        pointerDowns.clear()
        pointerStartPositions.clear()
        pointerTokens.clear()
        pointerOwnedPresses.keys.forEach(pressTokens::remove)
        pointerOwnedPresses.clear()
        trackedPressPointers.clear()
    }

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.hasFocus
        if (!focused) {
            keyboardTokens.values.toList().forEach(::releaseSource)
            keyboardTokens.clear()
        }
    }

    override fun onDensityChange() {
        applyConfig()
    }

    @Suppress("SameReturnValue")
    override fun onKeyEvent(event: KeyEvent): Boolean {
        return false
    }

    @Suppress("SameReturnValue")
    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown) pointerDrivenFocus = false
        if (interactionSource != null) return false
        val key = event.key
        if (key != Key.Enter && key != Key.Spacebar && key != Key.DirectionCenter) return false
        if (event.type == KeyEventType.KeyUp) {
            keyboardTokens.remove(key)?.let(::releaseSource)
            return false
        }
        if (focused && event.type == KeyEventType.KeyDown && key !in keyboardTokens) {
            val token = Any()
            if (putSource(token, center(), centered = true)) keyboardTokens[key] = token
        }
        return false
    }

    private fun restartCollection() {
        sourceJob?.cancel()
        sourceJob = null
        val source = interactionSource ?: return
        sourceJob = coroutineScope.launch {
            source.interactions.collect(::onInteraction)
        }
    }

    private fun onInteraction(interaction: Interaction) {
        when (interaction) {
            is PressInteraction.Press -> {
                val matching =
                    pointerStartPositions.entries.filter { it.value == interaction.pressPosition }
                val matchingPointer = matching.singleOrNull()?.key
                val centered = matching.size != 1 && !interaction.pressPosition.isFinitePosition()
                val position =
                    when {
                        matchingPointer != null ->
                            pointerDowns[matchingPointer] ?: matching.single().value
                        interaction.pressPosition.isFinitePosition() -> interaction.pressPosition
                        else -> center()
                    }
                val pointerToken = matchingPointer?.let(pointerTokens::get)
                if (pointerToken != null) {
                    pressTokens[interaction] = pointerToken
                    pointerOwnedPresses[interaction] = matchingPointer
                } else {
                    val token = Any()
                    if (!putSource(token, position, centered = centered)) return
                    pressTokens[interaction] = token
                    if (matchingPointer != null) trackedPressPointers[token] = matchingPointer
                }
            }
            is PressInteraction.Release ->
                pressTokens.remove(interaction.press)?.let {
                    if (pointerOwnedPresses.remove(interaction.press) == null) {
                        trackedPressPointers.remove(it)
                        releaseSource(it)
                    }
                }
            is PressInteraction.Cancel ->
                pressTokens.remove(interaction.press)?.let {
                    if (pointerOwnedPresses.remove(interaction.press) == null) {
                        trackedPressPointers.remove(it)
                        releaseSource(it)
                    }
                }
            is FocusInteraction.Focus -> {
                if (
                    interactionSource != null &&
                        !pointerDrivenFocus &&
                        interaction !in externalFocusTokens
                ) {
                    val token = Any()
                    if (putSource(token, center(), centered = true))
                        externalFocusTokens[interaction] = token
                }
            }
            is FocusInteraction.Unfocus ->
                externalFocusTokens.remove(interaction.focus)?.let(::releaseSource)
        }
    }

    private fun endFocusSources() {
        if (externalFocusTokens.isEmpty()) return
        externalFocusTokens.values.forEach(::releaseSource)
        externalFocusTokens.clear()
    }

    private fun center(): Offset {
        val size = coordinates?.size?.toSize() ?: return Offset.Zero
        return Offset(size.width / 2f, size.height / 2f)
    }

    private fun putSource(
        token: Any,
        local: Offset,
        centered: Boolean = false,
        windowAnchored: Boolean = false,
    ): Boolean {
        if (config === InteractionLight.None) return false
        val coords = coordinates ?: return false
        val currentRegistry = registry ?: return false
        val currentWindow = window ?: return false
        val ownerPosition = if (centered) center() else local
        val position = coords.localToWindow(ownerPosition)
        val light = config as? InteractionLight.Config ?: return false
        val entry =
            InteractionLightEntry(
                token,
                currentWindow,
                position.x,
                position.y,
                with(requireDensity()) { light.radius.toPx() },
                light.color,
                light.falloff,
                1f,
            )
        currentRegistry.lights.removeAll { it.token == token }
        releaseJobs.remove(token)?.cancel()
        currentRegistry.lights.add(entry)
        active[token] = entry
        localPositions[token] = if (centered) Offset.Unspecified else local
        if (windowAnchored) windowAnchoredTokens.add(token)
        return true
    }

    private fun updateWindowAnchoredEntry(token: Any, local: Offset) {
        val coords = coordinates ?: return
        val currentRegistry = registry ?: return
        val entry = active[token] ?: return
        val position = coords.localToWindow(local)
        entry.x = position.x
        entry.y = position.y
        if (currentRegistry.lights.none { it.token == token && it === entry }) {
            currentRegistry.lights.removeAll { it.token == token }
            currentRegistry.lights.add(entry)
            releaseJobs.remove(token)?.cancel()
        }
    }

    private fun updateEntry(entry: InteractionLightEntry, local: Offset) {
        val coords = coordinates ?: return
        val position = coords.localToWindow(local)
        entry.x = position.x
        entry.y = position.y
    }

    private fun updatePositions() {
        active.forEach { (token, entry) ->
            if (token in windowAnchoredTokens) return@forEach
            val local = localPositions[token]?.takeIf { it.isFinitePosition() } ?: center()
            updateEntry(entry, local)
        }
    }

    private fun updateTrackedPointerEntries(
        pointerId: PointerId,
        position: Offset,
        remove: Boolean = false,
    ) {
        val iterator = trackedPressPointers.entries.iterator()
        while (iterator.hasNext()) {
            val (token, trackedPointerId) = iterator.next()
            if (trackedPointerId != pointerId) continue
            localPositions[token] = position
            active[token]?.let { updateEntry(it, position) }
            if (remove) iterator.remove()
        }
    }

    private fun applyConfig() {
        if (config === InteractionLight.None) {
            clearSources()
            return
        }
        val light = config as? InteractionLight.Config ?: return
        active.values.forEach {
            it.radiusPx = with(requireDensity()) { light.radius.toPx() }
            it.updateColor(light.color)
            it.falloff = light.falloff
        }
    }

    private fun releaseSource(token: Any) {
        val entry = active[token] ?: return
        val duration = (config as? InteractionLight.Config)?.releaseDuration ?: Duration.ZERO
        if (!duration.isFinite() || duration <= Duration.ZERO) {
            removeSource(token)
            return
        }
        releaseJobs.remove(token)?.cancel()
        releaseJobs[token] = coroutineScope.launch {
            val animation = Animatable(entry.intensity)
            animation.animateTo(
                0f,
                tween(duration.inWholeMilliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            ) {
                entry.intensity = value
            }
            removeSource(token)
        }
    }

    private fun removeSource(token: Any) {
        releaseJobs.remove(token)
        active.remove(token)?.let { entry -> registry?.lights?.removeAll { it === entry } }
        localPositions.remove(token)
        windowAnchoredTokens.remove(token)
    }

    private fun clearSources() {
        releaseJobs.values.forEach(Job::cancel)
        releaseJobs.clear()
        active.values.toList().forEach { registry?.lights?.remove(it) }
        active.clear()
        localPositions.clear()
        windowAnchoredTokens.clear()
        pointerTokens.clear()
        trackedPressPointers.clear()
        pointerStartPositions.clear()
        pointerDowns.clear()
        pointerOwnedPresses.clear()
        pressTokens.clear()
        externalFocusTokens.clear()
        keyboardTokens.clear()
    }

    override fun onDetach() {
        sourceJob?.cancel()
        sourceJob = null
        clearSources()
        focused = false
        pointerDrivenFocus = false
        coordinates = null
        registry = null
        window = null
    }

    override fun onReset() {
        clearSources()
        focused = false
        pointerDrivenFocus = false
    }
}

private fun Offset.isFinitePosition(): Boolean = x.isFinite() && y.isFinite()

private class PhysicalPointerToken(
    private val window: WindowInfo,
    private val pointerId: PointerId,
) {
    override fun equals(other: Any?): Boolean =
        other is PhysicalPointerToken && window === other.window && pointerId == other.pointerId

    override fun hashCode(): Int = pointerId.hashCode()
}
