package com.vitorpamplona.graperank.v2Stateful

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

class StatefulGraph {
    @Test
    fun test() = with(Graph()) {
        val alice = newUser()
        val bob = newUser()
        val charlie = newUser()
        val david = newUser()

        // adds fake users
        repeat(50000) {
            newUser()
        }

        val time0 = measureTime {
            makeObserver(alice)
        }
        println("makeObserver: GrapeRank computed in $time0")

        assertEquals(1.0, alice.scores[alice])
        assertEquals(0.0, alice.scores[bob])
        assertEquals(0.0, alice.scores[charlie])
        assertEquals(0.0, alice.scores[david])

        val time1 = measureTime {
            alice follows bob
        }
        println("alice follows bob: GrapeRank computed in $time1")

        assertEquals(1.0, alice.scores[alice])
        assertEquals(0.05394235327440411, alice.scores[bob])
        assertEquals(0.0, alice.scores[charlie])
        assertEquals(0.0, alice.scores[david])

        val time2 = measureTime {
            bob follows david
        }
        println("bob follows david: GrapeRank computed in $time2")

        assertEquals(1.0, alice.scores[alice])
        assertEquals(0.05394235327440411, alice.scores[bob])
        assertEquals(0.0, alice.scores[charlie])
        assertEquals(0.001494481751665666, alice.scores[david])

        val time3 = measureTime {
            bob follows charlie
        }
        println("bob follows charlie: GrapeRank computed in $time3")

        assertEquals(1.0, alice.scores[alice])
        assertEquals(0.05394235327440411, alice.scores[bob])
        assertEquals(0.001494481751665666, alice.scores[charlie])
        assertEquals(0.001494481751665666, alice.scores[david])

        val time4 = measureTime {
            david mutes charlie
        }
        println("david mutes charlie: GrapeRank computed in $time4")

        assertEquals(1.0, alice.scores[alice])
        assertEquals(0.05394235327440411, alice.scores[bob])
        assertEquals(0.0014941722461121313, alice.scores[charlie])
        assertEquals(0.001494481751665666, alice.scores[david])

        val time5 = measureTime {
            alice reports charlie
        }
        println("alice reports charlie: GrapeRank computed in $time5")

        assertEquals(1.0, alice.scores[alice])
        assertEquals(0.05394235327440411, alice.scores[bob])
        assertEquals(0.0, alice.scores[charlie])
        assertEquals(0.001494481751665666, alice.scores[david])
    }
}