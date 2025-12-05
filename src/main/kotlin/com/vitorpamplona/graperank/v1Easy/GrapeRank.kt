package com.vitorpamplona.graperank.v1Easy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.exp
import kotlin.math.ln

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

class User {
    val incomingEdges = mutableListOf<Relationship>()

    infix fun follows(user: User) = user.incomingEdges.add(Follow(this))
    infix fun reports(user: User) = user.incomingEdges.add(Report(this))
    infix fun mutes(user: User) = user.incomingEdges.add(Mute(this))
}

fun weightToConfidence(weight: Double, rigor: Double = 0.5) = 1.0 - exp(-weight * -ln(rigor))

fun grapeRank(users: List<User>, observer: User) =
    mutableMapOf(observer to 1.0).apply {
        do {
            var doAnotherRound = false

            for (targetUser in users) {
                if (targetUser == observer) continue

                var sumOfWeights = 0.0
                var sumOfWeightRating = 0.0

                for (edge in targetUser.incomingEdges) {
                    val currentScore = this[edge.src] ?: continue

                    val weight = edge.confidence(observer) * currentScore
                    sumOfWeights += weight
                    sumOfWeightRating += weight * edge.rating()
                }

                val newScore = if (abs(sumOfWeights) < 0.00001) {
                    0.0
                } else {
                    max(weightToConfidence(sumOfWeights) * sumOfWeightRating / sumOfWeights, 0.0)
                }

                val currentScore = this.put(targetUser, newScore) ?: 0.0

                doAnotherRound = doAnotherRound || abs(newScore - currentScore) > 0.0001
            }
        } while (doAnotherRound)
    }