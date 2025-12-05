package com.vitorpamplona.graperank.v2Stateful

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

sealed class Relationship(val src: User) {
    abstract fun confidence(observer: User): Double
    abstract fun rating(): Double
}

class Follow(src: User): Relationship(src) {
    override fun rating() = 1.0
    override fun confidence(observer: User): Double = if (observer == src) 0.08 else 0.04
}

class Mute(src: User): Relationship(src) {
    override fun rating() = 0.0
    override fun confidence(observer: User): Double = 0.4
}

class Report(src: User): Relationship(src) {
    override fun rating() =  -0.1
    override fun confidence(observer: User): Double = 0.4
}

/**
 * User class that resembles the reality of having to store
 * scores and to constantly recompute them
 */
class User() {
    // followers + mutedBy + reportedBy
    val incomingEdges = mutableListOf<Relationship>()
    // scores from this user's standpoint
    val scores = mutableMapOf<User, Double>(this to 1.0)

    context(graph: Graph)
    infix fun follows(user: User) {
        user.incomingEdges.add(Follow(this))
        graph.computeScoresFrom(this)
    }

    context(graph: Graph)
    infix fun reports(user: User) {
        user.incomingEdges.add(Report(this))
        graph.computeScoresFrom(this)
    }

    context(graph: Graph)
    infix fun mutes(user: User) {
        user.incomingEdges.add(Mute(this))
        graph.computeScoresFrom(this)
    }
}

/**
 * Stateful Graph chart to store users and observers
 * and recompute graperank when the graph changes
 *
 * The update algorithm walks through the entire graph
 * after any update
 */
class Graph() {
    val users: MutableList<User> = mutableListOf()
    val observers: MutableList<User> = mutableListOf()

    fun newUser(): User = User().also { users.add(it) }

    fun makeObserver(observer: User) {
        observers.add(observer)
        updateGrapevine(users, observer)
    }

   fun computeScoresFrom(user: User) {
        observers.forEach { observer ->
            updateGrapevine(users, observer)
        }
    }

    fun updateGrapevine(users: List<User>, observer: User) {
        do {
            var doAnotherRound = false

            for (targetUser in users) {
                if (targetUser == observer) continue

                val newScore = nodeScore(targetUser, observer)

                val currentScore = observer.scores.put(targetUser, newScore) ?: 0.0

                doAnotherRound = doAnotherRound || abs(newScore - currentScore) > 0.0001
            }
        } while (doAnotherRound)
    }

    fun nodeScore(target: User, observer: User): Double {
        var sumOfWeights = 0.0
        var sumOfWeightRating = 0.0

        for (edge in target.incomingEdges) {
            val currentScore = observer.scores[edge.src] ?: continue

            val weight = edge.confidence(observer) * currentScore
            sumOfWeights += weight
            sumOfWeightRating += weight * edge.rating()
        }

        return if (abs(sumOfWeights) < 0.00001) {
            0.0
        } else {
            max(weightToConfidence(sumOfWeights) * sumOfWeightRating / sumOfWeights, 0.0)
        }
    }

    fun weightToConfidence(weight: Double, rigor: Double = 0.5) = 1.0 - exp(-weight * -ln(rigor))
}