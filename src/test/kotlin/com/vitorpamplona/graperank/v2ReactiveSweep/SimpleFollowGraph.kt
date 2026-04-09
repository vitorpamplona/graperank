package com.vitorpamplona.graperank.v2ReactiveSweep

import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleFollowGraph {
    @Test
    fun test2Plebs() = with(Graph()) {
        val pleb1 = newUser()
        val pleb2 = newUser()

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(null, pleb1.scores[pleb2])

        assertEquals(null, pleb2.scores[pleb1])
        assertEquals(1.0, pleb2.scores[pleb2])

        pleb1 follows pleb2

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(null, pleb1.scores[pleb2])

        assertEquals(null, pleb2.scores[pleb1])
        assertEquals(1.0, pleb2.scores[pleb2])

        makeObserver(pleb1)

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(0.25516126843864884, pleb1.scores[pleb2])

        assertEquals(null, pleb2.scores[pleb1])
        assertEquals(1.0, pleb2.scores[pleb2])

        makeObserver(pleb2)

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(0.25516126843864884, pleb1.scores[pleb2])

        assertEquals(0.0, pleb2.scores[pleb1])
        assertEquals(1.0, pleb2.scores[pleb2])
    }

    @Test
    fun test3Plebs() = with(Graph()) {
        val pleb1 = newUser()
        val pleb2 = newUser()
        val pleb3 = newUser()

        makeObserver(pleb1)

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(0.0, pleb1.scores[pleb2])
        assertEquals(0.0, pleb1.scores[pleb3])

        pleb1 follows pleb2

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(0.25516126843864884, pleb1.scores[pleb2])
        assertEquals(0.0, pleb1.scores[pleb3])

        pleb2 follows pleb3

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(0.25516126843864884, pleb1.scores[pleb2])
        assertEquals(0.004499885043810381, pleb1.scores[pleb3])
    }
}