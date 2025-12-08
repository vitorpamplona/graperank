package com.vitorpamplona.graperank.v3Recursive

import kotlin.test.Test
import kotlin.test.assertEquals

class CircularGraph {
    @Test
    fun testCircular() = with(Graph()) {
        val pleb1 = newUser()
        val pleb2 = newUser()
        val pleb3 = newUser()

        makeObserver(pleb1)

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(null, pleb1.scores[pleb2])
        assertEquals(null, pleb1.scores[pleb3])

        pleb1 follows pleb2
        pleb2 follows pleb3
        pleb3 follows pleb1

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(0.05394235327440411, pleb1.scores[pleb2])
        assertEquals(0.001494481751665666, pleb1.scores[pleb3])
    }

    @Test
    fun testCircularReverseOrder() = with(Graph()) {
        val pleb1 = newUser()
        val pleb2 = newUser()
        val pleb3 = newUser()

        pleb1 follows pleb2
        pleb2 follows pleb3
        pleb3 follows pleb1

        makeObserver(pleb1)

        assertEquals(1.0, pleb1.scores[pleb1])
        assertEquals(0.05394235327440411, pleb1.scores[pleb2])
        assertEquals(0.001494481751665666, pleb1.scores[pleb3])
    }
}