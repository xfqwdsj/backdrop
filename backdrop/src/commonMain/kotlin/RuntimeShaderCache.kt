package top.ltfan.backdrop

import org.intellij.lang.annotations.Language

public sealed interface RuntimeShaderCache {

    public fun obtainRuntimeShader(key: String, @Language("AGSL") string: String): RuntimeShader
}

internal class RuntimeShaderCacheImpl : RuntimeShaderCache {

    private class Entry(val source: String, val shader: RuntimeShader)

    private val runtimeShaders = mutableMapOf<String, Entry>()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        val cached = runtimeShaders[key]
        if (cached != null && cached.source == string) return cached.shader
        return RuntimeShader(string).also { runtimeShaders[key] = Entry(string, it) }
    }

    internal fun obtainRuntimeShader(key: String, string: () -> String): RuntimeShader {
        return runtimeShaders
            .getOrPut(key) {
                val source = string()
                Entry(source, RuntimeShader(source))
            }
            .shader
    }

    fun clear() {
        runtimeShaders.clear()
    }
}
