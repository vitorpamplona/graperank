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
 * Neo4j stored procedures implementing the V3 GrapeRank algorithm.
 *
 * Scores are stored as properties on NostrUser nodes, matching the
 * production format used by brainstorm_server:
 *
 *   node.influence_{observerPubkey}         — the V3 trust score
 *   node.hops_{observerPubkey}              — shortest FOLLOWS distance
 *   node.trusted_reporters_{observerPubkey} — # reporters with influence > 0.1
 */
class GrapeRankProcedures {

    companion object {
        const val ATTENUATION = 0.85
        const val CONVERGENCE_THRESHOLD = 0.0001
        const val MINIMUM_WEIGHT = 0.00001
        const val RIGOR = 0.5
        const val CUTOFF_TRUSTED_REPORTER = 0.1
        const val MAX_HOPS = 10

        @JvmField val NOSTR_USER: Label = Label.label("NostrUser")
        @JvmField val GRAPERANK_OBSERVER: Label = Label.label("GrapeRankObserver")

        @JvmField val FOLLOWS: RelationshipType = RelationshipType.withName("FOLLOWS")
        @JvmField val MUTES: RelationshipType = RelationshipType.withName("MUTES")
        @JvmField val REPORTS: RelationshipType = RelationshipType.withName("REPORTS")

        @JvmField val SOCIAL_EDGES = arrayOf(FOLLOWS, MUTES, REPORTS)
    }

    @Context
    @JvmField
    var tx: Transaction? = null

    // ------------------------------------------------------------------ //
    //  Result type                                                        //
    // ------------------------------------------------------------------ //

    class ScoreResult(
        @JvmField val observer: String,
        @JvmField val target: String,
        @JvmField val score: Double
    )

    // ------------------------------------------------------------------ //
    //  Per-observer context — caches everything for one computation pass   //
    // ------------------------------------------------------------------ //

    /**
     * Holds all mutable state for a single observer's score computation.
     * Created once per observer per procedure call; avoids repeated string
     * allocations and node lookups.
     */
    private inner class ObserverContext(
        val observer: Node,
        val obsPubkey: String
    ) {
        val obsElementId: String = observer.elementId

        // Pre-computed property names (avoid repeated string concat)
        val infProp: String = "influence_$obsPubkey"
        val hopProp: String = "hops_$obsPubkey"
        val trProp: String = "trusted_reporters_$obsPubkey"

        // Working score cache: elementId -> influence
        val scores: MutableMap<String, Double> = mutableMapOf(obsElementId to 1.0)

        // Scores as they were stored BEFORE this computation started.
        // Used to detect what actually changed vs. persisted state.
        val priorStored: MutableMap<String, Double> = mutableMapOf()

        // Nodes whose score changed relative to what was stored.
        val changedNodes: MutableSet<String> = mutableSetOf()

        // elementId -> Node cache to avoid getNodeByElementId lookups
        val nodeCache: MutableMap<String, Node> = mutableMapOf(obsElementId to observer)

        fun getStoredInfluence(node: Node): Double? {
            return if (node.hasProperty(infProp)) node.getProperty(infProp) as Double else null
        }

        fun cacheNode(node: Node): String {
            val eid = node.elementId
            nodeCache.putIfAbsent(eid, node)
            return eid
        }
    }

    // ------------------------------------------------------------------ //
    //  Edge model                                                         //
    // ------------------------------------------------------------------ //

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

    // ------------------------------------------------------------------ //
    //  Score math                                                         //
    // ------------------------------------------------------------------ //

    private fun weightToConfidence(w: Double): Double =
        1.0 - exp(-w * -ln(RIGOR))

    // ------------------------------------------------------------------ //
    //  Core V3 algorithm — single unified code path                       //
    // ------------------------------------------------------------------ //

    /**
     * Recompute [target]'s score from the observer's perspective.
     * Lazy-loads source scores from node properties on cache miss.
     * Tracks changes relative to the stored (persisted) values.
     */
    private fun recomputeScore(ctx: ObserverContext, target: Node): Boolean {
        val tid = ctx.cacheNode(target)
        if (ctx.obsElementId == tid) return false

        // Ensure the target's stored score is in the cache so the
        // convergence delta check compares against the right baseline.
        if (tid !in ctx.scores) {
            ctx.getStoredInfluence(target)?.let {
                ctx.scores[tid] = it
                ctx.priorStored.putIfAbsent(tid, it)
            }
        }
        ctx.priorStored.putIfAbsent(tid, ctx.scores[tid] ?: 0.0)

        var weights = 0.0
        var ratings = 0.0

        for (edge in target.getRelationships(Direction.INCOMING, *SOCIAL_EDGES)) {
            val source = edge.startNode
            val sid = ctx.cacheNode(source)

            val sourceScore = ctx.scores[sid] ?: run {
                val stored = ctx.getStoredInfluence(source) ?: return@run null
                ctx.scores[sid] = stored
                ctx.priorStored.putIfAbsent(sid, stored)
                stored
            } ?: continue

            val conf = confidence(edge.type, ctx.obsElementId, sid)
            val weight = conf * sourceScore * ATTENUATION

            weights += weight
            ratings += weight * rating(edge.type)
        }

        val newScore = if (abs(weights) < MINIMUM_WEIGHT) {
            0.0
        } else {
            (weightToConfidence(weights) * ratings / weights).coerceAtLeast(0.0)
        }

        val oldScore = ctx.scores.put(tid, newScore) ?: 0.0

        // Track for persistence: differs from what's stored on disk?
        // This is separate from convergence — a node with a tiny score
        // (below CONVERGENCE_THRESHOLD) still needs to be persisted.
        val prior = ctx.priorStored[tid] ?: 0.0
        if (abs(newScore - prior) > 1e-10 || (prior == 0.0 && newScore > 1e-10)) {
            ctx.changedNodes.add(tid)
        }

        // Convergence check: controls BFS propagation only.
        return abs(newScore - oldScore) > CONVERGENCE_THRESHOLD
    }

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

    private fun updateScores(ctx: ObserverContext, target: Node) {
        while (recomputeScore(ctx, target)) {
            propagateForward(ctx, target)
        }
    }

    // ------------------------------------------------------------------ //
    //  Hops — shortest FOLLOWS-path distance from observer                //
    // ------------------------------------------------------------------ //

    /**
     * Full BFS through FOLLOWS from observer.  Used by registerObserver
     * where we need hops for every scored node anyway.
     */
    private fun computeHopsBFS(
        observer: Node,
        targetIds: Set<String>
    ): Map<String, Int> {
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
     * For incremental updates: compute hops only for [targetIds] by reading
     * existing hops properties and only recomputing for truly new nodes.
     * For nodes that already had a hops value, we keep it unless this is a
     * FOLLOWS-edge change AND the node is directly downstream of the change
     * (in which case we do a targeted per-node reverse walk).
     */
    private fun computeHopsIncremental(
        ctx: ObserverContext,
        targetIds: Set<String>,
        isFollowChange: Boolean
    ): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val needCompute = mutableSetOf<String>()

        for (tid in targetIds) {
            if (tid == ctx.obsElementId) {
                result[tid] = 0
                continue
            }
            val node = ctx.nodeCache[tid] ?: tx!!.getNodeByElementId(tid)
            if (!isFollowChange && node.hasProperty(ctx.hopProp)) {
                // Mute/report change: hops can't change, keep stored value
                result[tid] = (node.getProperty(ctx.hopProp) as Double).toInt()
            } else if (node.hasProperty(ctx.hopProp)) {
                // Follow change but node had a prior value — recompute
                needCompute.add(tid)
            } else {
                // Brand new scored node — must compute
                needCompute.add(tid)
            }
        }

        if (needCompute.isEmpty()) return result

        // BFS from observer to find these specific nodes
        val found = computeHopsBFS(ctx.observer, needCompute)
        result.putAll(found)

        return result
    }

    // ------------------------------------------------------------------ //
    //  Trusted reporters                                                  //
    // ------------------------------------------------------------------ //

    private fun computeTrustedReporters(
        ctx: ObserverContext,
        target: Node
    ): Int {
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

    // ------------------------------------------------------------------ //
    //  Persistence — write node properties in production format           //
    // ------------------------------------------------------------------ //

    private fun persistScores(
        ctx: ObserverContext,
        changedNodeIds: Set<String>,
        hops: Map<String, Int>
    ): List<ScoreResult> {
        val results = mutableListOf<ScoreResult>()

        for (nodeId in changedNodeIds) {
            val newValue = ctx.scores[nodeId] ?: 0.0
            val targetNode = ctx.nodeCache[nodeId] ?: tx!!.getNodeByElementId(nodeId)
            val targetPubkey = targetNode.getProperty("pubkey") as String

            if (abs(newValue) < 1e-10) {
                targetNode.removeProperty(ctx.infProp)
                targetNode.removeProperty(ctx.hopProp)
                targetNode.removeProperty(ctx.trProp)
            } else {
                val h = (hops[nodeId] ?: 0).toDouble()
                val tr = computeTrustedReporters(ctx, targetNode).toDouble()

                targetNode.setProperty(ctx.infProp, newValue)
                targetNode.setProperty(ctx.hopProp, h)
                targetNode.setProperty(ctx.trProp, tr)

                results.add(ScoreResult(ctx.obsPubkey, targetPubkey, newValue))
            }
        }

        return results
    }

    // ------------------------------------------------------------------ //
    //  Observer lookup                                                    //
    // ------------------------------------------------------------------ //

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

    // ------------------------------------------------------------------ //
    //  Edge-change handler                                                //
    // ------------------------------------------------------------------ //

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
            allResults.addAll(persistScores(ctx, ctx.changedNodes, hops))
        }

        return allResults.stream()
    }

    // ------------------------------------------------------------------ //
    //  Public procedures                                                  //
    // ------------------------------------------------------------------ //

    @Procedure("graperank.v3.onFollow", mode = Mode.WRITE)
    @Description("Recomputes GrapeRank scores after a FOLLOWS edge is added.")
    fun onFollow(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = true)

    @Procedure("graperank.v3.onUnfollow", mode = Mode.WRITE)
    @Description("Recomputes GrapeRank scores after a FOLLOWS edge is removed.")
    fun onUnfollow(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = true)

    @Procedure("graperank.v3.onMute", mode = Mode.WRITE)
    @Description("Recomputes GrapeRank scores after a MUTES edge is added.")
    fun onMute(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = false)

    @Procedure("graperank.v3.onUnmute", mode = Mode.WRITE)
    @Description("Recomputes GrapeRank scores after a MUTES edge is removed.")
    fun onUnmute(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = false)

    @Procedure("graperank.v3.onReport", mode = Mode.WRITE)
    @Description("Recomputes GrapeRank scores after a REPORTS edge is added.")
    fun onReport(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target, isFollowChange = false)

    @Procedure("graperank.v3.registerObserver", mode = Mode.WRITE)
    @Description("Registers a NostrUser as a GrapeRank observer and computes all initial scores.")
    fun registerObserver(
        @Name("pubkey") pubkey: String
    ): Stream<ScoreResult> {
        val observer = tx!!.findNode(NOSTR_USER, "pubkey", pubkey)
            ?: return Stream.empty()

        observer.addLabel(GRAPERANK_OBSERVER)

        val ctx = ObserverContext(observer, pubkey)

        // Self-score
        observer.setProperty(ctx.infProp, 1.0)
        observer.setProperty(ctx.hopProp, 0.0)
        observer.setProperty(ctx.trProp, 0.0)

        propagateForward(ctx, observer)

        // Every node with a non-zero score that differs from stored is "changed"
        val scoredNodeIds = ctx.changedNodes
            .filter { it != ctx.obsElementId && (ctx.scores[it] ?: 0.0) > 1e-10 }
            .toSet()

        if (scoredNodeIds.isEmpty()) return Stream.empty()

        val hops = computeHopsBFS(observer, scoredNodeIds)

        return persistScores(ctx, scoredNodeIds, hops).stream()
    }

    @Procedure("graperank.v3.getScores", mode = Mode.READ)
    @Description("Returns all GrapeRank scores for an observer.")
    fun getScores(
        @Name("observer") pubkey: String
    ): Stream<ScoreResult> {
        val observer = tx!!.findNode(NOSTR_USER, "pubkey", pubkey)
            ?: return Stream.empty()

        val infProp = "influence_$pubkey"
        val results = mutableListOf<ScoreResult>()

        tx!!.findNodes(NOSTR_USER).use { iter ->
            while (iter.hasNext()) {
                val node = iter.next()
                if (node.hasProperty(infProp)) {
                    val score = node.getProperty(infProp) as Double
                    if (score > 1e-10) {
                        results.add(
                            ScoreResult(
                                pubkey,
                                node.getProperty("pubkey") as String,
                                score
                            )
                        )
                    }
                }
            }
        }

        return results.stream()
    }
}
