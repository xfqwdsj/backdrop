package top.ltfan.backdrop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import org.intellij.lang.annotations.Language

public expect fun RuntimeShader(@Language("AGSL") shaderString: String): RuntimeShader

public expect fun RuntimeShader.asComposeShader(): Shader

public interface RuntimeShader {

    public fun setFloatUniform(name: String, value: Float)

    public fun setFloatUniform(name: String, value1: Float, value2: Float)

    public fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float)

    public fun setFloatUniform(
        name: String,
        value1: Float,
        value2: Float,
        value3: Float,
        value4: Float,
    )

    public fun setFloatUniform(name: String, values: FloatArray)

    public fun setIntUniform(name: String, value: Int)

    public fun setIntUniform(name: String, value1: Int, value2: Int)

    public fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int)

    public fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int)

    public fun setIntUniform(name: String, values: IntArray)

    /**
     * Sets an unpremultiplied color on a `layout(color)` uniform. The backend converts it to the
     * shader's working color space without reducing it to 8-bit sRGB. The shader must premultiply
     * RGB by alpha before returning it.
     */
    public fun setColorUniform(name: String, color: Color)
}
