package com.example.workoutmate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class JumpCounterTest {

    private fun runSine(
        periods: Int,
        samplesPerPeriod: Int = 15,
        amplitude: Float = 0.15f,
        baseline: Float = 1f
    ): Int {
        val counter = JumpCounter()
        var timestamp = 0L
        repeat(periods * samplesPerPeriod) { index ->
            val theta = 2.0 * Math.PI * index / samplesPerPeriod
            val signal = baseline + amplitude * cos(theta).toFloat()
            counter.process(signal, timestamp)
            timestamp += 33L
        }
        return counter.state.count
    }

    @Test
    fun standingStillProducesNoCount() {
        val counter = JumpCounter()
        repeat(100) { index ->
            counter.process(1.0f, index * 33L)
        }
        assertEquals(0, counter.state.count)
    }

    @Test
    fun countsOneRepPerCleanCycle() {
        val count = runSine(periods = 10)
        assertTrue("expected about 10 counts, got $count", count in 9..10)
    }

    @Test
    fun standingWithSmallNoiseProducesNoCount() {
        val counter = JumpCounter()
        repeat(300) { index ->
            val noise = if (index % 2 == 0) 0.01f else -0.01f
            counter.process(1.0f + noise, index * 50L)
        }
        assertEquals(0, counter.state.count)
    }

    @Test
    fun fastCadenceStillCounts() {
        // About 8 pose samples per cycle, matching 200+ jumps/min at 27fps.
        val counter = JumpCounter()
        var timestamp = 0L
        repeat(20 * 8) { index ->
            val theta = 2.0 * Math.PI * index / 8
            val signal = 1f + 0.15f * cos(theta).toFloat()
            counter.process(signal, timestamp)
            timestamp += 37L
        }
        assertTrue(
            "expected about 20 counts, got ${counter.state.count}",
            counter.state.count in 18..20
        )
    }
}
