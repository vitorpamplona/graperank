import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleGraph {
    @Test
    fun test() {
        val alice = User()
        val bob = User()
        val charlie = User()
        val david = User()

        val users = listOf(alice, bob, charlie, david)

        alice follows bob
        bob follows david
        bob follows charlie
        david mutes charlie
        alice reports charlie

        val scores = grapeRank(users, alice)

        assertEquals(1.0, scores[alice])
        assertEquals(0.05394235327440411, scores[bob])
        assertEquals(0.0, scores[charlie])
        assertEquals(0.001494481751665666, scores[david])
    }
}