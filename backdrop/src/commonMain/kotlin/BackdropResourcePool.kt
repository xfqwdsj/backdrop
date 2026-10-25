package top.ltfan.backdrop

/**
 * Retains renderer state by keys with immutable equality and hash behavior. Each entry owns a
 * separate layer owner and shader namespace. Parameter changes update the retained value; callers
 * select active entries with [retainOnly]. Access belongs to the renderer's lifecycle.
 *
 * Display refresh disposes values and releases their layers, retaining shader caches for lazy
 * recreation. Removal and owner release dispose entries and their shader caches. Values borrowed
 * from the pool remain valid until removal, clearing, display refresh or owner release.
 */
public class BackdropResourcePool<K : Any, V : Any>
internal constructor(
    private val resources: () -> PrimitiveResources,
    private val create: BackdropRenderResources.(K) -> V,
    private val dispose: (V) -> Unit,
) {
    private class Entry<V>(val resources: PrimitiveResources, var value: V?)

    private val entries = mutableMapOf<K, Entry<V>>()
    private var closed = false
    private var changing = false

    /** Number of retained keys, including entries awaiting recreation after display refresh. */
    public val size: Int
        get() = entries.size

    /**
     * Returns the retained value or creates it using this key's resource owner. Resources allocated
     * through that owner are cleaned up if construction throws. The factory owns cleanup of other
     * partially constructed resources. Callbacks operate on entry resources; pool mutations are
     * guarded for the duration of each callback.
     */
    public fun getOrCreate(key: K): V {
        checkAccess()
        val entry = entries[key] ?: Entry<V>(resources(), null).also { entries[key] = it }
        entry.value?.let {
            return it
        }
        changing = true
        try {
            return create(entry.resources, key).also { entry.value = it }
        } catch (failure: Throwable) {
            entries.remove(key)
            try {
                entry.resources.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        } finally {
            changing = false
        }
    }

    /** Releases entries absent from [keys], retaining existing values for the remaining keys. */
    public fun retainOnly(keys: Set<K>) {
        checkAccess()
        changing = true
        var failure: Throwable? = null
        try {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key in keys) continue
                iterator.remove()
                try {
                    release(entry.value, refresh = false)
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
        } finally {
            changing = false
        }
        failure?.let { throw it }
    }

    /** Disposes all retained values and releases their layers and shader caches. */
    public fun clear(): Unit = retainOnly(emptySet())

    internal fun refresh() {
        checkAccess()
        changing = true
        var failure: Throwable? = null
        try {
            for (entry in entries.values) {
                try {
                    release(entry, refresh = true)
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
        } finally {
            changing = false
        }
        failure?.let { throw it }
    }

    internal fun close() {
        if (closed) return
        try {
            clear()
        } finally {
            closed = true
        }
    }

    private fun release(entry: Entry<V>, refresh: Boolean) {
        val value = entry.value
        entry.value = null
        var failure: Throwable? = null
        try {
            if (value != null) dispose(value)
        } catch (problem: Throwable) {
            failure = problem
        }
        try {
            if (refresh) entry.resources.releaseLayers() else entry.resources.close()
        } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        }
        failure?.let { throw it }
    }

    private fun checkAccess() {
        check(!closed) { "The resource pool's owner has been released" }
        check(!changing) { "Resource pool callbacks cannot reenter their pool" }
    }
}
