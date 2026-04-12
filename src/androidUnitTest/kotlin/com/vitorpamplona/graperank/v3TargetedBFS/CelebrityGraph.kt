package com.vitorpamplona.graperank.v3TargetedBFS

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

        assertClose(0.25516126843864884, newPleb.scores[pleb1])
        assertClose(0.004499885043810381, newPleb.scores[celebrity])
        assertEquals(null, newPleb.scores[pleb2])
        assertEquals(null, newPleb.scores[pleb3])

        newPleb follows pleb2

        assertClose(0.25516126843864884, newPleb.scores[pleb1])
        assertClose(0.00897952112221323, newPleb.scores[celebrity])
        assertClose(0.25516126843864884, newPleb.scores[pleb2])
        assertEquals(null, newPleb.scores[pleb3])

        newPleb follows pleb3

        assertClose(0.25516126843864884, newPleb.scores[pleb1])
        assertClose(0.013438999353225234, newPleb.scores[celebrity])

        pleb3 follows pleb1

        assertClose(0.2585129571068525, newPleb.scores[pleb1])
        assertClose(0.013497443415107724, newPleb.scores[celebrity])

        newPleb follows pleb4

        assertClose(0.2585129571068525, newPleb.scores[pleb1])
        assertClose(0.0179365915151648, newPleb.scores[celebrity])

        newPleb follows pleb5

        assertClose(0.2585129571068525, newPleb.scores[pleb1])
        assertClose(0.022355763959079122, newPleb.scores[celebrity])

        newPleb follows pleb6

        assertClose(0.2585129571068525, newPleb.scores[pleb1])
        assertClose(0.02675505063500705, newPleb.scores[celebrity])

        newPleb follows pleb7

        assertClose(0.2585129571068525, newPleb.scores[pleb1])
        assertClose(0.031134541026618612, newPleb.scores[celebrity])
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

        assertClose(0.25896, newPleb.scores[pleb1])
        assertClose(0.00511, newPleb.scores[celebrity])
        assertClose(0.00511, newPleb.scores[pleb2])
        assertClose(0.00511, newPleb.scores[pleb3])

        newPleb follows pleb2

        assertClose(0.26271, newPleb.scores[pleb1])
        assertClose(0.01013, newPleb.scores[celebrity])
        assertClose(0.26271, newPleb.scores[pleb2])
        assertClose(0.01013, newPleb.scores[pleb3])

        newPleb follows pleb3

        assertClose(0.26639, newPleb.scores[pleb1])
        assertClose(0.01508, newPleb.scores[celebrity])

        pleb3 follows pleb1

        assertClose(0.26989, newPleb.scores[pleb1])
        assertClose(0.01514, newPleb.scores[celebrity])

        newPleb follows pleb4

        assertClose(0.27354, newPleb.scores[pleb1])
        assertClose(0.02001, newPleb.scores[celebrity])

        newPleb follows pleb5

        assertClose(0.27714, newPleb.scores[pleb1])
        assertClose(0.02481, newPleb.scores[celebrity])

        newPleb follows pleb6

        assertClose(0.28069, newPleb.scores[pleb1])
        assertClose(0.02953, newPleb.scores[celebrity])

        newPleb follows pleb7

        assertClose(0.28418, newPleb.scores[pleb1])
        assertClose(0.03418, newPleb.scores[celebrity])
    }
}
