package com.vitorpamplona.graperank.neo4j

import org.neo4j.graphdb.Direction
import org.neo4j.graphdb.Label
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.Transaction
import org.neo4j.procedure.Context
import org.neo4j.procedure.Description
import org.neo4j.procedure.Mode
import org.neo4j.procedure.Name
import org.neo4j.procedure.Procedure
import java.util.stream.Stream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Neo4j stored procedures that run the V3 GrapeRank algorithm
 * incrementally on every follow / mute / report event.
 *
 * Scores are written as properties on NostrUser nodes in the same
 * format as brainstorm_server's write_neo4j_results.py:
 *
 *   node.influence_{observerPubkey}
 *   node.hops_{observerPubkey}
 *   node.trusted_reporters_{observerPubkey}
 */
class GrapeRankProcedures {

    @Context @JvmField var tx: Transaction? = null

    // ================================================================== //
    //                                                                      //
    //  1. PUBLIC API — the procedures called from Cypher / Python           //
    //                                                                      //
    // ================================================================== //

    class ScoreResult(
        @JvmField val observer: String,
        @JvmField val target: String,
        @JvmField val score: Double
    )

    @Procedure("graperank.v3.registerObserver", mode = Mode.WRITE)
    @Description("One-time setup: computes all trust scores for this observer.")
    fun registerObserver(@Name("pubkey") pubkey: String): Stream<ScoreResult> {
        val observer = tx!!.findNode(NOSTR_USER, "pubkey", pubkey)
            ?: return Stream.empty()
        observer.addLabel(GRAPERANK_OBSERVER)

        val ctx = ObserverContext(observer, pubkey)

        // Self-score: observer always trusts themselves at 1.0
        observer.setProperty(ctx.infProp, 1.0)
        observer.setProperty(ctx.hopProp, 0.0)
        observer.setProperty(ctx.trProp, 0.0)

        // Walk outward from the observer, scoring every reachable node
        propagateForward(ctx, observer)

        val scored = ctx.changedNodes
            .filter { it != ctx.obsElementId && (ctx.scores[it] ?: 0.0) > 1e-10 }
            .toSet()
        if (scored.isEmpty()) return Stream.empty()

        val hops = computeHopsBFS(observer, scored)
        return writeScores(ctx, scored, hops).stream()
    }

    @Procedure("graperank.v3.onFollow", mode = Mode.WRITE)
    @Description("Call AFTER creating a FOLLOWS edge.")
    fun onFollow(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = true)

    @Procedure("graperank.v3.onUnfollow", mode = Mode.WRITE)
    @Description("Call AFTER deleting a FOLLOWS edge.")
    fun onUnfollow(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = true)

    @Procedure("graperank.v3.onMute", mode = Mode.WRITE)
    @Description("Call AFTER creating a MUTES edge.")
    fun onMute(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = false)

    @Procedure("graperank.v3.onUnmute", mode = Mode.WRITE)
    @Description("Call AFTER deleting a MUTES edge.")
    fun onUnmute(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = false)

    @Procedure("graperank.v3.onReport", mode = Mode.WRITE)
    @Description("Call AFTER creating a REPORTS edge.")
    fun onReport(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = false)

    @Procedure("graperank.v3.getScores", mode = Mode.READ)
    @Description("Returns all scores for an observer.")
    fun getScores(@Name("observer") pubkey: String): Stream<ScoreResult> {
        tx!!.findNode(NOSTR_USER, "pubkey", pubkey) ?: return Stream.empty()
        val infProp = "influence_$pubkey"
        val results = mutableListOf<ScoreResult>()

        tx!!.findNodes(NOSTR_USER).use { iter ->
            while (iter.hasNext()) {
                val node = iter.next()
                if (node.hasProperty(infProp)) {
                    val score = node.getProperty(infProp) as Double
                    if (score > 1e-10) {
                        results.add(ScoreResult(pubkey, node.getProperty("pubkey") as String, score))
                    }
                }
            }
        }
        return results.stream()
    }

    // ================================================================== //
    //                                                                      //
    //  2. ORCHESTRATION — wires observers, algorithm, and persistence       //
    //                                                                      //
    // ================================================================== //

    /**
     * Main entry point for incremental updates.
     *
     * Flow:
     *   1. Find which observers can "see" the source node
     *   2. For each: run the V3 BFS from the target node
     *   3. Compute hops + trusted_reporters for changed nodes
     *   4. Write the three properties to Neo4j
     */
    private fun onEdgeChanged(
        sourcePubkey: String,
        targetPubkey: String,
        isFollowChange: Boolean
    ): Stream<ScoreResult> {
        val sourceNode = tx!!.findNode(NOSTR_USER, "pubkey", sourcePubkey)
            ?: return Stream.empty()
        val targetNode = tx!!.findNode(NOSTR_USER, "pubkey", targetPubkey)
            ?: return Stream.empty()

        val affected = findAffectedObservers(sourceNode)
        if (affected.isEmpty()) return Stream.empty()

        val allResults = mutableListOf<ScoreResult>()

        for ((observer, obsPubkey) in affected) {
            val ctx = ObserverContext(observer, obsPubkey)
            updateScores(ctx, targetNode)

            if (ctx.changedNodes.isEmpty()) continue

            val hops = computeHopsIncremental(ctx, ctx.changedNodes, isFollowChange)
            allResults.addAll(writeScores(ctx, ctx.changedNodes, hops))
        }

        return allResults.stream()
    }

    /**
     * An observer is affected by a change at [sourceNode] only if they
     * already have an influence_{obs} property on that node — meaning
     * the source is visible in their trust network.
     */
    private fun findAffectedObservers(sourceNode: Node): List<Pair<Node, String>> {
        val observers = mutableListOf<Pair<Node, String>>()
        tx!!.findNodes(GRAPERANK_OBSERVER).use { iter ->
            while (iter.hasNext()) {
                val obs = iter.next()
                val pk = obs.getProperty("pubkey") as String
                if (sourceNode.hasProperty("influence_$pk")) {
                    observers.add(obs to pk)
                }
            }
        }
        return observers
    }

    // ================================================================== //
    //                                                                      //
    //  3. V3 ALGORITHM — BFS with convergence                              //
    //                                                                      //
    // ================================================================== //

    /**
     * Outer convergence loop: recompute [target]'s score and propagate
     * downstream until [target] stabilises (handles graph cycles).
     */
    private fun updateScores(ctx: ObserverContext, target: Node) {
        while (recomputeScore(ctx, target)) {
            propagateForward(ctx, target)
        }
    }

    /**
     * BFS walk: starting from [target]'s outgoing neighbours, recompute
     * each node's score.  Only continue walking along paths where the
     * score actually changed (targeted propagation).
     */
    private fun propagateForward(ctx: ObserverContext, target: Node) {
        val queue = mutableSetOf<Node>()
        for (edge in target.getRelationships(Direction.OUTGOING, *SOCIAL_EDGES)) {
            queue.add(edge.endNode)
        }
        while (queue.isNotEmpty()) {
            val next = queue.first()
            queue.remove(next)
            if (recomputeScore(ctx, next)) {
                for (edge in next.getRelationships(Direction.OUTGOING, *SOCIAL_EDGES)) {
                    queue.add(edge.endNode)
                }
            }
        }
    }

    /**
     * Core scoring function.  For [target], aggregates weighted contributions
     * from all incoming FOLLOWS/MUTES/REPORTS edges, converts to a trust
     * score, and returns whether propagation should continue.
     *
     * Two independent checks happen here:
     *
     *   1. Persistence tracking — did the score change vs. what's stored
     *      on disk?  If so, add to changedNodes (uses 1e-10 threshold).
     *
     *   2. Convergence — did the score change vs. the previous BFS
     *      iteration?  If so, return true to keep propagating (uses
     *      CONVERGENCE_THRESHOLD = 0.0001).
     *
     * A node with a tiny score (e.g. 7.95e-5 at hop 3) may be tracked
     * for persistence (check 1) even though propagation stops (check 2).
     */
    private fun recomputeScore(ctx: ObserverContext, target: Node): Boolean {
        val tid = ctx.cacheNode(target)
        if (ctx.obsElementId == tid) return false

        // Record what was stored before we touched anything
        ctx.priorStored.putIfAbsent(tid, resolveScore(ctx, target) ?: 0.0)

        // --- Aggregate incoming edges ---
        var weights = 0.0
        var ratings = 0.0
        for (edge in target.getRelationships(Direction.INCOMING, *SOCIAL_EDGES)) {
            val source = edge.startNode
            val sourceScore = resolveScore(ctx, source) ?: continue
            val sid = ctx.cacheNode(source)

            val conf = confidence(edge.type, ctx.obsElementId, sid)
            val weight = conf * sourceScore * ATTENUATION
            weights += weight
            ratings += weight * rating(edge.type)
        }

        // --- Compute new score ---
        val newScore = if (abs(weights) < MINIMUM_WEIGHT) {
            0.0
        } else {
            (weightToConfidence(weights) * ratings / weights).coerceAtLeast(0.0)
        }

        val oldScore = ctx.scores.put(tid, newScore) ?: 0.0

        // --- Check 1: persistence (differs from stored?) ---
        val prior = ctx.priorStored[tid] ?: 0.0
        if (abs(newScore - prior) > 1e-10 || (prior == 0.0 && newScore > 1e-10)) {
            ctx.changedNodes.add(tid)
        }

        // --- Check 2: convergence (keep propagating?) ---
        return abs(newScore - oldScore) > CONVERGENCE_THRESHOLD
    }

    /**
     * Look up a node's score: check the in-memory cache first, then
     * fall back to the stored influence_{observer} property.
     * Returns null if the observer has never scored this node.
     */
    private fun resolveScore(ctx: ObserverContext, node: Node): Double? {
        val eid = ctx.cacheNode(node)
        ctx.scores[eid]?.let { return it }

        val stored = ctx.getStoredInfluence(node) ?: return null
        ctx.scores[eid] = stored
        ctx.priorStored.putIfAbsent(eid, stored)
        return stored
    }

    // ================================================================== //
    //                                                                      //
    //  4. PERSISTENCE — read/write node properties                         //
    //                                                                      //
    // ================================================================== //

    /**
     * Write influence, hops, and trusted_reporters to each changed node.
     * Removes all three properties if the score dropped to zero.
     */
    private fun writeScores(
        ctx: ObserverContext,
        changedNodeIds: Set<String>,
        hops: Map<String, Int>
    ): List<ScoreResult> {
        val results = mutableListOf<ScoreResult>()

        for (nodeId in changedNodeIds) {
            val score = ctx.scores[nodeId] ?: 0.0
            val node = ctx.nodeCache[nodeId] ?: tx!!.getNodeByElementId(nodeId)
            val pubkey = node.getProperty("pubkey") as String

            if (abs(score) < 1e-10) {
                node.removeProperty(ctx.infProp)
                node.removeProperty(ctx.hopProp)
                node.removeProperty(ctx.trProp)
            } else {
                node.setProperty(ctx.infProp, score)
                node.setProperty(ctx.hopProp, (hops[nodeId] ?: 0).toDouble())
                node.setProperty(ctx.trProp, countTrustedReporters(ctx, node).toDouble())
                results.add(ScoreResult(ctx.obsPubkey, pubkey, score))
            }
        }

        return results
    }

    /**
     * Count incoming REPORTS edges whose source has influence > 0.1
     * from this observer's perspective.
     */
    private fun countTrustedReporters(ctx: ObserverContext, target: Node): Int {
        var count = 0
        for (edge in target.getRelationships(Direction.INCOMING, REPORTS)) {
            val reporter = edge.startNode
            val inf = ctx.scores[reporter.elementId]
                ?: ctx.getStoredInfluence(reporter)
                ?: 0.0
            if (inf > CUTOFF_TRUSTED_REPORTER) count++
        }
        return count
    }

    // ================================================================== //
    //                                                                      //
    //  5. HOPS — shortest FOLLOWS-path distance from observer              //
    //                                                                      //
    // ================================================================== //

    /**
     * Full BFS through FOLLOWS edges.  Finds the shortest distance from
     * [observer] to each node in [targetIds].  Stops early once all
     * targets are found or MAX_HOPS is reached.
     *
     * Used by registerObserver (needs hops for all scored nodes).
     */
    private fun computeHopsBFS(observer: Node, targetIds: Set<String>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val remaining = targetIds.toMutableSet()

        if (observer.elementId in remaining) {
            result[observer.elementId] = 0
            remaining.remove(observer.elementId)
        }
        if (remaining.isEmpty()) return result

        val visited = mutableSetOf(observer.elementId)
        var frontier = listOf(observer)
        var depth = 0

        while (frontier.isNotEmpty() && depth < MAX_HOPS && remaining.isNotEmpty()) {
            depth++
            val next = mutableListOf<Node>()
            for (node in frontier) {
                for (edge in node.getRelationships(Direction.OUTGOING, FOLLOWS)) {
                    val t = edge.endNode
                    val tid = t.elementId
                    if (tid !in visited) {
                        visited.add(tid)
                        if (tid in remaining) {
                            result[tid] = depth
                            remaining.remove(tid)
                        }
                        next.add(t)
                    }
                }
            }
            frontier = next
        }

        return result
    }

    /**
     * Incremental hops: for mute/report changes, hops can't change so
     * we read the stored value.  For follow changes (or new nodes),
     * fall back to a full BFS.
     */
    private fun computeHopsIncremental(
        ctx: ObserverContext,
        targetIds: Set<String>,
        isFollowChange: Boolean
    ): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val needBFS = mutableSetOf<String>()

        for (tid in targetIds) {
            if (tid == ctx.obsElementId) {
                result[tid] = 0
                continue
            }
            val node = ctx.nodeCache[tid] ?: tx!!.getNodeByElementId(tid)
            val hasStoredHops = node.hasProperty(ctx.hopProp)

            if (!isFollowChange && hasStoredHops) {
                // Mute/report: hops don't change
                result[tid] = (node.getProperty(ctx.hopProp) as Double).toInt()
            } else {
                needBFS.add(tid)
            }
        }

        if (needBFS.isNotEmpty()) {
            result.putAll(computeHopsBFS(ctx.observer, needBFS))
        }

        return result
    }

    // ================================================================== //
    //                                                                      //
    //  6. OBSERVER CONTEXT — per-observer working state                    //
    //                                                                      //
    // ================================================================== //

    /**
     * Bundles all mutable state for one observer's score computation.
     * Created fresh for each procedure call.  Pre-computes property
     * name strings and caches Node references to avoid repeated lookups.
     */
    private inner class ObserverContext(
        val observer: Node,
        val obsPubkey: String
    ) {
        val obsElementId: String = observer.elementId

        // Property names (pre-computed to avoid string concat in hot loops)
        val infProp = "influence_$obsPubkey"
        val hopProp = "hops_$obsPubkey"
        val trProp = "trusted_reporters_$obsPubkey"

        // Score cache: elementId -> influence value
        val scores = mutableMapOf(obsElementId to 1.0)

        // What was stored in Neo4j before this computation (for change detection)
        val priorStored = mutableMapOf<String, Double>()

        // Nodes whose score changed relative to what was stored
        val changedNodes = mutableSetOf<String>()

        // Node object cache: elementId -> Node (avoids getNodeByElementId)
        val nodeCache = mutableMapOf(obsElementId to observer)

        /** Read the stored influence_{observer} property, or null if absent. */
        fun getStoredInfluence(node: Node): Double? =
            if (node.hasProperty(infProp)) node.getProperty(infProp) as Double else null

        /** Cache a node and return its elementId. */
        fun cacheNode(node: Node): String {
            val eid = node.elementId
            nodeCache.putIfAbsent(eid, node)
            return eid
        }
    }

    // ================================================================== //
    //                                                                      //
    //  7. CONSTANTS & EDGE MODEL                                           //
    //                                                                      //
    // ================================================================== //

    companion object {
        // Algorithm parameters (must match V3 / production Constants.java)
        const val ATTENUATION = 0.85
        const val CONVERGENCE_THRESHOLD = 0.0001
        const val MINIMUM_WEIGHT = 0.00001
        const val RIGOR = 0.5
        const val CUTOFF_TRUSTED_REPORTER = 0.1
        const val MAX_HOPS = 10

        // Neo4j schema
        @JvmField val NOSTR_USER: Label = Label.label("NostrUser")
        @JvmField val GRAPERANK_OBSERVER: Label = Label.label("GrapeRankObserver")
        @JvmField val FOLLOWS: RelationshipType = RelationshipType.withName("FOLLOWS")
        @JvmField val MUTES: RelationshipType = RelationshipType.withName("MUTES")
        @JvmField val REPORTS: RelationshipType = RelationshipType.withName("REPORTS")
        @JvmField val SOCIAL_EDGES = arrayOf(FOLLOWS, MUTES, REPORTS)
    }

    private fun rating(type: RelationshipType): Double = when (type) {
        FOLLOWS -> 1.0
        MUTES   -> -0.1
        REPORTS -> -0.1
        else    -> 0.0
    }

    private fun confidence(type: RelationshipType, obsElementId: String, sourceElementId: String): Double = when (type) {
        FOLLOWS -> if (obsElementId == sourceElementId) 0.5 else 0.03
        MUTES   -> 0.5
        REPORTS -> 0.5
        else    -> 0.0
    }

    private fun weightToConfidence(w: Double): Double =
        1.0 - exp(-w * -ln(RIGOR))
}
