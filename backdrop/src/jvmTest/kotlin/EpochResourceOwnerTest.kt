package top.ltfan.backdrop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import top.ltfan.backdrop.internal.EpochResourceOwner

class EpochResourceOwnerTest {
    @Test
    fun epochChangeCreatesReplacementBeforeReleasingPreviousResource() {
        val owner = EpochResourceOwner<Any>()
        val released = mutableListOf<Any>()
        val initial = owner.attach(0) { Any() }

        val current =
            owner.resourceFor(
                epoch = 1,
                create = {
                    assertEquals(emptyList(), released)
                    Any()
                },
                release = { released += it },
            )

        assertNotSame(initial, current)
        assertEquals(listOf(initial), released)
        assertSame(
            current,
            owner.resourceFor(
                epoch = 1,
                create = { error("An unchanged epoch must reuse its resource") },
                release = { released += it },
            ),
        )
    }
}
