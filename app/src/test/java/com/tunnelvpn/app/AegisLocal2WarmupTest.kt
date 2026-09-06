package com.tunnelvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AegisLocal2WarmupTest {
    @Test
    fun preparePreservesModelWeightsAndEngineState() {
        val clock = AegisLocal2NanoClock { 1L }
        val model = model()
        val weightsBefore = parameterSnapshot(model)
        val predictionBefore = model.predict(FloatArray(AegisLocal2NeuralModel.INPUT_SIZE))

        val prepared = model.prepare(nanoClock = clock)

        assertTrue(prepared.ready)
        assertEquals(AegisLocal2NeuralModel.PREPARATION_PREDICTION_LIMIT, prepared.attemptedPredictions)
        weightsBefore.forEach { (name, expected) ->
            assertArrayEquals(expected, parameterArray(model, name), 0.0f)
        }
        assertEquals(predictionBefore, model.predict(FloatArray(AegisLocal2NeuralModel.INPUT_SIZE)))

        val engine = AegisLocal2Engine(nanoClock = clock)
        val stateBefore = engine.stats()
        val enginePrepared = engine.prepare()

        assertTrue(enginePrepared.ready)
        assertEquals(stateBefore, engine.stats())
        assertTrue(engine.outcomeMapSnapshot().isEmpty())
    }

    @Test
    fun prepareCapsPredictionCountAndStopsAtWallBoundary() {
        val countBounded = model().prepare(
            predictionLimit = Int.MAX_VALUE,
            wallLimitNanos = Long.MAX_VALUE,
            nanoClock = AegisLocal2NanoClock { 1L }
        )

        assertTrue(countBounded.ready)
        assertEquals(AegisLocal2NeuralModel.PREPARATION_PREDICTION_LIMIT, countBounded.attemptedPredictions)

        val wallBounded = model().prepare(
            nanoClock = SteppingNanoClock(30_000_000L)
        )

        assertTrue(wallBounded.ready)
        assertEquals(2, wallBounded.attemptedPredictions)
        assertTrue(wallBounded.elapsedNanos >= AegisLocal2NeuralModel.PREPARATION_WALL_LIMIT_NANOS)
    }

    @Test
    fun prepareRejectsNonFiniteModelWithoutPrediction() {
        val model = model()
        val weights = parameterArray(model, "inputWeights")
        weights[0] = Float.NaN

        val prepared = model.prepare(nanoClock = AegisLocal2NanoClock { 1L })

        assertFalse(prepared.ready)
        assertEquals(0, prepared.attemptedPredictions)
        assertTrue(parameterArray(model, "inputWeights")[0].isNaN())
    }

    private fun model(): AegisLocal2NeuralModel {
        return AegisLocal2NeuralModel(
            seed = 0x41454749534C4F32L,
            learningRate = 0.015f,
            gradientClip = 1.0f
        )
    }

    private fun parameterSnapshot(model: AegisLocal2NeuralModel): Map<String, FloatArray> {
        return PARAMETER_FIELDS.associateWith { name -> parameterArray(model, name).copyOf() }
    }

    private fun parameterArray(model: AegisLocal2NeuralModel, name: String): FloatArray {
        val field = AegisLocal2NeuralModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        return field.get(model) as FloatArray
    }

    private class SteppingNanoClock(private val stepNanos: Long) : AegisLocal2NanoClock {
        private var now = 0L

        override fun nowNanos(): Long {
            now += stepNanos
            return now
        }
    }

    companion object {
        private val PARAMETER_FIELDS = listOf("inputWeights", "hiddenBias", "outputWeights", "outputBias")
    }
}
