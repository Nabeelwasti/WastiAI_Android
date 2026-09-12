package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Wasti's self-enhancement decision layer.
 *
 * It does not pretend that a generated idea is a working capability. Instead it turns
 * an observed problem into a bounded evolution loop:
 * OBSERVE -> RESEARCH -> COMBINE/ADAPT/INVENT -> PROBE -> OBSERVE -> VERIFY -> PROMOTE.
 *
 * Research results and real execution/verification evidence are inputs. The engine never
 * promotes an idea merely because it sounds plausible, and it never treats model output as
 * proof of execution.
 */
object WastiSelfEvolutionEngine {
    enum class Move { RESEARCH, COMBINE, ADAPT, INVENT, PROBE, VERIFY, WAIT_FOR_USER_CAPABILITY }

    data class Strategy(
        val id: String,
        val description: String,
        val source: String,
        val prerequisites: Set<String> = emptySet(),
        val evidence: List<String> = emptyList()
    )

    data class Problem(
        val taskId: String,
        val objective: String,
        val failureOrGap: String,
        val currentStrategies: List<Strategy> = emptyList(),
        val availableCapabilities: Set<String> = emptySet(),
        val observations: List<String> = emptyList(),
        val constraints: Set<String> = emptySet()
    )

    data class EvolutionPlan(
        val taskId: String,
        val moves: List<Move>,
        val candidates: List<Strategy>,
        val rationale: String
    )

    private val outcomes = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Boolean>>()

    fun plan(problem: Problem): EvolutionPlan {
        val candidates = linkedMapOf<String, Strategy>()
        problem.currentStrategies.forEach { candidates[it.id] = it }

        // Research is always a legitimate next move when the current route is blocked or
        // the evidence is insufficient. An external research adapter supplies the actual data.
        val moves = mutableListOf(Move.RESEARCH)

        val strategies = problem.currentStrategies
        for (leftIndex in strategies.indices) {
            for (rightIndex in leftIndex + 1 until strategies.size) {
                val left = strategies[leftIndex]
                val right = strategies[rightIndex]
                val combinedId = "combine:${left.id}+${right.id}"
                candidates.putIfAbsent(
                    combinedId,
                    Strategy(
                        id = combinedId,
                        description = "Combine '${left.description}' with '${right.description}' and probe the resulting route.",
                        source = "COMBINED_EXISTING_STRATEGIES",
                        prerequisites = left.prerequisites + right.prerequisites,
                        evidence = left.evidence + right.evidence
                    )
                )
            }
        }
        if (strategies.size >= 2) moves += Move.COMBINE

        if (problem.observations.isNotEmpty()) {
            val adaptedId = "adapt:${problem.taskId}:${problem.failureOrGap.hashCode()}"
            candidates.putIfAbsent(
                adaptedId,
                Strategy(
                    id = adaptedId,
                    description = "Adapt the strongest existing route using the latest observed failure/gap instead of repeating the same attempt.",
                    source = "ADAPTED_FROM_OBSERVATION",
                    evidence = problem.observations.takeLast(8)
                )
            )
            moves += Move.ADAPT
        }

        // INVENT means constructing a new route from available primitives. It is deliberately
        // expressed as a candidate for probing, never as a claim that the route already works.
        if (problem.availableCapabilities.isNotEmpty()) {
            val inventory = problem.availableCapabilities.sorted().joinToString(" + ")
            val inventedId = "invent:${problem.taskId}:${problem.availableCapabilities.hashCode()}"
            candidates.putIfAbsent(
                inventedId,
                Strategy(
                    id = inventedId,
                    description = "Construct a novel route by composing available capabilities: $inventory",
                    source = "COMPOSED_NEW_ROUTE",
                    prerequisites = problem.availableCapabilities
                )
            )
            moves += Move.INVENT
        }

        moves += Move.PROBE
        moves += Move.VERIFY

        return EvolutionPlan(
            taskId = problem.taskId,
            moves = moves.distinct(),
            candidates = candidates.values.toList(),
            rationale = "Do not repeat a failed path blindly. Research current possibilities, combine or adapt known routes, invent a composed candidate when primitives exist, then require real probing and independent verification before promotion."
        )
    }

    fun recordOutcome(taskId: String, verifiedSuccess: Boolean) {
        outcomes.computeIfAbsent(taskId) { java.util.concurrent.CopyOnWriteArrayList() }.add(verifiedSuccess)
    }

    fun successRate(taskId: String): Double {
        val history = outcomes[taskId] ?: return 0.0
        if (history.isEmpty()) return 0.0
        return history.count { it }.toDouble() / history.size.toDouble()
    }

    fun clear(taskId: String) {
        outcomes.remove(taskId)
    }
}
