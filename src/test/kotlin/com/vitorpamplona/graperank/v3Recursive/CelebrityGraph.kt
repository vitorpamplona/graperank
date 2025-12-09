package com.vitorpamplona.graperank.v3Recursive

import com.vitorpamplona.graperank.utils.assertClose
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

        assertClose(0.05394235327440411, newPleb.scores[pleb1])
        assertClose(0.001494481751665666, newPleb.scores[celebrity])
        assertEquals(null, newPleb.scores[pleb2])
        assertEquals(null, newPleb.scores[pleb3])

        newPleb follows pleb2

        assertClose(0.05394235327440411, newPleb.scores[pleb1])
        assertClose(0.002986730027625284, newPleb.scores[celebrity])
        assertClose(0.05394235327440411, newPleb.scores[pleb2])
        assertEquals(null, newPleb.scores[pleb3])

        newPleb follows pleb3

        assertClose(0.05394235327440411, newPleb.scores[pleb1])
        assertClose(0.004476748165767597, newPleb.scores[celebrity])

        pleb3 follows pleb1

        assertClose(0.05535621916345923, newPleb.scores[pleb1])
        assertClose(0.004515772595457879, newPleb.scores[celebrity])

        newPleb follows pleb4

        assertClose(0.05535621916345923, newPleb.scores[pleb1])
        assertClose(0.00600350560738494, newPleb.scores[celebrity])

        newPleb follows pleb5

        assertClose(0.05535621916345923, newPleb.scores[pleb1])
        assertClose(0.007489015229474427, newPleb.scores[celebrity])

        newPleb follows pleb6

        assertClose(0.05535621916345923, newPleb.scores[pleb1])
        assertClose(0.008972304784541696, newPleb.scores[celebrity])

        newPleb follows pleb7

        assertClose(0.05535621916345923, newPleb.scores[pleb1])
        assertClose(0.010453377590436519, newPleb.scores[celebrity])
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

        assertClose(0.05569, newPleb.scores[pleb1])
        assertClose(0.00183, newPleb.scores[celebrity])
        assertClose(0.00185, newPleb.scores[pleb2])
        assertClose(0.00185, newPleb.scores[pleb3])

        newPleb follows pleb2

        assertClose(0.05743, newPleb.scores[pleb1])
        assertClose(0.00367, newPleb.scores[celebrity])
        assertClose(0.05743, newPleb.scores[pleb2])
        assertClose(0.00368, newPleb.scores[pleb3])

        newPleb follows pleb3

        assertClose(0.05916, newPleb.scores[pleb1])
        assertClose(0.00550, newPleb.scores[celebrity])

        pleb3 follows pleb1

        assertClose(0.06075, newPleb.scores[pleb1])
        assertClose(0.00556, newPleb.scores[celebrity])

        newPleb follows pleb4

        assertClose(0.06251, newPleb.scores[pleb1])
        assertClose(0.00737, newPleb.scores[celebrity])

        newPleb follows pleb5

        assertClose(0.06426, newPleb.scores[pleb1])
        assertClose(0.00918, newPleb.scores[celebrity])

        newPleb follows pleb6

        assertClose(0.06601, newPleb.scores[pleb1])
        assertClose(0.01098, newPleb.scores[celebrity])

        newPleb follows pleb7

        assertClose(0.06774, newPleb.scores[pleb1])
        assertClose(0.01277, newPleb.scores[celebrity])
    }
}