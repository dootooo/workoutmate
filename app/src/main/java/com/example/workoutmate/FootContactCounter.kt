package com.example.workoutmate

import kotlin.math.abs
import kotlin.math.max

/**
 * Detects one jump per takeoff -> landing cycle using the lowest ankle.
 *
 * The ground line is calibrated while the person stands before the countdown.
 * Takeoff / landing thresholds adapt to the measured foot jitter and to the
 * actually observed takeoff peaks, so different heights, camera angles and
 * clothing do not need hard-coded thresholds.
 */
class FootContactCounter {

    data class Landing(
        val x: Float,
        val y: Float,
        val footHeight: Float,
        val peakHeight: Float
    )

    private enum class Phase { GROUND, AIR }

    private var groundY: Float? = null
    private var phase = Phase.GROUND
    private var airStartedAt = 0L
    private var lastLandingAt = 0L
    private var airPeakHeight = 0f
    private var lastFootY = 0f
    private var footStableSince = 0L
    private val peakHistory = ArrayDeque<Float>()

    var ready: Boolean = false
        private set

    var noise: Float = BASE_NOISE
        private set

    var minLift: Float = BASE_MIN_LIFT
        private set

    fun calibrate(landmarks: PoseFrame) {
        val foot = lowestAnkle(landmarks) ?: return
        val current = groundY
        groundY = if (current == null) {
            foot.first
        } else {
            current * 0.75f + foot.first * 0.25f
        }
        ready = true
    }

    fun process(landmarks: PoseFrame, timestampMs: Long): Landing? {
        val initialGround = groundY ?: return null
        val foot = lowestAnkle(landmarks) ?: return null
        val footY = foot.first
        val footX = foot.second

        val stillThreshold = 0.0015f
        if (abs(footY - lastFootY) < stillThreshold) {
            if (footStableSince == 0L) footStableSince = timestampMs
        } else {
            footStableSince = 0L
        }
        lastFootY = footY

        // If the person walked, the original ground line is stale. Once the
        // foot has been stable for a while, adopt its new position.
        if (
            footStableSince > 0L &&
            timestampMs - footStableSince >= GROUND_RECALIBRATE_MS
        ) {
            groundY = footY
            phase = Phase.GROUND
            airStartedAt = 0L
            airPeakHeight = 0f
            footStableSince = 0L
        }

        val ground = groundY ?: initialGround
        val height = ground - footY
        val takeoffThreshold = max(BASE_TAKEOFF, noise * 3f)
            .coerceIn(0.004f, 0.012f)
        val landingThreshold = max(BASE_LANDING, noise * 1.5f)
            .coerceIn(0.002f, 0.006f)
        minLift = requiredLift()

        return when (phase) {
            Phase.GROUND -> {
                if (abs(height) < 0.02f) {
                    groundY = ground * 0.98f + footY * 0.02f
                    noise = (noise * 0.98f + abs(height) * 0.02f).coerceAtMost(0.006f)
                }
                if (height > takeoffThreshold) {
                    phase = Phase.AIR
                    airStartedAt = timestampMs
                    airPeakHeight = height
                }
                null
            }

            Phase.AIR -> {
                airPeakHeight = max(airPeakHeight, height)
                val airborneLongEnough = timestampMs - airStartedAt >= MIN_AIR_MS
                if (airborneLongEnough && height < landingThreshold) {
                    phase = Phase.GROUND
                    // Every landing is a fresh ground line at the new position.
                    groundY = footY
                    val eligible = timestampMs - lastLandingAt >= REFRACTORY_MS
                    lastLandingAt = timestampMs
                    val peakHeight = airPeakHeight
                    airPeakHeight = 0f

                    if (peakHeight >= noise * 2f) {
                        peakHistory.addLast(peakHeight)
                        while (peakHistory.size > PEAK_HISTORY_SIZE) {
                            peakHistory.removeFirst()
                        }
                    }
                    val hadEnoughLift = peakHeight >= minLift
                    if (eligible && hadEnoughLift) {
                        return Landing(
                            x = footX,
                            y = footY,
                            footHeight = height,
                            peakHeight = peakHeight
                        )
                    }
                }
                null
            }
        }
    }

    fun resetPhase() {
        phase = Phase.GROUND
        airStartedAt = 0L
        airPeakHeight = 0f
        lastLandingAt = 0L
    }

    fun reset() {
        groundY = null
        ready = false
        noise = BASE_NOISE
        minLift = BASE_MIN_LIFT
        peakHistory.clear()
        resetPhase()
    }

    private fun requiredLift(): Float {
        if (peakHistory.size < 3) return BASE_MIN_LIFT
        val sorted = peakHistory.sorted()
        val median = sorted[sorted.size / 2]
        return (median * 0.30f).coerceIn(0.008f, 0.015f)
    }

    private fun lowestAnkle(landmarks: PoseFrame): Pair<Float, Float>? {
        if (landmarks.size < MoveNetIndex.COUNT) return null
        val left = landmarks[MoveNetIndex.LEFT_ANKLE]
        val right = landmarks[MoveNetIndex.RIGHT_ANKLE]
        if (left.visibility < MIN_VISIBILITY && right.visibility < MIN_VISIBILITY) {
            return null
        }
        return if (left.visibility < MIN_VISIBILITY) {
            right.y to right.x
        } else if (right.visibility < MIN_VISIBILITY) {
            left.y to left.x
        } else if (left.y >= right.y) {
            left.y to left.x
        } else {
            right.y to right.x
        }
    }

    companion object {
        private const val MIN_VISIBILITY = 0.2f
        private const val BASE_NOISE = 0.0025f
        private const val BASE_TAKEOFF = 0.006f
        private const val BASE_LANDING = 0.003f
        private const val BASE_MIN_LIFT = 0.010f
        private const val MIN_AIR_MS = 80L
        private const val REFRACTORY_MS = 220L
        private const val GROUND_RECALIBRATE_MS = 700L
        private const val PEAK_HISTORY_SIZE = 5
    }
}
