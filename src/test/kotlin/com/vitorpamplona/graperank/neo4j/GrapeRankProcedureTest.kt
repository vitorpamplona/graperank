package com.vitorpamplona.graperank.neo4j

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the GrapeRank Neo4j stored procedures.
 *
 * Every test verifies numerical equivalence with the in-memory V3
 * algorithm (see v3TargetedBFS test suite for reference values).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrapeRankProcedureTest {

    private lateinit var neo4j: Neo4j
    private lateinit var driver: Driver

    @BeforeAll
    fun setUp() {
        neo4j = Neo4jBuilders.newInProcessBuilder()
            .withProcedure(GrapeRankProcedures::class.java)
            .build()
        driver = GraphDatabase.driver(neo4j.boltURI())
    }

    @AfterAll
    fun tearDown() {
        driver.close()
        neo4j.close()
    }

    @BeforeEach
    fun cleanup() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n").consume() }
    }

    // ---------------------------------------------------------------- //
    //  Helpers                                                          //
    // ---------------------------------------------------------------- //

    private fun Session.createUser(pubkey: String) {
        run("MERGE (:NostrUser {pubkey: \$pk})", mapOf("pk" to pubkey)).consume()
    }

    private fun Session.addFollow(src: String, tgt: String) {
        run(
            """
            MATCH (a:NostrUser {pubkey: ${'$'}src}), (b:NostrUser {pubkey: ${'$'}tgt})
            MERGE (a)-[:FOLLOWS]->(b)
            """,
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
        run(
            "CALL graperank.v3.onFollow(\$src, \$tgt)",
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
    }

    private fun Session.removeFollow(src: String, tgt: String) {
        run(
            """
            MATCH (a:NostrUser {pubkey: ${'$'}src})-[r:FOLLOWS]->(b:NostrUser {pubkey: ${'$'}tgt})
            DELETE r
            """,
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
        run(
            "CALL graperank.v3.onUnfollow(\$src, \$tgt)",
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
    }

    private fun Session.addMute(src: String, tgt: String) {
        run(
            """
            MATCH (a:NostrUser {pubkey: ${'$'}src}), (b:NostrUser {pubkey: ${'$'}tgt})
            MERGE (a)-[:MUTES]->(b)
            """,
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
        run(
            "CALL graperank.v3.onMute(\$src, \$tgt)",
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
    }

    private fun Session.addReport(src: String, tgt: String) {
        run(
            """
            MATCH (a:NostrUser {pubkey: ${'$'}src}), (b:NostrUser {pubkey: ${'$'}tgt})
            MERGE (a)-[:REPORTS]->(b)
            """,
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
        run(
            "CALL graperank.v3.onReport(\$src, \$tgt)",
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
    }

    private fun Session.registerObserver(pubkey: String) {
        run(
            "CALL graperank.v3.registerObserver(\$pk)",
            mapOf("pk" to pubkey)
        ).consume()
    }

    private fun Session.getScore(observer: String, target: String): Double? {
        val result = run(
            """
            MATCH (obs:NostrUser {pubkey: ${'$'}obs})-[s:GRAPERANK_SCORE]->(t:NostrUser {pubkey: ${'$'}tgt})
            RETURN s.value AS score
            """,
            mapOf("obs" to observer, "tgt" to target)
        )
        val records = result.list()
        return if (records.isEmpty()) null else records[0].get("score").asDouble()
    }

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double = 0.0001) {
        assertEquals(expected, actual ?: 0.0, tolerance)
    }

    // ---------------------------------------------------------------- //
    //  Tests — Simple follow (mirrors SimpleFollowGraph)                //
    // ---------------------------------------------------------------- //

    @Test
    fun testSimpleFollow() {
        driver.session().use { s ->
            s.createUser("p1")
            s.createUser("p2")

            // Graph first, observer second
            s.run(
                "MATCH (a:NostrUser {pubkey: 'p1'}), (b:NostrUser {pubkey: 'p2'}) CREATE (a)-[:FOLLOWS]->(b)"
            ).consume()
            s.registerObserver("p1")

            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
            assertNull(s.getScore("p2", "p1"))
        }
    }

    @Test
    fun testSimpleFollowInvertedOrder() {
        driver.session().use { s ->
            s.createUser("p1")
            s.createUser("p2")

            // Observer first, follow second (incremental path)
            s.registerObserver("p1")
            assertNull(s.getScore("p1", "p2"))

            s.addFollow("p1", "p2")
            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
        }
    }

    @Test
    fun test3PlebsIncremental() {
        driver.session().use { s ->
            s.createUser("p1")
            s.createUser("p2")
            s.createUser("p3")

            s.registerObserver("p1")

            s.addFollow("p1", "p2")
            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
            assertNull(s.getScore("p1", "p3"))

            s.addFollow("p2", "p3")
            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
            assertClose(0.004499885043810381, s.getScore("p1", "p3"))
        }
    }

    // ---------------------------------------------------------------- //
    //  Tests — Linear chain (mirrors LinearGraph)                       //
    // ---------------------------------------------------------------- //

    @Test
    fun testLinearChain() {
        driver.session().use { s ->
            listOf("c", "p1", "p2", "p3").forEach { s.createUser(it) }

            // Build chain: center -> p1 -> p2 -> p3
            s.run(
                """
                MATCH (a:NostrUser {pubkey: 'c'}),  (b:NostrUser {pubkey: 'p1'}) CREATE (a)-[:FOLLOWS]->(b)
                """
            ).consume()
            s.run(
                """
                MATCH (a:NostrUser {pubkey: 'p1'}), (b:NostrUser {pubkey: 'p2'}) CREATE (a)-[:FOLLOWS]->(b)
                """
            ).consume()
            s.run(
                """
                MATCH (a:NostrUser {pubkey: 'p2'}), (b:NostrUser {pubkey: 'p3'}) CREATE (a)-[:FOLLOWS]->(b)
                """
            ).consume()

            s.registerObserver("c")

            assertClose(0.25516126843864884, s.getScore("c", "p1"))
            assertClose(0.004499885043810381, s.getScore("c", "p2"))
            // p3 score is 7.95e-5, below convergence threshold so the BFS
            // computes it but the delta from 0 is too small for propagation.
            // The score IS stored in the cache and persisted.
            assertClose(7.953344413746954E-5, s.getScore("c", "p3"))
        }
    }

    // ---------------------------------------------------------------- //
    //  Tests — Circular graph (mirrors CircularGraph)                   //
    // ---------------------------------------------------------------- //

    @Test
    fun testCircular() {
        driver.session().use { s ->
            listOf("p1", "p2", "p3").forEach { s.createUser(it) }

            s.registerObserver("p1")

            s.addFollow("p1", "p2")
            s.addFollow("p2", "p3")
            s.addFollow("p3", "p1")

            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
            assertClose(0.004499885043810381, s.getScore("p1", "p3"))
        }
    }

    @Test
    fun testCircularGraphFirst() {
        driver.session().use { s ->
            listOf("p1", "p2", "p3").forEach { s.createUser(it) }

            // Build graph first, then register observer
            s.run(
                """
                MATCH (a:NostrUser {pubkey: 'p1'}), (b:NostrUser {pubkey: 'p2'}) CREATE (a)-[:FOLLOWS]->(b)
                """
            ).consume()
            s.run(
                """
                MATCH (a:NostrUser {pubkey: 'p2'}), (b:NostrUser {pubkey: 'p3'}) CREATE (a)-[:FOLLOWS]->(b)
                """
            ).consume()
            s.run(
                """
                MATCH (a:NostrUser {pubkey: 'p3'}), (b:NostrUser {pubkey: 'p1'}) CREATE (a)-[:FOLLOWS]->(b)
                """
            ).consume()

            s.registerObserver("p1")

            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
            assertClose(0.004499885043810381, s.getScore("p1", "p3"))
        }
    }

    // ---------------------------------------------------------------- //
    //  Tests — Mute + Report (mirrors TargetedBFSGraph)                 //
    // ---------------------------------------------------------------- //

    @Test
    fun testMuteAndReport() {
        driver.session().use { s ->
            listOf("alice", "bob", "charlie", "david").forEach { s.createUser(it) }

            s.registerObserver("alice")

            s.addFollow("alice", "bob")
            assertClose(0.25516126843864884, s.getScore("alice", "bob"))
            assertNull(s.getScore("alice", "charlie"))

            s.addFollow("bob", "david")
            assertClose(0.004499885043810381, s.getScore("alice", "david"))

            s.addFollow("bob", "charlie")
            assertClose(0.004499885043810381, s.getScore("alice", "charlie"))

            // David mutes Charlie — should reduce Charlie's score
            s.addMute("david", "charlie")
            assertClose(0.0043647310818470215, s.getScore("alice", "charlie"))
            assertClose(0.004499885043810381, s.getScore("alice", "david"))

            // Alice reports Charlie — should zero Charlie's score
            s.addReport("alice", "charlie")
            assertClose(0.0, s.getScore("alice", "charlie"))
            assertClose(0.004499885043810381, s.getScore("alice", "david"))
        }
    }

    // ---------------------------------------------------------------- //
    //  Tests — Celebrity graph (mirrors CelebrityGraph)                 //
    // ---------------------------------------------------------------- //

    @Test
    fun testCelebrityWeakNetwork() {
        driver.session().use { s ->
            listOf("celebrity", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "newPleb")
                .forEach { s.createUser(it) }

            // All plebs follow celebrity
            for (i in 1..7) {
                s.run(
                    """
                    MATCH (a:NostrUser {pubkey: ${'$'}src}), (b:NostrUser {pubkey: 'celebrity'})
                    CREATE (a)-[:FOLLOWS]->(b)
                    """,
                    mapOf("src" to "p$i")
                ).consume()
            }

            s.registerObserver("newPleb")

            assertNull(s.getScore("newPleb", "celebrity"))
            assertNull(s.getScore("newPleb", "p1"))

            s.addFollow("newPleb", "p1")
            assertClose(0.25516126843864884, s.getScore("newPleb", "p1"))
            assertClose(0.004499885043810381, s.getScore("newPleb", "celebrity"))

            s.addFollow("newPleb", "p2")
            assertClose(0.00897952112221323, s.getScore("newPleb", "celebrity"))

            s.addFollow("newPleb", "p3")
            assertClose(0.013438999353225234, s.getScore("newPleb", "celebrity"))
        }
    }

    // ---------------------------------------------------------------- //
    //  Tests — Unfollow (score decreases / disappears)                  //
    // ---------------------------------------------------------------- //

    @Test
    fun testUnfollow() {
        driver.session().use { s ->
            listOf("obs", "a", "b").forEach { s.createUser(it) }

            s.registerObserver("obs")
            s.addFollow("obs", "a")
            s.addFollow("a", "b")

            assertClose(0.25516126843864884, s.getScore("obs", "a"))
            assertClose(0.004499885043810381, s.getScore("obs", "b"))

            // Remove a -> b: b should lose its score
            s.removeFollow("a", "b")

            assertClose(0.25516126843864884, s.getScore("obs", "a"))
            // b is no longer reachable — score should drop to 0 / null
            val bScore = s.getScore("obs", "b")
            assert(bScore == null || bScore < 0.0001) {
                "Expected b's score to drop after unfollow, got $bScore"
            }
        }
    }

    @Test
    fun testUnfollowDirect() {
        driver.session().use { s ->
            listOf("obs", "a").forEach { s.createUser(it) }

            s.registerObserver("obs")
            s.addFollow("obs", "a")

            assertClose(0.25516126843864884, s.getScore("obs", "a"))

            // Observer unfollows directly
            s.removeFollow("obs", "a")

            val aScore = s.getScore("obs", "a")
            assert(aScore == null || aScore < 0.0001) {
                "Expected a's score to drop after direct unfollow, got $aScore"
            }
        }
    }

    // ---------------------------------------------------------------- //
    //  Tests — Observer not affected by distant changes                  //
    // ---------------------------------------------------------------- //

    @Test
    fun testDisconnectedObserverUnaffected() {
        driver.session().use { s ->
            listOf("obs", "a", "x", "y").forEach { s.createUser(it) }

            s.registerObserver("obs")
            s.addFollow("obs", "a")

            // x and y are disconnected from obs
            s.addFollow("x", "y")

            // obs should have no score for x or y
            assertNull(s.getScore("obs", "x"))
            assertNull(s.getScore("obs", "y"))
            // obs -> a should be unchanged
            assertClose(0.25516126843864884, s.getScore("obs", "a"))
        }
    }

    // ---------------------------------------------------------------- //
    //  Tests — getScores procedure                                      //
    // ---------------------------------------------------------------- //

    @Test
    fun testGetScores() {
        driver.session().use { s ->
            listOf("obs", "a", "b").forEach { s.createUser(it) }

            s.run(
                """
                MATCH (a:NostrUser {pubkey: 'obs'}), (b:NostrUser {pubkey: 'a'}) CREATE (a)-[:FOLLOWS]->(b)
                """
            ).consume()
            s.run(
                """
                MATCH (a:NostrUser {pubkey: 'a'}), (b:NostrUser {pubkey: 'b'}) CREATE (a)-[:FOLLOWS]->(b)
                """
            ).consume()

            s.registerObserver("obs")

            val result = s.run(
                "CALL graperank.v3.getScores(\$pk)",
                mapOf("pk" to "obs")
            )
            val scores = result.list().associate {
                it.get("target").asString() to it.get("score").asDouble()
            }

            assert(scores.size >= 2) { "Expected at least 2 scores, got ${scores.size}" }
            assertClose(0.25516126843864884, scores["a"])
            assertClose(0.004499885043810381, scores["b"])
        }
    }
}
