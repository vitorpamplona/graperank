package com.vitorpamplona.graperank.neo4j

import org.neo4j.graphdb.Direction
import org.neo4j.graphdb.Label
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.Relationship
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
 * GrapeRank computes observer-centric trust scores by propagating
 * weighted signals through FOLLOWS / MUTES / REPORTS edges.
 *
 * Scores are stored as (:GrapeRankObserver)-[:GRAPERANK_SCORE {value}]->(:NostrUser)
 * relationships and updated incrementally on each graph mutation.
 *
 * ## Usage from Cypher / Python
 *
 *   // One-time setup: register an observer
 *   CALL graperank.v3.registerObserver('pubkey_hex')
 *
 *   // After adding a FOLLOWS edge:
 *   CALL graperank.v3.onFollow('source_hex', 'target_hex')
 *
 *   // After removing a FOLLOWS edge:
 *   CALL graperank.v3.onUnfollow('source_hex', 'target_hex')
 *
 *   // Read scores:
 *   CALL graperank.v3.getScores('observer_hex')
 */
class GrapeRankProcedures {

    companion object {
        const val ATTENUATION = 0.85
        const val CONVERGENCE_THRESHOLD = 0.0001
        const val MINIMUM_WEIGHT = 0.00001
        const val RIGOR = 0.5

        @JvmField val NOSTR_USER: Label = Label.label("NostrUser")
        @JvmField val GRAPERANK_OBSERVER: Label = Label.label("GrapeRankObserver")

        @JvmField val FOLLOWS: RelationshipType = RelationshipType.withName("FOLLOWS")
        @JvmField val MUTES: RelationshipType = RelationshipType.withName("MUTES")
        @JvmField val REPORTS: RelationshipType = RelationshipType.withName("REPORTS")
        @JvmField val GRAPERANK_SCORE: RelationshipType = RelationshipType.withName("GRAPERANK_SCORE")

        @JvmField val SOCIAL_EDGES = arrayOf(FOLLOWS, MUTES, REPORTS)
    }

    // Injected by Neo4j at procedure invocation time
    @Context
    @JvmField
    var tx: Transaction? = null

    // ------------------------------------------------------------------ //
    //  Result type returned by all procedures                             //
    // ------------------------------------------------------------------ //

    class ScoreResult(
        @JvmField val observer: String,
        @JvmField val target: String,
        @JvmField val score: Double
    )

    // ------------------------------------------------------------------ //
    //  Edge model — mirrors V3's Relationship sealed class                //
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
    //  Core V3 algorithm                                                  //
    // ------------------------------------------------------------------ //

    /**
     * Recompute [target]'s score from [observer]'s perspective using the
     * score cache. Stores the result and returns true if the score changed
     * by more than [CONVERGENCE_THRESHOLD].
     */
    private fun recomputeScore(
        observer: Node,
        target: Node,
        scores: MutableMap<String, Double>
    ): Boolean {
        if (observer.elementId == target.elementId) return false

        var weights = 0.0
        var ratings = 0.0

        for (edge in target.getRelationships(Direction.INCOMING, *SOCIAL_EDGES)) {
            val source = edge.startNode
            val sourceScore = scores[source.elementId] ?: continue

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

    /**
     * BFS propagation: starting from [target]'s outgoing neighbours,
     * recompute scores and keep walking only along paths where scores
     * actually change.
     */
    private fun propagateForward(
        observer: Node,
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

            if (recomputeScore(observer, next, scores)) {
                for (edge in next.getRelationships(Direction.OUTGOING, *SOCIAL_EDGES)) {
                    queue.add(edge.endNode)
                }
            }
        }
    }

    /**
     * Outer convergence loop: recompute [target] and propagate forward
     * until [target]'s score stabilises (handles cycles).
     */
    private fun updateScores(
        observer: Node,
        target: Node,
        scores: MutableMap<String, Double>
    ) {
        while (recomputeScore(observer, target, scores)) {
            propagateForward(observer, target, scores)
        }
    }

    // ------------------------------------------------------------------ //
    //  Score persistence                                                  //
    // ------------------------------------------------------------------ //

    /**
     * Load all existing GRAPERANK_SCORE relationships for [observer]
     * into a fast in-memory map (nodeId -> score).
     */
    private fun loadScores(observer: Node): MutableMap<String, Double> {
        val scores = mutableMapOf(observer.elementId to 1.0)
        for (rel in observer.getRelationships(Direction.OUTGOING, GRAPERANK_SCORE)) {
            scores[rel.endNode.elementId] = rel.getProperty("value") as Double
        }
        return scores
    }

    /**
     * Diff [oldScores] vs [newScores] and write only the changed entries
     * as GRAPERANK_SCORE relationships.  Returns the list of changes.
     */
    private fun persistScores(
        observer: Node,
        oldScores: Map<String, Double>,
        newScores: Map<String, Double>
    ): List<ScoreResult> {
        val observerPubkey = observer.getProperty("pubkey") as String
        val results = mutableListOf<ScoreResult>()

        // Build index of existing score relationships for O(1) lookup
        val existingRels = mutableMapOf<String, Relationship>()
        for (rel in observer.getRelationships(Direction.OUTGOING, GRAPERANK_SCORE)) {
            existingRels[rel.endNode.elementId] = rel
        }

        for ((nodeId, newValue) in newScores) {
            if (nodeId == observer.elementId) continue

            val oldValue = oldScores[nodeId]

            // Skip if the score existed before and hasn't moved
            if (oldValue != null && abs(newValue - oldValue) < 1e-10) continue
            // Skip negligible new scores
            if (oldValue == null && abs(newValue) < 1e-10) continue

            val existingRel = existingRels[nodeId]

            if (abs(newValue) < 1e-10) {
                // Score collapsed to zero — remove relationship
                existingRel?.delete()
            } else {
                val targetNode = existingRel?.endNode ?: tx!!.getNodeByElementId(nodeId)
                if (existingRel != null) {
                    existingRel.setProperty("value", newValue)
                } else {
                    observer.createRelationshipTo(targetNode, GRAPERANK_SCORE)
                        .setProperty("value", newValue)
                }
                results.add(
                    ScoreResult(
                        observerPubkey,
                        targetNode.getProperty("pubkey") as String,
                        newValue
                    )
                )
            }
        }

        return results
    }

    // ------------------------------------------------------------------ //
    //  Observer management                                                //
    // ------------------------------------------------------------------ //

    /**
     * Find observers affected by a change whose edge source is [sourceNode].
     *
     * An observer is affected **only if** they already have a
     * GRAPERANK_SCORE relationship pointing to [sourceNode].  If no such
     * relationship exists the source is invisible to that observer and
     * the change cannot alter any of their scores.
     */
    private fun findAffectedObservers(sourceNode: Node): List<Node> {
        val observers = mutableListOf<Node>()

        // Any observer who already scored the source
        for (rel in sourceNode.getRelationships(Direction.INCOMING, GRAPERANK_SCORE)) {
            val candidate = rel.startNode
            if (candidate.hasLabel(GRAPERANK_OBSERVER)) {
                observers.add(candidate)
            }
        }

        // The source itself might be an observer (self-score = 1.0 is implicit)
        if (sourceNode.hasLabel(GRAPERANK_OBSERVER) &&
            observers.none { it.elementId == sourceNode.elementId }
        ) {
            observers.add(sourceNode)
        }

        return observers
    }

    // ------------------------------------------------------------------ //
    //  Generic edge-change handler                                        //
    // ------------------------------------------------------------------ //

    /**
     * After a social edge (FOLLOWS/MUTES/REPORTS) has been added or
     * removed between [sourcePubkey] and [targetPubkey], recompute
     * scores for every affected observer.
     */
    private fun onEdgeChanged(
        sourcePubkey: String,
        targetPubkey: String
    ): Stream<ScoreResult> {
        val sourceNode = tx!!.findNode(NOSTR_USER, "pubkey", sourcePubkey)
            ?: return Stream.empty()
        val targetNode = tx!!.findNode(NOSTR_USER, "pubkey", targetPubkey)
            ?: return Stream.empty()

        val affected = findAffectedObservers(sourceNode)
        if (affected.isEmpty()) return Stream.empty()

        val allResults = mutableListOf<ScoreResult>()

        for (observer in affected) {
            val oldScores = loadScores(observer)
            val newScores = oldScores.toMutableMap()

            updateScores(observer, targetNode, newScores)

            allResults.addAll(persistScores(observer, oldScores, newScores))
        }

        return allResults.stream()
    }

    // ------------------------------------------------------------------ //
    //  Public procedures                                                  //
    // ------------------------------------------------------------------ //

    @Procedure("graperank.v3.onFollow", mode = Mode.WRITE)
    @Description(
        "Recomputes GrapeRank scores after a FOLLOWS edge is added. " +
        "Call AFTER creating the FOLLOWS relationship."
    )
    fun onFollow(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target)

    @Procedure("graperank.v3.onUnfollow", mode = Mode.WRITE)
    @Description(
        "Recomputes GrapeRank scores after a FOLLOWS edge is removed. " +
        "Call AFTER deleting the FOLLOWS relationship."
    )
    fun onUnfollow(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target)

    @Procedure("graperank.v3.onMute", mode = Mode.WRITE)
    @Description(
        "Recomputes GrapeRank scores after a MUTES edge is added."
    )
    fun onMute(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target)

    @Procedure("graperank.v3.onUnmute", mode = Mode.WRITE)
    @Description(
        "Recomputes GrapeRank scores after a MUTES edge is removed."
    )
    fun onUnmute(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target)

    @Procedure("graperank.v3.onReport", mode = Mode.WRITE)
    @Description(
        "Recomputes GrapeRank scores after a REPORTS edge is added."
    )
    fun onReport(
        @Name("source") source: String,
        @Name("target") target: String
    ): Stream<ScoreResult> = onEdgeChanged(source, target)

    @Procedure("graperank.v3.registerObserver", mode = Mode.WRITE)
    @Description(
        "Registers a NostrUser as a GrapeRank observer, computing " +
        "initial trust scores for every reachable user."
    )
    fun registerObserver(
        @Name("pubkey") pubkey: String
    ): Stream<ScoreResult> {
        val observer = tx!!.findNode(NOSTR_USER, "pubkey", pubkey)
            ?: return Stream.empty()

        observer.addLabel(GRAPERANK_OBSERVER)

        val scores = mutableMapOf(observer.elementId to 1.0)
        propagateForward(observer, observer, scores)

        return persistScores(observer, emptyMap(), scores).stream()
    }

    @Procedure("graperank.v3.getScores", mode = Mode.READ)
    @Description(
        "Returns all GrapeRank scores for an observer."
    )
    fun getScores(
        @Name("observer") pubkey: String
    ): Stream<ScoreResult> {
        val observer = tx!!.findNode(NOSTR_USER, "pubkey", pubkey)
            ?: return Stream.empty()
        val observerPubkey = observer.getProperty("pubkey") as String

        return observer.getRelationships(Direction.OUTGOING, GRAPERANK_SCORE)
            .map { rel ->
                ScoreResult(
                    observerPubkey,
                    rel.endNode.getProperty("pubkey") as String,
                    rel.getProperty("value") as Double
                )
            }
            .stream()
    }
}
