package top.ltfan.backdrop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackdropResourcePoolTest : PlatformGraphicsTest() {
    private data class Value(val key: Int)

    @Test
    fun keysRetainValuesAndClearDisposesEachValueOnce() {
        val resources = PrimitiveResources { error("No layers are requested") }
        var created = 0
        val disposed = mutableListOf<Int>()
        val pool =
            resources.resourcePool<Int, Value>(dispose = { disposed += it.key }) { key ->
                created++
                Value(key)
            }
        val first = pool.getOrCreate(1)
        pool.getOrCreate(2)
        assertSame(first, pool.getOrCreate(1))
        assertEquals(2, created)
        pool.retainOnly(setOf(1, 3))
        assertEquals(listOf(2), disposed)
        assertEquals(1, pool.size)
        assertSame(first, pool.getOrCreate(1))
        pool.getOrCreate(3)
        pool.clear()
        assertEquals(listOf(2, 1, 3), disposed)
        resources.close()
        assertEquals(3, disposed.size)
        assertFailsWith<IllegalStateException> { pool.getOrCreate(1) }
    }

    @Test
    fun refreshRecreatesValuesAndKeepsIsolatedShaderNamespaces() {
        val resources = PrimitiveResources { error("No layers are requested") }
        var disposed = 0
        val text = "half4 main(float2 p) { return half4(1.0); }"
        val pool =
            resources.resourcePool<Int, RuntimeShader>(dispose = { disposed++ }) {
                obtainRuntimeShader("program", text)
            }
        val first = pool.getOrCreate(1)
        assertNotSame(first, pool.getOrCreate(2))
        resources.releaseLayers()
        assertEquals(2, disposed)
        assertEquals(2, pool.size)
        assertSame(first, pool.getOrCreate(1))
        pool.retainOnly(emptySet())
        assertNotSame(first, pool.getOrCreate(1))
        resources.close()
        assertEquals(4, disposed)
    }

    @Test
    fun failedFactoryClosesEntryResourcesAndNestedPools() {
        val resources = PrimitiveResources { error("No layers are requested") }
        var nestedDisposals = 0
        val pool =
            resources.resourcePool<Int, Value> { key ->
                val nested = resourcePool<Int, Value>(dispose = { nestedDisposals++ }) { Value(it) }
                nested.getOrCreate(key)
                error("factory failure")
            }
        repeat(2) {
            val failure = assertFailsWith<IllegalStateException> { pool.getOrCreate(1) }
            assertEquals("factory failure", failure.message)
            assertEquals(0, pool.size)
        }
        assertEquals(2, nestedDisposals)
        resources.close()
    }

    @Test
    fun disposalFailuresStillReleaseOtherEntriesAndRejectCallbackReentry() {
        val resources = PrimitiveResources { error("No layers are requested") }
        val released = mutableListOf<Int>()
        lateinit var pool: BackdropResourcePool<Int, Value>
        pool =
            resources.resourcePool(
                dispose = {
                    assertFailsWith<IllegalStateException> { pool.getOrCreate(9) }
                    released += it.key
                    error("release ${it.key}")
                },
            ) {
                Value(it)
            }
        pool.getOrCreate(1)
        pool.getOrCreate(2)
        val failure = assertFailsWith<IllegalStateException> { pool.clear() }
        assertEquals("release 1", failure.message)
        assertEquals(1, failure.suppressedExceptions.size)
        assertEquals(listOf(1, 2), released)
        assertEquals(0, pool.size)
        resources.close()
    }

    @Test
    fun ownerReleaseContinuesAfterOnePoolFails() {
        val resources = PrimitiveResources { error("No layers are requested") }
        val bad = resources.resourcePool<Int, Value>(dispose = { error("dispose") }) { Value(it) }
        var released = false
        val good = resources.resourcePool<Int, Value>(dispose = { released = true }) { Value(it) }
        bad.getOrCreate(1)
        good.getOrCreate(1)
        assertFailsWith<IllegalStateException> { resources.close() }
        assertTrue(released)
        assertFailsWith<IllegalStateException> { good.getOrCreate(1) }
        assertFailsWith<IllegalStateException> { resources.resourcePool<Int, Value> { Value(it) } }
        resources.close()
    }
}
