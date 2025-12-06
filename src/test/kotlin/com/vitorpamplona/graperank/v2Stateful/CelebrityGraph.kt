package com.vitorpamplona.graperank.v2Stateful

import kotlin.test.Test
import kotlin.test.assertEquals

class CelebrityGraph {
    @Test
    fun test() = with(Graph()) {
        val celebrity = newUser()
        val pleb1 = newUser()
        val pleb2 = newUser()
        val pleb3 = newUser()
        val pleb4 = newUser()
        val pleb5 = newUser()
        val pleb6 = newUser()
        val pleb7 = newUser()


        pleb1 follows celebrity
        pleb2 follows celebrity
        pleb3 follows celebrity
        pleb4 follows celebrity
        pleb5 follows celebrity
        pleb6 follows celebrity
        pleb7 follows celebrity

        val newPleb = newUser()

        makeObserver(newPleb)

        assertEquals(0.0, newPleb.scores[celebrity])
        assertEquals(0.0, newPleb.scores[pleb1])
        assertEquals(0.0, newPleb.scores[pleb2])

        newPleb follows pleb1

        assertEquals(0.05394235327440411, newPleb.scores[pleb1])
        assertEquals(0.001494481751665666, newPleb.scores[celebrity])
        assertEquals(0.0, newPleb.scores[pleb2])
        assertEquals(0.0, newPleb.scores[pleb3])

        newPleb follows pleb2

        assertEquals(0.05394235327440411, newPleb.scores[pleb1])
        assertEquals(0.002986730027625284, newPleb.scores[celebrity])
        assertEquals(0.05394235327440411, newPleb.scores[pleb2])
        assertEquals(0.0, newPleb.scores[pleb3])

        newPleb follows pleb3

        assertEquals(0.05394235327440411, newPleb.scores[pleb1])
        assertEquals(0.004476748165767597, newPleb.scores[celebrity])

        pleb3 follows pleb1

        assertEquals(0.05535621916345923, newPleb.scores[pleb1])
        assertEquals(0.004515772595457879, newPleb.scores[celebrity])

        newPleb follows pleb4

        assertEquals(0.05535621916345923, newPleb.scores[pleb1])
        assertEquals(0.00600350560738494, newPleb.scores[celebrity])

        newPleb follows pleb5

        assertEquals(0.05535621916345923, newPleb.scores[pleb1])
        assertEquals(0.007489015229474427, newPleb.scores[celebrity])

        newPleb follows pleb6

        assertEquals(0.05535621916345923, newPleb.scores[pleb1])
        assertEquals(0.008972304784541696, newPleb.scores[celebrity])

        newPleb follows pleb7

        assertEquals(0.05535621916345923, newPleb.scores[pleb1])
        assertEquals(0.010453377590436519, newPleb.scores[celebrity])
    }
}