package com.example.workoutmate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Adapted from hanklzl/HappyJumping (Apache License 2.0):
 * app/src/main/java/com/example/happyjumping/core/JumpCounter.kt
 * app/src/main/java/com/example/happyjumping/core/PoseSignal.kt
 */
object PoseSignal {
    private const val MIN_TORSO_LENGTH = 1e-3f

    /**
     * Hip-center vertical position normalized by torso length. Smaller means
     * the body is higher in the image. Returns null when the required joints
     * are not confidently visible.
     */
    fun extract(
        landmarks: PoseFrame,
        minVisibility: Float = 0.5f
    ): Float? {
        if (landmarks.size < MoveNetIndex.COUNT) return null
        val points = listOf(
            landmarks[MoveNetIndex.LEFT_SHOULDER],
            landmarks[MoveNetIndex.RIGHT_SHOULDER],
            landmarks[MoveNetIndex.LEFT_HIP],
            landmarks[MoveNetIndex.RIGHT_HIP]
        )
        if (points.any { it.visibility < minVisibility }) return null

        val shoulderY = (
            landmarks[MoveNetIndex.LEFT_SHOULDER].y +
                landmarks[MoveNetIndex.RIGHT_SHOULDER].y
            ) / 2f
        val hipY = (
            landmarks[MoveNetIndex.LEFT_HIP].y +
                landmarks[MoveNetIndex.RIGHT_HIP].y
            ) / 2f
        val torsoLength = abs(hipY - shoulderY)
        if (torsoLength < MIN_TORSO_LENGTH) return null

        // Hip-center is the most stable single signal. Knee/ankle points can
        // move out of phase with the hip and cancel half of a fast jump cycle.
        return hipY / torsoLength
    }
}

data class JumpCounterConfig(
    val emaAlpha: Float = 0.60f,
    val baselineAlpha: Float = 0.12f,
    val minAmplitude: Float = 0.028f,
    val fastCadenceMinAmplitude: Float = 0.028f,
    val fastCadenceThresholdMs: Long = 520L,
    val upFraction: Float = 0.5f,
    val downFraction: Float = 0.35f,
    val bandDecay: Float = 0.02f,
    val refractoryMs: Long = 180L,
    val gapResetMs: Long = 1200L,
    val suppressAfterGap: Int = 2,
    val phaseTimeoutMs: Long = 650L,
    val missFramesForOutOfFrame: Int = 8
)

enum class JumpPhase { LOW, HIGH }

data class CounterState(
    val count: Int = 0,
    val phase: JumpPhase = JumpPhase.LOW,
    val inFrame: Boolean = true,
    val armed: Boolean = false,
    val amplitude: Float = 0f
)

/**
 * Counts jump-rope reps from normalized vertical signals using an EMA and an
 * adaptive-band hysteresis state machine with a refractory period.
 */
class JumpCounter(
    private val config: JumpCounterConfig = JumpCounterConfig()
) {
    var state: CounterState = CounterState()
        private set

    private var initialized = false
    private var smoothed = 0f
    private var recentMin = 0f
    private var recentMax = 0f
    private var lastCountMs = NEVER
    private var suppressRemaining = 0
    private var baseline = 0f
    private var aboveBaseline = false
    private var missCount = 0
    private var fastCadence = false

    /** @return true if a jump was counted on this frame. */
    fun process(signal: Float?, timestampMs: Long): Boolean {
        if (signal == null) {
            missCount++
            if (missCount >= config.missFramesForOutOfFrame) {
                state = state.copy(inFrame = false)
            }
            return false
        }
        missCount = 0

        if (!initialized) {
            smoothed = signal
            recentMin = signal
            recentMax = signal
            baseline = signal
            aboveBaseline = false
            initialized = true
            state = state.copy(inFrame = true)
            return false
        }

        smoothed = config.emaAlpha * signal + (1f - config.emaAlpha) * smoothed
        baseline = config.baselineAlpha * smoothed + (1f - config.baselineAlpha) * baseline
        recentMin = min(smoothed, recentMin + config.bandDecay * (smoothed - recentMin))
        recentMax = max(smoothed, recentMax + config.bandDecay * (smoothed - recentMax))
        val amplitude = recentMax - recentMin

        val threshold = if (fastCadence) {
            config.fastCadenceMinAmplitude
        } else {
            config.minAmplitude
        }
        val centered = smoothed - baseline
        val wasAbove = aboveBaseline
        if (centered > threshold) {
            aboveBaseline = true
        } else if (centered < -threshold * 0.50f) {
            aboveBaseline = false
        }

        var counted = false
        if (aboveBaseline && !wasAbove) {
            val eligible =
                lastCountMs == NEVER || timestampMs - lastCountMs >= config.refractoryMs
            if (eligible) {
                val gap = if (lastCountMs == NEVER) {
                    0L
                } else {
                    timestampMs - lastCountMs
                }
                if (gap > config.gapResetMs) {
                    // A pause / rope adjustment produces isolated hip peaks.
                    // Ignore the first few events after the gap until the
                    // periodic jumping motion has resumed.
                    suppressRemaining = config.suppressAfterGap
                }
                if (lastCountMs != NEVER && gap <= config.fastCadenceThresholdMs) {
                    fastCadence = true
                }
                lastCountMs = timestampMs
                if (suppressRemaining > 0) {
                    suppressRemaining -= 1
                } else {
                    counted = true
                }
            }
        }

        state = CounterState(
            count = state.count + if (counted) 1 else 0,
            phase = if (aboveBaseline) JumpPhase.HIGH else JumpPhase.LOW,
            inFrame = true,
            armed = amplitude >= threshold,
            amplitude = amplitude
        )
        return counted
    }

    fun reset() {
        state = CounterState()
        initialized = false
        smoothed = 0f
        recentMin = 0f
        recentMax = 0f
        lastCountMs = NEVER
        suppressRemaining = 0
        baseline = 0f
        aboveBaseline = false
        missCount = 0
        fastCadence = false
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
