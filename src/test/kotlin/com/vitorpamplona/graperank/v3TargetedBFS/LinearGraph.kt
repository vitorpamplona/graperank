package com.vitorpamplona.graperank.v3TargetedBFS

import kotlin.test.Test
import kotlin.test.assertEquals

class LinearGraph {
    @Test
    fun test() = with(Graph()) {
        val minus4hops = newUser()
        val minus3hops = newUser()
        val minus2hops = newUser()
        val minus1hops = newUser()

        val center = newUser()

        val plus1hops = newUser()
        val plus2hops = newUser()
        val plus3hops = newUser()
        val plus4hops = newUser()

        minus4hops follows minus3hops
        minus3hops follows minus2hops
        minus2hops follows minus1hops
        minus1hops follows center
        center follows plus1hops
        plus1hops follows plus2hops
        plus2hops follows plus3hops
        plus3hops follows plus4hops

        makeObserver(center)

        assertEquals(null, center.scores[minus4hops])
        assertEquals(null, center.scores[minus3hops])
        assertEquals(null, center.scores[minus2hops])
        assertEquals(null, center.scores[minus1hops])
        assertEquals(1.0, center.scores[center])
        assertEquals(0.25516126843864884, center.scores[plus1hops])
        assertEquals(0.004499885043810381, center.scores[plus2hops])
        assertEquals(7.953344413746954E-5, center.scores[plus3hops])
        assertEquals(null, center.scores[plus4hops])

        makeObserver(minus4hops)

        assertEquals(1.0, minus4hops.scores[minus4hops])
        assertEquals(0.25516126843864884, minus4hops.scores[minus3hops])
        assertEquals(0.004499885043810381, minus4hops.scores[minus2hops])
        assertEquals(7.953344413746954E-5, minus4hops.scores[minus1hops])
        assertEquals(null, minus4hops.scores[center])
        assertEquals(null, minus4hops.scores[plus1hops])
        assertEquals(null, minus4hops.scores[plus2hops])
        assertEquals(null, minus4hops.scores[plus3hops])
        assertEquals(null, minus4hops.scores[plus4hops])

        makeObserver(plus4hops)

        assertEquals(null, plus4hops.scores[minus4hops])
        assertEquals(null, plus4hops.scores[minus3hops])
        assertEquals(null, plus4hops.scores[minus2hops])
        assertEquals(null, plus4hops.scores[minus1hops])
        assertEquals(null, plus4hops.scores[center])
        assertEquals(null, plus4hops.scores[plus1hops])
        assertEquals(null, plus4hops.scores[plus2hops])
        assertEquals(null, plus4hops.scores[plus3hops])
        assertEquals(1.0, plus4hops.scores[plus4hops])
    }
}