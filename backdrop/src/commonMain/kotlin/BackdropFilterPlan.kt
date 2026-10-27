package top.ltfan.backdrop

import androidx.compose.ui.graphics.RenderEffect
import top.ltfan.backdrop.internal.chain

/** Reuses immutable native composition while both child effect identities remain unchanged. */
internal class BackdropFilterComposition {
    private var input: RenderEffect? = null
    private var effect: RenderEffect? = null
    private var composed: RenderEffect? = null

    fun compose(input: RenderEffect, effect: RenderEffect): RenderEffect {
        if (this.input === input && this.effect === effect) return checkNotNull(composed)
        return checkNotNull(input.chain(effect)).also {
            this.input = input
            this.effect = effect
            composed = it
        }
    }
}

/** Updates recording passes while retaining compatible native-filter compositions. */
internal class BackdropFilterPlan {
    val passes = mutableListOf<BackdropEffectScopeImpl.Pass>()
    private var mergedPass: BackdropEffectScopeImpl.Pass? = null
    private var composition: BackdropFilterComposition? = null

    fun update(previous: BackdropFilterPlan?, own: BackdropEffectScopeImpl) {
        passes.clear()
        if (previous == null || own.requiresInputImage) {
            mergedPass = null
            composition = null
            if (previous != null) append(previous.passes, 0)
            append(own.passes, 0)
            return
        }
        append(previous.passes, 0)
        val last = passes.removeAt(passes.lastIndex)
        val first = own.passes.first()
        val retained = mergedPass
        val chain = composition ?: BackdropFilterComposition().also { composition = it }
        val combined = chain.compose(last.effect, first.effect)
        val merged = retained ?: BackdropEffectScopeImpl.Pass(combined, last.inputBounds)
        merged.effect = combined
        merged.inputBounds = last.inputBounds
        mergedPass = merged
        passes += merged
        append(own.passes, 1)
    }

    private fun append(source: List<BackdropEffectScopeImpl.Pass>, start: Int) {
        for (index in start until source.size) passes += source[index]
    }
}
