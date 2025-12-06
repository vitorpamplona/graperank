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

        assertEquals(0.055686583499358074, newPleb.scores[pleb1])
        assertEquals(0.0018436828146688455, newPleb.scores[celebrity])
        assertEquals(0.0018458602531107315, newPleb.scores[pleb2])
        assertEquals(0.0018469463943409092, newPleb.scores[pleb3])

        newPleb follows pleb2

        assertEquals(0.057432004716325154, newPleb.scores[pleb1])
        assertEquals(0.003688624529381568, newPleb.scores[celebrity])
        assertEquals(0.05743222225174204, newPleb.scores[pleb2])
        assertEquals(0.0036890061955414666, newPleb.scores[pleb3])

        newPleb follows pleb3

        assertEquals(0.05916135695902347, newPleb.scores[pleb1])
        assertEquals(0.005516581048399027, newPleb.scores[celebrity])

        pleb3 follows pleb1

        assertEquals(0.06075257596100659, newPleb.scores[pleb1])
        assertEquals(0.005567534262442608, newPleb.scores[celebrity])

        newPleb follows pleb4

        assertEquals(0.06251587328324792, newPleb.scores[pleb1])
        assertEquals(0.007387097633949624, newPleb.scores[celebrity])

        newPleb follows pleb5

        assertEquals(0.06426716595759019, newPleb.scores[pleb1])
        assertEquals(0.009194490131380515, newPleb.scores[celebrity])

        newPleb follows pleb6

        assertEquals(0.06600788469500507, newPleb.scores[pleb1])
        assertEquals(0.010991155717923085, newPleb.scores[celebrity])

        newPleb follows pleb7

        assertEquals(0.06773813345851798, newPleb.scores[pleb1])
        assertEquals(0.012777196549572212, newPleb.scores[celebrity])
    }
}