package com.example.data.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp

data class NeuronSpikeState(
    val membranePotentialMv: Float,
    val isSpiking: Boolean,
    val refractoryCounter: Int
)

class LeakyIntegrateAndFireNeuron(
    val restingPotential: Float = -70.0f,
    val threshold: Float = -55.0f,
    val resetPotential: Float = -75.0f,
    val membraneTimeConstantMs: Float = 20.0f
) {
    private var membranePotential: Float = restingPotential
    private var refractoryPeriod: Int = 0

    fun step(inputCurrent: Float, dtMs: Float = 1.0f): NeuronSpikeState {
        if (refractoryPeriod > 0) {
            refractoryPeriod--
            return NeuronSpikeState(resetPotential, false, refractoryPeriod)
        }

        val dV = (-(membranePotential - restingPotential) + inputCurrent) / membraneTimeConstantMs * dtMs
        membranePotential += dV

        return if (membranePotential >= threshold) {
            membranePotential = resetPotential
            refractoryPeriod = 2
            NeuronSpikeState(threshold, true, refractoryPeriod)
        } else {
            NeuronSpikeState(membranePotential, false, 0)
        }
    }
}

object NeuralEmulationEngine {

    private val neuronPopulation = List(16) { LeakyIntegrateAndFireNeuron() }
    private val _emulationActivity = MutableStateFlow<List<NeuronSpikeState>>(emptyList())
    val emulationActivity: StateFlow<List<NeuronSpikeState>> = _emulationActivity.asStateFlow()

    fun stepNetwork(externalStimulus: Float): List<NeuronSpikeState> {
        val states = neuronPopulation.mapIndexed { idx, neuron ->
            val input = externalStimulus + (idx * 0.5f)
            neuron.step(input)
        }
        _emulationActivity.value = states
        return states
    }
}
