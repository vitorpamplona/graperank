package com.vitorpamplona.graperank.v3Recursive

import kotlin.test.Test
import kotlin.test.assertEquals

class CelebrityGraph {
    @Test
    fun testWeakPlebNetwork() = with(Graph()) {
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

        assertEquals(null, newPleb.scores[celebrity])
        assertEquals(null, newPleb.scores[pleb1])
        assertEquals(null, newPleb.scores[pleb2])

        newPleb follows pleb1

        assertEquals(0.05394235327440411, newPleb.scores[pleb1])
        assertEquals(0.001494481751665666, newPleb.scores[celebrity])
        assertEquals(null, newPleb.scores[pleb2])
        assertEquals(null, newPleb.scores[pleb3])

        newPleb follows pleb2

        assertEquals(0.05394235327440411, newPleb.scores[pleb1])
        assertEquals(0.002986730027625284, newPleb.scores[celebrity])
        assertEquals(0.05394235327440411, newPleb.scores[pleb2])
        assertEquals(null, newPleb.scores[pleb3])

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

    @Test
    fun testStrongPlebNetwork() = with(Graph()) {
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

        // all plebs follow each otehr
        val plebs = listOf(pleb1, pleb2, pleb3, pleb4, pleb5, pleb6, pleb7)
        plebs.forEach { plebA ->
            plebs.forEach { plebB ->
                plebA follows plebB
            }
        }

        val newPleb = newUser()

        makeObserver(newPleb)

        assertEquals(null, newPleb.scores[celebrity])
        assertEquals(null, newPleb.scores[pleb1])
        assertEquals(null, newPleb.scores[pleb2])

        newPleb follows pleb1

        assertEquals(0.055692738273896876, newPleb.scores[pleb1])
        assertEquals(0.001837352618892285, newPleb.scores[celebrity])
        assertEquals(0.0018501151812080339, newPleb.scores[pleb2])
        assertEquals(0.001850133760869177, newPleb.scores[pleb3])

        newPleb follows pleb2

        assertEquals(0.0574323334798954, newPleb.scores[pleb1])
        assertEquals(0.003676787514139246, newPleb.scores[celebrity])
        assertEquals(0.0574327006908637, newPleb.scores[pleb2])
        assertEquals(0.0036893071617520867, newPleb.scores[pleb3])

        newPleb follows pleb3

        assertEquals(0.059161975308458674, newPleb.scores[pleb1])
        assertEquals(0.005505298773733069, newPleb.scores[celebrity])

        pleb3 follows pleb1

        assertEquals(0.060753838425860573, newPleb.scores[pleb1])
        assertEquals(0.005560141539988739, newPleb.scores[celebrity])

        newPleb follows pleb4

        assertEquals(0.06251759083683106, newPleb.scores[pleb1])
        assertEquals(0.007376524154573904, newPleb.scores[celebrity])

        newPleb follows pleb5

        assertEquals(0.0642695065164357, newPleb.scores[pleb1])
        assertEquals(0.009184745086210588, newPleb.scores[celebrity])

        newPleb follows pleb6

        assertEquals(0.06601099625883189, newPleb.scores[pleb1])
        assertEquals(0.0109823945463563, newPleb.scores[celebrity])

        newPleb follows pleb7

        assertEquals(0.06774217470570998, newPleb.scores[pleb1])
        assertEquals(0.012770043902771033, newPleb.scores[celebrity])
    }
}