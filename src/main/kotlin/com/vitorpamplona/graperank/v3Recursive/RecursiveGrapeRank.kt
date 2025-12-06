package com.vitorpamplona.graperank.v3Recursive

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

sealed class Relationship(val src: User) {
    abstract fun conf(observer: User): Double
    abstract fun rating(): Double
}

class Follow(src: User): Relationship(src) {
    override fun rating() = 1.0
    override fun conf(observer: User): Double =
        if (observer == src) 0.08 else 0.04
}

class Mute(src: User): Relationship(src) {
    override fun rating() = 0.0
    override fun conf(observer: User): Double = 0.4
}

class Report(src: User): Relationship(src) {
    override fun rating() =  -0.1
    override fun conf(observer: User): Double = 0.4
}

/**
 * User class that resembles the reality of
 * having to store scores and to constantly
 * recompute them while offering
 * an index to all neighborhood nodes.
 */
open class User() {
    // followers, mutedBy and reportedBy
    val incomingEdges = mutableListOf<Relationship>()
    // my follows, mutes and reports
    val outgoingEdges = mutableListOf<User>()
    // scores from this user's standpoint
    val scores = mutableMapOf<User, Double>(this to 1.0)

    context(graph: Graph)
    infix fun follows(user: User) {
        outgoingEdges.add(user)
        user.incomingEdges.add(Follow(this))
        graph.computeScoresFrom(user)
    }

    context(graph: Graph)
    infix fun reports(user: User) {
        outgoingEdges.add(user)
        user.incomingEdges.add(Report(this))
        graph.computeScoresFrom(user)
    }

    context(graph: Graph)
    infix fun mutes(user: User) {
        outgoingEdges.add(user)
        user.incomingEdges.add(Mute(this))
        graph.computeScoresFrom(user)
    }
}

/**
 * Stateful Graph to store users and observers
 * and recompute graperank when it changes
 *
 * The update algorithm propagates forward
 * until no more changes are found
 */
class Graph() {
    val users  = mutableListOf<User>()
    val observers = mutableListOf<User>()

    fun newUser() = User().also { users += it }

    fun makeObserver(observer: User) {
        observers.add(observer)
        // this will create the entire graph from the observer's point
        updateScores(observer, observer)
    }

    fun computeScoresFrom(user: User) {
        observers.forEach { observer ->
            updateScores(user, observer)
        }
    }

    fun updateScores(target: User, observer: User) {
        if (target == observer) {
            // special case
            // since the score is always 1, it never changes
            // so the algo should stop when the outgoing edges
            // stop changing.
            for (newTarget in target.outgoingEdges) {
                updateScores(newTarget, observer)
            }
            return
        }

        do {
            val newScore = score(target, observer)
            val currentScore = observer.scores.put(target, newScore) ?: 0.0
            val hasChanged = abs(newScore - currentScore) > 0.0001

            if (hasChanged) {
                // the node's new scores will affect everyone the node follows/mutes
                for (newTarget in target.outgoingEdges) {
                    updateScores(newTarget, observer)
                }
            }
        } while (hasChanged)
    }

    fun score(target: User, observer: User): Double {
        var sumOfWeights = 0.0
        var sumOfWeightRating = 0.0

        for (edge in target.incomingEdges) {
            val score = observer.scores[edge.src] ?: continue
            val weight = edge.conf(observer) * score

            sumOfWeights += weight
            sumOfWeightRating += weight * edge.rating()
        }

        return if (abs(sumOfWeights) < 0.00001) {
            0.0
        } else {
            max(conf(sumOfWeights) * sumOfWeightRating / sumOfWeights, 0.0)
        }
    }

    fun conf(w: Double, rigor: Double = 0.5) =
        1.0 - exp(-w * -ln(rigor))
}



