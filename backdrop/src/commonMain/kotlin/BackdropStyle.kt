package top.ltfan.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

/** Restricts implicit receiver lookup to the innermost style configuration. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
public annotation class BackdropStyleMarker

/**
 * Delayed appearance producers. Later assignments replace earlier ones. Null clears an explicit
 * override and restores use-position inheritance; None and neutral values disable processing.
 */
@BackdropStyleMarker
@BackdropRenderDsl
public interface BackdropStyleDsl {
    public var colorControls: (() -> BackdropColorControls)?
    public var blur: (() -> BackdropBlur)?
    public var vibrancy: (() -> Float)?
    public var lens: (() -> BackdropLens)?
    public var fill: (() -> BackdropFill)?
    public var highlight: (() -> Highlight)?
    public var shadow: (() -> Shadow)?
    public var innerShadow: (() -> InnerShadow)?
    public var interactionLighting: (() -> InteractionLighting)?
    public var insets: (() -> BackdropInsets)?
}

/**
 * Immutable appearance overrides for a fixed-order styled surface. Construction and derivation only
 * copy producer references. Unspecified fields resolve at the receiving position.
 */
@Immutable
public class BackdropStyle
private constructor(
    public val colorControls: (() -> BackdropColorControls)?,
    public val blur: (() -> BackdropBlur)?,
    public val vibrancy: (() -> Float)?,
    public val lens: (() -> BackdropLens)?,
    public val fill: (() -> BackdropFill)?,
    public val highlight: (() -> Highlight)?,
    public val shadow: (() -> Shadow)?,
    public val innerShadow: (() -> InnerShadow)?,
    public val interactionLighting: (() -> InteractionLighting)?,
    public val insets: (() -> BackdropInsets)?,
) {
    /** Derives an independent value; null clears an override rather than retaining a base link. */
    public operator fun invoke(block: BackdropStyleDsl.() -> Unit): BackdropStyle =
        Builder(this).apply(block).build(this)

    internal fun over(parent: BackdropStyle): BackdropStyle {
        if (this === parent) return this
        if (this === Empty) return parent
        if (
            colorControls != null &&
                blur != null &&
                vibrancy != null &&
                lens != null &&
                fill != null &&
                highlight != null &&
                shadow != null &&
                innerShadow != null &&
                interactionLighting != null &&
                insets != null
        )
            return this
        return BackdropStyle(
                colorControls ?: parent.colorControls,
                blur ?: parent.blur,
                vibrancy ?: parent.vibrancy,
                lens ?: parent.lens,
                fill ?: parent.fill,
                highlight ?: parent.highlight,
                shadow ?: parent.shadow,
                innerShadow ?: parent.innerShadow,
                interactionLighting ?: parent.interactionLighting,
                insets ?: parent.insets,
            )
            .let { if (it == this) this else if (it == parent) parent else it }
    }

    override fun equals(other: Any?): Boolean =
        other is BackdropStyle &&
            colorControls == other.colorControls &&
            blur == other.blur &&
            vibrancy == other.vibrancy &&
            lens == other.lens &&
            fill == other.fill &&
            highlight == other.highlight &&
            shadow == other.shadow &&
            innerShadow == other.innerShadow &&
            interactionLighting == other.interactionLighting &&
            insets == other.insets

    override fun hashCode(): Int {
        var result = 1
        result = 31 * result + colorControls.hashCode()
        result = 31 * result + blur.hashCode()
        result = 31 * result + vibrancy.hashCode()
        result = 31 * result + lens.hashCode()
        result = 31 * result + fill.hashCode()
        result = 31 * result + highlight.hashCode()
        result = 31 * result + shadow.hashCode()
        result = 31 * result + innerShadow.hashCode()
        result = 31 * result + interactionLighting.hashCode()
        result = 31 * result + insets.hashCode()
        return result
    }

    public companion object {
        private val Empty =
            BackdropStyle(null, null, null, null, null, null, null, null, null, null)

        /** Creates overrides without reading theme defaults or executing producers. */
        public operator fun invoke(block: BackdropStyleDsl.() -> Unit): BackdropStyle = Empty(block)

        /** Complete defaults, suitable for explicitly resetting an inherited appearance. */
        @Stable
        public val Default: BackdropStyle =
            BackdropStyle(
                colorControls = { BackdropColorControls.None },
                blur = { BackdropBlur.None },
                vibrancy = { 1f },
                lens = { BackdropLens.None },
                fill = { BackdropFill.None },
                highlight = { Highlight.Default },
                shadow = { Shadow.Default },
                innerShadow = { InnerShadow.None },
                interactionLighting = { InteractionLighting.None },
                insets = { BackdropInsets.None },
            )

        /** Complete neutral processing, transparent fill, and disabled decorations. */
        @Stable
        public val NoEffects: BackdropStyle = Default {
            highlight = { Highlight.None }
            shadow = { Shadow.None }
        }
    }

    private class Builder(style: BackdropStyle) : BackdropStyleDsl {
        override var colorControls = style.colorControls
        override var blur = style.blur
        override var vibrancy = style.vibrancy
        override var lens = style.lens
        override var fill = style.fill
        override var highlight = style.highlight
        override var shadow = style.shadow
        override var innerShadow = style.innerShadow
        override var interactionLighting = style.interactionLighting
        override var insets = style.insets

        fun build(original: BackdropStyle): BackdropStyle {
            if (
                colorControls == original.colorControls &&
                    blur == original.blur &&
                    vibrancy == original.vibrancy &&
                    lens == original.lens &&
                    fill == original.fill &&
                    highlight == original.highlight &&
                    shadow == original.shadow &&
                    innerShadow == original.innerShadow &&
                    interactionLighting == original.interactionLighting &&
                    insets == original.insets
            )
                return original
            return BackdropStyle(
                colorControls,
                blur,
                vibrancy,
                lens,
                fill,
                highlight,
                shadow,
                innerShadow,
                interactionLighting,
                insets,
            )
        }
    }
}

public val LocalBackdropStyle: ProvidableCompositionLocal<BackdropStyle> = compositionLocalOf {
    BackdropStyle.Default
}

/** Merges explicit fields with the parent while retaining delayed producer references. */
@Composable
public fun ProvideBackdropStyle(style: BackdropStyle, content: @Composable () -> Unit) {
    val parent = LocalBackdropStyle.current
    CompositionLocalProvider(LocalBackdropStyle provides style.over(parent), content = content)
}

/** Resolves fields independently so explicitly configured fields never read the theme. */
internal class BackdropStyleResolver(
    private val explicit: BackdropStyle?,
    private val theme: () -> BackdropStyle,
) {
    fun colorControls(): BackdropColorControls =
        (explicit?.colorControls ?: theme().colorControls ?: BackdropStyle.Default.colorControls!!)
            .invoke()

    fun blur(): BackdropBlur =
        (explicit?.blur ?: theme().blur ?: BackdropStyle.Default.blur!!).invoke()

    fun vibrancy(): Float =
        (explicit?.vibrancy ?: theme().vibrancy ?: BackdropStyle.Default.vibrancy!!).invoke()

    fun lens(): BackdropLens =
        (explicit?.lens ?: theme().lens ?: BackdropStyle.Default.lens!!).invoke()

    fun fill(): BackdropFill =
        (explicit?.fill ?: theme().fill ?: BackdropStyle.Default.fill!!).invoke()

    fun highlight(): Highlight =
        (explicit?.highlight ?: theme().highlight ?: BackdropStyle.Default.highlight!!).invoke()

    fun shadow(): Shadow =
        (explicit?.shadow ?: theme().shadow ?: BackdropStyle.Default.shadow!!).invoke()

    fun innerShadow(): InnerShadow =
        (explicit?.innerShadow ?: theme().innerShadow ?: BackdropStyle.Default.innerShadow!!)
            .invoke()

    fun interactionLighting(): InteractionLighting =
        (explicit?.interactionLighting
                ?: theme().interactionLighting
                ?: BackdropStyle.Default.interactionLighting!!)
            .invoke()

    fun insets(): BackdropInsets =
        (explicit?.insets ?: theme().insets ?: BackdropStyle.Default.insets!!).invoke()
}
