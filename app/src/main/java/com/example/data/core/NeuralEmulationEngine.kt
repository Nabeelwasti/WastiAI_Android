package com.example.data.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.random.Random

data class NeuronSpikeState(
    val neuronId: Int,
    val membranePotentialMv: Float,
    val isSpiking: Boolean,
    val refractoryCounter: Int,
    val totalSpikeCount: Int = 0
)

data class HodgkinHuxleyState(
    val membraneVoltageMv: Double = -65.0,
    val sodiumConductanceM: Double = 0.05,
    val potassiumConductanceN: Double = 0.32,
    val sodiumInactivationH: Double = 0.60
)

data class AnnLayerState(
    val layerName: String,
    val neuronValues: List<Float>
)

data class AnnSimulationResult(
    val inputValues: List<Float>,
    val hiddenLayers: List<AnnLayerState>,
    val outputValues: List<Float>,
    val executionTimeMs: Long
)

class LeakyIntegrateAndFireNeuron(
    val id: Int,
    val restingPotential: Float = -70.0f,
    val threshold: Float = -55.0f,
    val resetPotential: Float = -75.0f,
    val membraneTimeConstantMs: Float = 20.0f
) {
    var membranePotential: Float = restingPotential
        private set
    private var refractoryPeriod: Int = 0
    private var spikeCount: Int = 0

    fun step(inputCurrent: Float, dtMs: Float = 1.0f): NeuronSpikeState {
        if (refractoryPeriod > 0) {
            refractoryPeriod--
            return NeuronSpikeState(id, resetPotential, false, refractoryPeriod, spikeCount)
        }

        val dV = (-(membranePotential - restingPotential) + inputCurrent) / membraneTimeConstantMs * dtMs
        membranePotential += dV

        return if (membranePotential >= threshold) {
            membranePotential = resetPotential
            refractoryPeriod = 2
            spikeCount++
            NeuronSpikeState(id, threshold, true, refractoryPeriod, spikeCount)
        } else {
            NeuronSpikeState(id, membranePotential, false, 0, spikeCount)
        }
    }

    fun reset() {
        membranePotential = restingPotential
        refractoryPeriod = 0
        spikeCount = 0
    }
}

/**
 * Biological Hodgkin-Huxley conductance-based action potential simulator.
 */
class HodgkinHuxleyNeuron {
    private var v = -65.0 // Membrane voltage (mV)
    private var m = 0.05  // Na+ activation
    private var h = 0.60  // Na+ inactivation
    private var n = 0.32  // K+ activation

    // Constants
    private val gNa = 120.0 // mS/cm^2
    private val gK = 36.0   // mS/cm^2
    private val gL = 0.3    // mS/cm^2
    private val eNa = 50.0  // mV
    private val eK = -77.0  // mV
    private val eL = -54.387 // mV
    private val cM = 1.0    // uF/cm^2

    fun step(injectedCurrent: Double, dtMs: Double = 0.05): HodgkinHuxleyState {
        fun alphaM(v: Double) = 0.1 * (v + 40.0) / (1.0 - exp(-(v + 40.0) / 10.0))
        fun betaM(v: Double) = 4.0 * exp(-(v + 65.0) / 18.0)
        fun alphaH(v: Double) = 0.07 * exp(-(v + 65.0) / 20.0)
        fun betaH(v: Double) = 1.0 / (1.0 + exp(-(v + 35.0) / 10.0))
        fun alphaN(v: Double) = 0.01 * (v + 55.0) / (1.0 - exp(-(v + 55.0) / 10.0))
        fun betaN(v: Double) = 0.125 * exp(-(v + 65.0) / 80.0)

        val am = alphaM(v)
        val bm = betaM(v)
        val ah = alphaH(v)
        val bh = betaH(v)
        val an = alphaN(v)
        val bn = betaN(v)

        m += dtMs * (am * (1.0 - m) - bm * m)
        h += dtMs * (ah * (1.0 - h) - bh * h)
        n += dtMs * (an * (1.0 - n) - bn * n)

        val iNa = gNa * (m * m * m) * h * (v - eNa)
        val iK = gK * (n * n * n * n) * (v - eK)
        val iL = gL * (v - eL)

        val iMembrane = injectedCurrent - iNa - iK - iL
        v += dtMs * (iMembrane / cM)

        return HodgkinHuxleyState(v, m, n, h)
    }
}

/**
 * Custom Artificial Neural Network (ANN) Multi-Layer Perceptron Simulator.
 */
class ArtificialNeuralNetwork(
    val inputSize: Int = 4,
    val hiddenLayers: List<Int> = listOf(8, 6),
    val outputSize: Int = 3
) {
    private val weights = mutableListOf<Array<FloatArray>>()
    private val biases = mutableListOf<FloatArray>()

    init {
        var prevSize = inputSize
        val allLayers = hiddenLayers + outputSize
        for (nextSize in allLayers) {
            val w = Array(prevSize) {
                FloatArray(nextSize) { (Random.nextFloat() * 2.0f - 1.0f) * 0.5f }
            }
            val b = FloatArray(nextSize) { 0.1f }
            weights.add(w)
            biases.add(b)
            prevSize = nextSize
        }
    }

    fun forward(inputs: List<Float>): AnnSimulationResult {
        val startTime = System.currentTimeMillis()
        var currentActivations = inputs.toFloatArray()
        val hiddenLayerStates = mutableListOf<AnnLayerState>()

        for (layerIdx in 0 until weights.size) {
            val w = weights[layerIdx]
            val b = biases[layerIdx]
            val nextActivations = FloatArray(b.size)

            for (j in b.indices) {
                var sum = b[j]
                for (i in currentActivations.indices) {
                    sum += currentActivations[i] * w[i][j]
                }
                // Activation: ReLU for hidden, Softmax/Sigmoid for output
                nextActivations[j] = if (layerIdx < weights.size - 1) {
                    if (sum > 0f) sum else 0f // ReLU
                } else {
                    1.0f / (1.0f + exp(-sum)) // Sigmoid
                }
            }

            val layerLabel = if (layerIdx < hiddenLayers.size) "Hidden Layer ${layerIdx + 1}" else "Output Layer"
            hiddenLayerStates.add(AnnLayerState(layerLabel, nextActivations.toList()))
            currentActivations = nextActivations
        }

        val elapsed = System.currentTimeMillis() - startTime
        return AnnSimulationResult(
            inputValues = inputs,
            hiddenLayers = hiddenLayerStates.dropLast(1),
            outputValues = hiddenLayerStates.last().neuronValues,
            executionTimeMs = elapsed
        )
    }
}

object NeuralEmulationEngine {

    // 64-neuron biological population with dynamic synaptic recurrence
    private val neuronPopulation = List(64) { idx -> LeakyIntegrateAndFireNeuron(id = idx) }
    private val hhNeuron = HodgkinHuxleyNeuron()
    private val ann = ArtificialNeuralNetwork(inputSize = 4, hiddenLayers = listOf(8, 6), outputSize = 3)

    private val _emulationActivity = MutableStateFlow<List<NeuronSpikeState>>(emptyList())
    val emulationActivity: StateFlow<List<NeuronSpikeState>> = _emulationActivity.asStateFlow()

    private val _hodgkinHuxleyState = MutableStateFlow(HodgkinHuxleyState())
    val hodgkinHuxleyState: StateFlow<HodgkinHuxleyState> = _hodgkinHuxleyState.asStateFlow()

    private val _annState = MutableStateFlow<AnnSimulationResult?>(null)
    val annState: StateFlow<AnnSimulationResult?> = _annState.asStateFlow()

    fun stepNetwork(externalStimulus: Float): List<NeuronSpikeState> {
        val states = neuronPopulation.mapIndexed { idx, neuron ->
            // Recurrent synaptic excitation from neighboring neurons
            val neighborSpikeBonus = if (idx > 0 && neuronPopulation[idx - 1].membranePotential > -60.0f) 3.5f else 0.0f
            val input = externalStimulus + (idx % 8) * 1.5f + neighborSpikeBonus + (Random.nextFloat() * 2.0f)
            neuron.step(input)
        }
        _emulationActivity.value = states
        return states
    }

    fun stepHodgkinHuxley(injectedCurrent: Double): HodgkinHuxleyState {
        val state = hhNeuron.step(injectedCurrent)
        _hodgkinHuxleyState.value = state
        return state
    }

    fun executeAnnForwardPass(inputs: List<Float> = listOf(0.8f, 0.4f, 0.9f, 0.2f)): AnnSimulationResult {
        val result = ann.forward(inputs)
        _annState.value = result
        return result
    }

    fun generatePythonSimulationScript(type: String = "LIF"): String {
        return when (type.uppercase()) {
            "ANN" -> """
import numpy as np

# Wasti AI OS - Artificial Neural Network Simulation (From Scratch)
def sigmoid(x):
    return 1 / (1 + np.exp(-x))

def relu(x):
    return np.maximum(0, x)

np.random.seed(42)
X = np.array([[0.8, 0.4, 0.9, 0.2]]) # Input features

# 3-Layer MLP Weights and Biases
W1 = np.random.randn(4, 8) * 0.5
b1 = np.zeros((1, 8))
W2 = np.random.randn(8, 6) * 0.5
b2 = np.zeros((1, 6))
W3 = np.random.randn(6, 3) * 0.5
b3 = np.zeros((1, 3))

# Forward Pass Execution
z1 = np.dot(X, W1) + b1
a1 = relu(z1)
z2 = np.dot(a1, W2) + b2
a2 = relu(z2)
z3 = np.dot(a2, W3) + b3
output = sigmoid(z3)

print("ANN Forward Pass Outputs:", output)
            """.trimIndent()
            else -> """
import numpy as np
import matplotlib.pyplot as plt

# Wasti AI OS - Leaky Integrate-and-Fire (LIF) 100-Neuron Network
n_neurons = 100
time_steps = 200
v_rest = -70.0
v_thresh = -55.0
v_reset = -75.0
tau_m = 20.0
dt = 1.0

v = np.full((n_neurons,), v_rest)
spike_raster = []

for t in range(time_steps):
    i_inj = np.random.normal(18.0, 4.0, size=(n_neurons,))
    dv = (-(v - v_rest) + i_inj) / tau_m * dt
    v += dv
    
    spiked = v >= v_thresh
    for idx in np.where(spiked)[0]:
        spike_raster.append((t, idx))
    v[spiked] = v_reset

print(f"Total Spikes Simulated: {len(spike_raster)}")
            """.trimIndent()
        }
    }
}
