package com.vitorpamplona.graperank.v3Recursive

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

sealed class Relationship(val src: User) {
    abstract fun conf(observer: User): Double
    abstract fun rating(): Double
}

class Follow(src: User): Relationship(src) {
    override fun rating() = 1.0
    override fun conf(observer: User) =
        if (observer == src) 0.08 else 0.04
}

class Mute(src: User): Relationship(src) {
    override fun rating() = 0.0
    override fun conf(observer: User) = 0.4
}

class Report(src: User): Relationship(src) {
    override fun rating() = -0.1
    override fun conf(observer: User)= 0.4
}

/**
 * User class that resembles the reality of
 * having to store scores and to constantly
 * recompute them while offering
 * an index to all neighborhood nodes.
 */
class User() {
    // followers, mutedBy and reportedBy
    val inEdges = mutableListOf<Relationship>()

    // my follows, mutes and reports
    val outEdges = hashSetOf<User>()

    // scores from this user's standpoint
    val scores = mutableMapOf<User, Double>(
        this to 1.0
    )

    context(graph: Graph)
    infix fun follows(user: User) {
        outEdges.add(user)
        user.inEdges.add(Follow(this))
        graph.computeScoresFrom(user)
    }

    context(graph: Graph)
    infix fun reports(user: User) {
        outEdges.add(user)
        user.inEdges.add(Report(this))
        graph.computeScoresFrom(user)
    }

    context(graph: Graph)
    infix fun mutes(user: User) {
        outEdges.add(user)
        user.inEdges.add(Mute(this))
        graph.computeScoresFrom(user)
    }

    fun score(edge: Relationship) =
        scores[edge.src]

    fun score(user: User, value: Double) =
        scores.put(user, value)
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
        // the score of the observer is always
        // 1, so propagate from all outEdges
        processOutEdges(observer, observer)
    }

    fun computeScoresFrom(user: User) {
        observers.forEach { observer ->
            updateScores(user, observer)
        }
    }

    fun updateScores(
        target: User,
        observer: User
    ) {
        while (observer.newScore(target)) {
            processOutEdges(target, observer)
        }
    }

    fun processOutEdges(
        target: User,
        observer: User
    ) {
        val recompute = mutableSetOf<User>()
        recompute.addAll(target.outEdges)
        while (recompute.isNotEmpty()) {
            val next = recompute.first()
            if (observer.newScore(next)) {
                recompute.addAll(next.outEdges)
            }
            // always remove because it can be
            // part of outgoing edges
            recompute.remove(next)
        }
    }

    /**
     * Computes a new score and returns if
     * it is different from the past
     */
    fun User.newScore(target: User): Boolean {
        if (target == this) return false

        var weights = 0.0
        var ratings = 0.0

        for (edge in target.inEdges) {
            val sc = score(edge) ?: continue
            val weight = edge.conf(this) * sc

            weights += weight
            ratings += weight * edge.rating()
        }

        val new = if (abs(weights) < 0.00001) {
            0.0
        } else {
            val conf = conf(weights)
            val score = conf * ratings/weights
            score.coerceAtLeast(0.0)
        }

        val curr = score(target, new) ?: 0.0
        return abs(new - curr) > 0.0001
    }

    fun conf(w: Double, rigor: Double = 0.5) =
        1.0 - exp(-w * -ln(rigor))
}



