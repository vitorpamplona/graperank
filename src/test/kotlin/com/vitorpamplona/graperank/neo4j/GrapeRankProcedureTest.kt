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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the GrapeRank Neo4j stored procedures.
 *
 * Verifies numerical equivalence with the in-memory V3 algorithm
 * AND that scores are stored in the production format:
 *   node.influence_{observer}, node.hops_{observer}, node.trusted_reporters_{observer}
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
            "MATCH (a:NostrUser {pubkey: \$src}), (b:NostrUser {pubkey: \$tgt}) MERGE (a)-[:FOLLOWS]->(b)",
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
        run("CALL graperank.v3.onFollow(\$src, \$tgt)", mapOf("src" to src, "tgt" to tgt)).consume()
    }

    private fun Session.removeFollow(src: String, tgt: String) {
        run(
            "MATCH (a:NostrUser {pubkey: \$src})-[r:FOLLOWS]->(b:NostrUser {pubkey: \$tgt}) DELETE r",
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
        run("CALL graperank.v3.onUnfollow(\$src, \$tgt)", mapOf("src" to src, "tgt" to tgt)).consume()
    }

    private fun Session.addMute(src: String, tgt: String) {
        run(
            "MATCH (a:NostrUser {pubkey: \$src}), (b:NostrUser {pubkey: \$tgt}) MERGE (a)-[:MUTES]->(b)",
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
        run("CALL graperank.v3.onMute(\$src, \$tgt)", mapOf("src" to src, "tgt" to tgt)).consume()
    }

    private fun Session.addReport(src: String, tgt: String) {
        run(
            "MATCH (a:NostrUser {pubkey: \$src}), (b:NostrUser {pubkey: \$tgt}) MERGE (a)-[:REPORTS]->(b)",
            mapOf("src" to src, "tgt" to tgt)
        ).consume()
        run("CALL graperank.v3.onReport(\$src, \$tgt)", mapOf("src" to src, "tgt" to tgt)).consume()
    }

    private fun Session.registerObserver(pubkey: String) {
        run("CALL graperank.v3.registerObserver(\$pk)", mapOf("pk" to pubkey)).consume()
    }

    /** Read influence_{observer} property from the target node */
    private fun Session.getScore(observer: String, target: String): Double? {
        val prop = "influence_$observer"
        val result = run(
            "MATCH (n:NostrUser {pubkey: \$tgt}) RETURN n[\$prop] AS score",
            mapOf("tgt" to target, "prop" to prop)
        )
        val records = result.list()
        if (records.isEmpty()) return null
        val value = records[0].get("score")
        return if (value.isNull) null else value.asDouble()
    }

    /** Read hops_{observer} property */
    private fun Session.getHops(observer: String, target: String): Double? {
        val prop = "hops_$observer"
        val result = run(
            "MATCH (n:NostrUser {pubkey: \$tgt}) RETURN n[\$prop] AS hops",
            mapOf("tgt" to target, "prop" to prop)
        )
        val records = result.list()
        if (records.isEmpty()) return null
        val value = records[0].get("hops")
        return if (value.isNull) null else value.asDouble()
    }

    /** Read trusted_reporters_{observer} property */
    private fun Session.getTrustedReporters(observer: String, target: String): Double? {
        val prop = "trusted_reporters_$observer"
        val result = run(
            "MATCH (n:NostrUser {pubkey: \$tgt}) RETURN n[\$prop] AS tr",
            mapOf("tgt" to target, "prop" to prop)
        )
        val records = result.list()
        if (records.isEmpty()) return null
        val value = records[0].get("tr")
        return if (value.isNull) null else value.asDouble()
    }

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double = 0.0001) {
        assertEquals(expected, actual ?: 0.0, tolerance)
    }

    // ---------------------------------------------------------------- //
    //  Score value tests (numerical parity with V3)                     //
    // ---------------------------------------------------------------- //

    @Test
    fun testSimpleFollow() {
        driver.session().use { s ->
            s.createUser("p1"); s.createUser("p2")
            s.run("MATCH (a:NostrUser {pubkey: 'p1'}), (b:NostrUser {pubkey: 'p2'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.registerObserver("p1")

            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
        }
    }

    @Test
    fun testSimpleFollowInvertedOrder() {
        driver.session().use { s ->
            s.createUser("p1"); s.createUser("p2")
            s.registerObserver("p1")
            assertNull(s.getScore("p1", "p2"))

            s.addFollow("p1", "p2")
            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
        }
    }

    @Test
    fun test3PlebsIncremental() {
        driver.session().use { s ->
            s.createUser("p1"); s.createUser("p2"); s.createUser("p3")
            s.registerObserver("p1")

            s.addFollow("p1", "p2")
            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
            assertNull(s.getScore("p1", "p3"))

            s.addFollow("p2", "p3")
            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
            assertClose(0.004499885043810381, s.getScore("p1", "p3"))
        }
    }

    @Test
    fun testLinearChain() {
        driver.session().use { s ->
            listOf("c", "p1", "p2", "p3").forEach { s.createUser(it) }
            s.run("MATCH (a:NostrUser {pubkey: 'c'}),  (b:NostrUser {pubkey: 'p1'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.run("MATCH (a:NostrUser {pubkey: 'p1'}), (b:NostrUser {pubkey: 'p2'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.run("MATCH (a:NostrUser {pubkey: 'p2'}), (b:NostrUser {pubkey: 'p3'}) CREATE (a)-[:FOLLOWS]->(b)").consume()

            s.registerObserver("c")

            assertClose(0.25516126843864884, s.getScore("c", "p1"))
            assertClose(0.004499885043810381, s.getScore("c", "p2"))
            assertClose(7.953344413746954E-5, s.getScore("c", "p3"))
        }
    }

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
            s.run("MATCH (a:NostrUser {pubkey: 'p1'}), (b:NostrUser {pubkey: 'p2'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.run("MATCH (a:NostrUser {pubkey: 'p2'}), (b:NostrUser {pubkey: 'p3'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.run("MATCH (a:NostrUser {pubkey: 'p3'}), (b:NostrUser {pubkey: 'p1'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.registerObserver("p1")

            assertClose(0.25516126843864884, s.getScore("p1", "p2"))
            assertClose(0.004499885043810381, s.getScore("p1", "p3"))
        }
    }

    @Test
    fun testMuteAndReport() {
        driver.session().use { s ->
            listOf("alice", "bob", "charlie", "david").forEach { s.createUser(it) }
            s.registerObserver("alice")

            s.addFollow("alice", "bob")
            assertClose(0.25516126843864884, s.getScore("alice", "bob"))

            s.addFollow("bob", "david")
            assertClose(0.004499885043810381, s.getScore("alice", "david"))

            s.addFollow("bob", "charlie")
            assertClose(0.004499885043810381, s.getScore("alice", "charlie"))

            s.addMute("david", "charlie")
            assertClose(0.0043647310818470215, s.getScore("alice", "charlie"))

            s.addReport("alice", "charlie")
            assertClose(0.0, s.getScore("alice", "charlie"))
        }
    }

    @Test
    fun testCelebrityWeakNetwork() {
        driver.session().use { s ->
            listOf("celebrity", "p1", "p2", "p3", "newPleb").forEach { s.createUser(it) }
            for (i in 1..3) {
                s.run("MATCH (a:NostrUser {pubkey: \$src}), (b:NostrUser {pubkey: 'celebrity'}) CREATE (a)-[:FOLLOWS]->(b)",
                    mapOf("src" to "p$i")).consume()
            }

            s.registerObserver("newPleb")
            assertNull(s.getScore("newPleb", "celebrity"))

            s.addFollow("newPleb", "p1")
            assertClose(0.25516126843864884, s.getScore("newPleb", "p1"))
            assertClose(0.004499885043810381, s.getScore("newPleb", "celebrity"))

            s.addFollow("newPleb", "p2")
            assertClose(0.00897952112221323, s.getScore("newPleb", "celebrity"))

            s.addFollow("newPleb", "p3")
            assertClose(0.013438999353225234, s.getScore("newPleb", "celebrity"))
        }
    }

    @Test
    fun testUnfollow() {
        driver.session().use { s ->
            listOf("obs", "a", "b").forEach { s.createUser(it) }
            s.registerObserver("obs")
            s.addFollow("obs", "a")
            s.addFollow("a", "b")

            assertClose(0.25516126843864884, s.getScore("obs", "a"))
            assertClose(0.004499885043810381, s.getScore("obs", "b"))

            s.removeFollow("a", "b")

            assertClose(0.25516126843864884, s.getScore("obs", "a"))
            val bScore = s.getScore("obs", "b")
            assertTrue(bScore == null || bScore < 0.0001, "b's score should drop after unfollow, got $bScore")
        }
    }

    @Test
    fun testUnfollowDirect() {
        driver.session().use { s ->
            listOf("obs", "a").forEach { s.createUser(it) }
            s.registerObserver("obs")
            s.addFollow("obs", "a")
            assertClose(0.25516126843864884, s.getScore("obs", "a"))

            s.removeFollow("obs", "a")
            val aScore = s.getScore("obs", "a")
            assertTrue(aScore == null || aScore < 0.0001, "a's score should drop after direct unfollow, got $aScore")
        }
    }

    @Test
    fun testDisconnectedObserverUnaffected() {
        driver.session().use { s ->
            listOf("obs", "a", "x", "y").forEach { s.createUser(it) }
            s.registerObserver("obs")
            s.addFollow("obs", "a")
            s.addFollow("x", "y")

            assertNull(s.getScore("obs", "x"))
            assertNull(s.getScore("obs", "y"))
            assertClose(0.25516126843864884, s.getScore("obs", "a"))
        }
    }

    // ---------------------------------------------------------------- //
    //  Production format tests (properties, hops, trusted_reporters)     //
    // ---------------------------------------------------------------- //

    @Test
    fun testSelfScoreProperties() {
        driver.session().use { s ->
            s.createUser("obs")
            s.registerObserver("obs")

            assertClose(1.0, s.getScore("obs", "obs"))
            assertClose(0.0, s.getHops("obs", "obs"))
            assertClose(0.0, s.getTrustedReporters("obs", "obs"))
        }
    }

    @Test
    fun testHopsComputed() {
        driver.session().use { s ->
            listOf("obs", "a", "b", "c").forEach { s.createUser(it) }
            s.run("MATCH (a:NostrUser {pubkey: 'obs'}), (b:NostrUser {pubkey: 'a'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.run("MATCH (a:NostrUser {pubkey: 'a'}),   (b:NostrUser {pubkey: 'b'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.run("MATCH (a:NostrUser {pubkey: 'b'}),   (b:NostrUser {pubkey: 'c'}) CREATE (a)-[:FOLLOWS]->(b)").consume()

            s.registerObserver("obs")

            assertClose(1.0, s.getHops("obs", "a"))
            assertClose(2.0, s.getHops("obs", "b"))
            assertClose(3.0, s.getHops("obs", "c"))
        }
    }

    @Test
    fun testTrustedReportersComputed() {
        driver.session().use { s ->
            listOf("obs", "trusted", "untrusted", "victim").forEach { s.createUser(it) }

            // obs follows trusted (will get high influence)
            s.run("MATCH (a:NostrUser {pubkey: 'obs'}), (b:NostrUser {pubkey: 'trusted'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            // obs follows untrusted too
            s.run("MATCH (a:NostrUser {pubkey: 'obs'}), (b:NostrUser {pubkey: 'untrusted'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            // obs follows victim
            s.run("MATCH (a:NostrUser {pubkey: 'obs'}), (b:NostrUser {pubkey: 'victim'}) CREATE (a)-[:FOLLOWS]->(b)").consume()

            // Both report the victim
            s.run("MATCH (a:NostrUser {pubkey: 'trusted'}),   (b:NostrUser {pubkey: 'victim'}) CREATE (a)-[:REPORTS]->(b)").consume()
            s.run("MATCH (a:NostrUser {pubkey: 'untrusted'}), (b:NostrUser {pubkey: 'victim'}) CREATE (a)-[:REPORTS]->(b)").consume()

            s.registerObserver("obs")

            // obs directly follows trusted and untrusted → both get influence ~0.255 > 0.1
            val trustedScore = s.getScore("obs", "trusted")
            val untrustedScore = s.getScore("obs", "untrusted")
            assertNotNull(trustedScore)
            assertNotNull(untrustedScore)
            assertTrue(trustedScore > 0.1, "trusted should have influence > 0.1, got $trustedScore")
            assertTrue(untrustedScore > 0.1, "untrusted should have influence > 0.1, got $untrustedScore")

            // victim should have 2 trusted reporters (both > 0.1)
            val tr = s.getTrustedReporters("obs", "victim")
            assertNotNull(tr)
            assertClose(2.0, tr)
        }
    }

    @Test
    fun testGetScoresReturnsProperties() {
        driver.session().use { s ->
            listOf("obs", "a", "b").forEach { s.createUser(it) }
            s.run("MATCH (a:NostrUser {pubkey: 'obs'}), (b:NostrUser {pubkey: 'a'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.run("MATCH (a:NostrUser {pubkey: 'a'}),   (b:NostrUser {pubkey: 'b'}) CREATE (a)-[:FOLLOWS]->(b)").consume()
            s.registerObserver("obs")

            val result = s.run("CALL graperank.v3.getScores(\$pk)", mapOf("pk" to "obs"))
            val scores = result.list().associate {
                it.get("target").asString() to it.get("score").asDouble()
            }

            assertTrue(scores.size >= 2, "Expected at least 2 scores, got ${scores.size}")
            assertClose(0.25516126843864884, scores["a"])
            assertClose(0.004499885043810381, scores["b"])
        }
    }
}
