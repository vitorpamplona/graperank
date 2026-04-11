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
    //  Property name helpers (match production format)                     //
    // ------------------------------------------------------------------ //

    private fun influenceProp(obsPubkey: String) = "influence_$obsPubkey"
    private fun hopsProp(obsPubkey: String) = "hops_$obsPubkey"
    private fun trustedReportersProp(obsPubkey: String) = "trusted_reporters_$obsPubkey"

    private fun getStoredInfluence(node: Node, obsPubkey: String): Double? {
        val prop = influenceProp(obsPubkey)
        return if (node.hasProperty(prop)) node.getProperty(prop) as Double else null
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

    private fun confidence(type: RelationshipType, observer: Node, source: Node): Double = when (type) {
        FOLLOWS -> if (observer.elementId == source.elementId) 0.5 else 0.03
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
    //  Core V3 algorithm (lazy-loads existing scores from node properties) //
    // ------------------------------------------------------------------ //

    /**
     * Recompute [target]'s score from [observer]'s perspective.
     *
     * Source scores are resolved from the [scores] cache first, falling
     * back to the persisted `influence_{observer}` node property.  This
     * avoids loading all scores up front.
     */
    private fun recomputeScore(
        observer: Node,
        obsPubkey: String,
        target: Node,
        scores: MutableMap<String, Double>
    ): Boolean {
        if (observer.elementId == target.elementId) return false

        // Make sure the target's current stored score is in the cache
        // so the delta check at the end is correct.
        if (target.elementId !in scores) {
            getStoredInfluence(target, obsPubkey)?.let {
                scores[target.elementId] = it
            }
        }

        var weights = 0.0
        var ratings = 0.0

        for (edge in target.getRelationships(Direction.INCOMING, *SOCIAL_EDGES)) {
            val source = edge.startNode
            val sid = source.elementId

            // Lazy-load: cache first, then node property
            val sourceScore = scores[sid] ?: run {
                val stored = getStoredInfluence(source, obsPubkey) ?: return@run null
                scores[sid] = stored
                stored
            } ?: continue

            val conf = confidence(edge.type, observer, source)
            val weight = conf * sourceScore * ATTENUATION

            weights += weight
            ratings += weight * rating(edge.type)
        }

        val newScore = if (abs(weights) < MINIMUM_WEIGHT) {
            0.0
        } else {
            (weightToConfidence(weights) * ratings / weights).coerceAtLeast(0.0)
        }

        val oldScore = scores.put(target.elementId, newScore) ?: 0.0
        return abs(newScore - oldScore) > CONVERGENCE_THRESHOLD
    }

    private fun propagateForward(
        observer: Node,
        obsPubkey: String,
        target: Node,
        scores: MutableMap<String, Double>
    ) {
        val queue = mutableSetOf<Node>()
        for (edge in target.getRelationships(Direction.OUTGOING, *SOCIAL_EDGES)) {
            queue.add(edge.endNode)
        }
        while (queue.isNotEmpty()) {
            val next = queue.first()
            queue.remove(next)
            if (recomputeScore(observer, obsPubkey, next, scores)) {
                for (edge in next.getRelationships(Direction.OUTGOING, *SOCIAL_EDGES)) {
                    queue.add(edge.endNode)
                }
            }
        }
    }

    private fun updateScores(
        observer: Node,
        obsPubkey: String,
        target: Node,
        scores: MutableMap<String, Double>
    ) {
        while (recomputeScore(observer, obsPubkey, target, scores)) {
            propagateForward(observer, obsPubkey, target, scores)
        }
    }

    // ------------------------------------------------------------------ //
    //  Hops — shortest FOLLOWS-path distance from observer                //
    // ------------------------------------------------------------------ //

    /**
     * BFS through FOLLOWS edges from [observer].  Returns the minimum
     * hop distance for every node in [targetIds] that is reachable
     * within 10 hops.
     */
    private fun computeHops(
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

        while (frontier.isNotEmpty() && depth < 10 && remaining.isNotEmpty()) {
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

    // ------------------------------------------------------------------ //
    //  Trusted reporters                                                  //
    // ------------------------------------------------------------------ //

    /**
     * Count incoming REPORTS edges whose source has
     * influence > [CUTOFF_TRUSTED_REPORTER] from this observer.
     */
    private fun computeTrustedReporters(
        obsPubkey: String,
        target: Node,
        scores: Map<String, Double>
    ): Int {
        var count = 0
        for (edge in target.getRelationships(Direction.INCOMING, REPORTS)) {
            val reporter = edge.startNode
            val inf = scores[reporter.elementId]
                ?: getStoredInfluence(reporter, obsPubkey)
                ?: 0.0
            if (inf > CUTOFF_TRUSTED_REPORTER) count++
        }
        return count
    }

    // ------------------------------------------------------------------ //
    //  Persistence — write node properties in production format           //
    // ------------------------------------------------------------------ //

    /**
     * For every node whose score changed, write the three properties
     * that the production system expects:
     *
     *   influence_{observer}, hops_{observer}, trusted_reporters_{observer}
     */
    private fun persistScores(
        observer: Node,
        obsPubkey: String,
        scores: Map<String, Double>,
        changedNodeIds: Set<String>,
        hops: Map<String, Int>
    ): List<ScoreResult> {
        val results = mutableListOf<ScoreResult>()

        for (nodeId in changedNodeIds) {
            val newValue = scores[nodeId] ?: 0.0
            val targetNode = tx!!.getNodeByElementId(nodeId)
            val targetPubkey = targetNode.getProperty("pubkey") as String

            if (abs(newValue) < 1e-10) {
                targetNode.removeProperty(influenceProp(obsPubkey))
                targetNode.removeProperty(hopsProp(obsPubkey))
                targetNode.removeProperty(trustedReportersProp(obsPubkey))
            } else {
                val h = (hops[nodeId] ?: 0).toDouble()
                val tr = computeTrustedReporters(obsPubkey, targetNode, scores).toDouble()

                targetNode.setProperty(influenceProp(obsPubkey), newValue)
                targetNode.setProperty(hopsProp(obsPubkey), h)
                targetNode.setProperty(trustedReportersProp(obsPubkey), tr)

                results.add(ScoreResult(obsPubkey, targetPubkey, newValue))
            }
        }

        return results
    }

    // ------------------------------------------------------------------ //
    //  Observer lookup                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Find observers affected by a change at [sourceNode].
     *
     * An observer is affected only if they already have an
     * `influence_{observer}` property on [sourceNode] — meaning the
     * source is visible in their trust network.
     */
    private fun findAffectedObservers(sourceNode: Node): List<Pair<Node, String>> {
        val observers = mutableListOf<Pair<Node, String>>()

        tx!!.findNodes(GRAPERANK_OBSERVER).use { iter ->
            while (iter.hasNext()) {
                val obs = iter.next()
                val pk = obs.getProperty("pubkey") as String
                if (sourceNode.hasProperty(influenceProp(pk))) {
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
            val scores = mutableMapOf(observer.elementId to 1.0)

            // Snapshot which nodes had scores before BFS,
            // so we can detect what actually changed.
            val priorValues = mutableMapOf<String, Double>()

            // Wrap recomputeScore to track changes
            val changedNodes = mutableSetOf<String>()

            // Run BFS; recomputeScore lazy-loads existing scores and
            // puts new ones into `scores`. We detect changes by comparing.
            updateScoresTracked(observer, obsPubkey, targetNode, scores, priorValues, changedNodes)

            if (changedNodes.isEmpty()) continue

            // Compute hops only for changed nodes, only if this is a FOLLOWS change
            val hops = if (isFollowChange) {
                computeHops(observer, changedNodes)
            } else {
                // For mute/report changes, read existing hops
                val h = mutableMapOf<String, Int>()
                for (nid in changedNodes) {
                    val node = tx!!.getNodeByElementId(nid)
                    val prop = hopsProp(obsPubkey)
                    h[nid] = if (node.hasProperty(prop)) (node.getProperty(prop) as Double).toInt() else 0
                }
                h
            }

            allResults.addAll(persistScores(observer, obsPubkey, scores, changedNodes, hops))
        }

        return allResults.stream()
    }

    /**
     * Like [updateScores] but tracks which nodes actually changed
     * by recording their prior values before overwrite.
     */
    private fun updateScoresTracked(
        observer: Node,
        obsPubkey: String,
        target: Node,
        scores: MutableMap<String, Double>,
        priorValues: MutableMap<String, Double>,
        changedNodes: MutableSet<String>
    ) {
        while (recomputeScoreTracked(observer, obsPubkey, target, scores, priorValues, changedNodes)) {
            propagateForwardTracked(observer, obsPubkey, target, scores, priorValues, changedNodes)
        }
    }

    private fun recomputeScoreTracked(
        observer: Node,
        obsPubkey: String,
        target: Node,
        scores: MutableMap<String, Double>,
        priorValues: MutableMap<String, Double>,
        changedNodes: MutableSet<String>
    ): Boolean {
        if (observer.elementId == target.elementId) return false
        val tid = target.elementId

        // Lazy-load the target's stored score into the cache
        if (tid !in scores) {
            val stored = getStoredInfluence(target, obsPubkey)
            if (stored != null) {
                scores[tid] = stored
                priorValues.putIfAbsent(tid, stored)
            }
        }
        priorValues.putIfAbsent(tid, scores[tid] ?: 0.0)

        var weights = 0.0
        var ratings = 0.0

        for (edge in target.getRelationships(Direction.INCOMING, *SOCIAL_EDGES)) {
            val source = edge.startNode
            val sid = source.elementId

            val sourceScore = scores[sid] ?: run {
                val stored = getStoredInfluence(source, obsPubkey) ?: return@run null
                scores[sid] = stored
                priorValues.putIfAbsent(sid, stored)
                stored
            } ?: continue

            val conf = confidence(edge.type, observer, source)
            val weight = conf * sourceScore * ATTENUATION

            weights += weight
            ratings += weight * rating(edge.type)
        }

        val newScore = if (abs(weights) < MINIMUM_WEIGHT) {
            0.0
        } else {
            (weightToConfidence(weights) * ratings / weights).coerceAtLeast(0.0)
        }

        val oldScore = scores.put(tid, newScore) ?: 0.0
        val changed = abs(newScore - oldScore) > CONVERGENCE_THRESHOLD
        if (changed) {
            // Track as changed relative to the STORED value (not just the cache)
            val prior = priorValues[tid] ?: 0.0
            if (abs(newScore - prior) > 1e-10 || (prior == 0.0 && newScore > 1e-10)) {
                changedNodes.add(tid)
            }
        }
        return changed
    }

    private fun propagateForwardTracked(
        observer: Node,
        obsPubkey: String,
        target: Node,
        scores: MutableMap<String, Double>,
        priorValues: MutableMap<String, Double>,
        changedNodes: MutableSet<String>
    ) {
        val queue = mutableSetOf<Node>()
        for (edge in target.getRelationships(Direction.OUTGOING, *SOCIAL_EDGES)) {
            queue.add(edge.endNode)
        }
        while (queue.isNotEmpty()) {
            val next = queue.first()
            queue.remove(next)
            if (recomputeScoreTracked(observer, obsPubkey, next, scores, priorValues, changedNodes)) {
                for (edge in next.getRelationships(Direction.OUTGOING, *SOCIAL_EDGES)) {
                    queue.add(edge.endNode)
                }
            }
        }
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

        // Self-score
        observer.setProperty(influenceProp(pubkey), 1.0)
        observer.setProperty(hopsProp(pubkey), 0.0)
        observer.setProperty(trustedReportersProp(pubkey), 0.0)

        val scores = mutableMapOf(observer.elementId to 1.0)
        propagateForward(observer, pubkey, observer, scores)

        // Collect all scored nodes (exclude observer)
        val scoredNodeIds = scores.keys.filter { it != observer.elementId && (scores[it] ?: 0.0) > 1e-10 }.toSet()

        if (scoredNodeIds.isEmpty()) return Stream.empty()

        val hops = computeHops(observer, scoredNodeIds)

        return persistScores(observer, pubkey, scores, scoredNodeIds, hops).stream()
    }

    @Procedure("graperank.v3.getScores", mode = Mode.READ)
    @Description("Returns all GrapeRank scores for an observer.")
    fun getScores(
        @Name("observer") pubkey: String
    ): Stream<ScoreResult> {
        val observer = tx!!.findNode(NOSTR_USER, "pubkey", pubkey)
            ?: return Stream.empty()

        val prefix = "influence_$pubkey"
        val results = mutableListOf<ScoreResult>()

        // Scan all NostrUser nodes for the observer's influence property.
        // This is O(nodes) but getScores is a read-only query, not a hot path.
        tx!!.findNodes(NOSTR_USER).use { iter ->
            while (iter.hasNext()) {
                val node = iter.next()
                if (node.hasProperty(prefix)) {
                    val score = node.getProperty(prefix) as Double
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
