package com.example.workoutmate

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Debug-only shadow classifier. It consumes the same MoveNet poses as the live
 * counter, computes the Phase 2 window features, scores the logistic model and
 * runs the time-state machine. It only writes a log file; it never changes the
 * visible count or the JumpCounter state.
 */
class JumpGateShadow(private val context: Context) {

    private data class FrameFeature(
        val t: Float,
        val signal: Float,
        val hipX: Float,
        val hipY: Float,
        val torso: Float,
        val ankleY: Float?,
        val topLeg: Float?,
        val legDiff: Float?,
        val kneeY: Float?,
        val visibility: Float,
        val shoulderHip: Float,
        val headX: Float?,
        val headY: Float?,
        val wristX: Float?,
        val wristY: Float?,
        val handHead: Float?
    )

    private class AdaptiveCrossingDetector(private val threshold: Float) {
        private var smoothed: Float? = null
        private var baseline: Float? = null
        private var above = false
        private var lastEventAt = -1f

        fun update(value: Float?, t: Float): Boolean {
            if (value == null) return false
            val previous = smoothed
            if (previous == null) {
                smoothed = value
                baseline = value
                return false
            }
            val nextSmoothed = 0.60f * value + 0.40f * previous
            val nextBaseline = 0.12f * nextSmoothed + 0.88f * (baseline ?: nextSmoothed)
            smoothed = nextSmoothed
            baseline = nextBaseline
            val centered = nextSmoothed - nextBaseline
            val wasAbove = above
            if (centered > threshold) {
                above = true
            } else if (centered < -0.5f * threshold) {
                above = false
            }
            if (above && !wasAbove && (lastEventAt < 0f || t - lastEventAt >= 0.2f)) {
                lastEventAt = t
                return true
            }
            return false
        }

        fun reset() {
            smoothed = null
            baseline = null
            above = false
            lastEventAt = -1f
        }
    }

    private val model = loadModel()
    private val buffer = ArrayDeque<FrameFeature>()
    private val logFile = File(context.filesDir, "shadow-gate.log")
    private val logLock = Any()

    private var lastWindowAt = -1f
    private var lastProbability = 0f
    private var lastShadowLogAt = 0L
    private var stateActive = true
    private var lowSince: Float? = null
    private var highSince: Float? = null
    private var transitions = 0
    private var candidateEvents = 0
    private var shadowCount = 0
    private var hipCandidateEvents = 0
    private var topCandidateEvents = 0
    private var styleAlternating = false
    private var styleKnown = false
    private var pendingStyle: Boolean? = null
    private var pendingStyleSince = 0f
    private var lastFusedCandidateAt = -1f

    private val hipDetector = AdaptiveCrossingDetector(0.028f)
    private val topDetector = AdaptiveCrossingDetector(0.020f)

    init {
        append("Shadow model loaded; features=${model.featureNames.size}")
    }

    fun reset() {
        synchronized(buffer) {
            buffer.clear()
        }
        lastWindowAt = -1f
        lastProbability = 0f
        lastShadowLogAt = 0L
        stateActive = true
        lowSince = null
        highSince = null
        transitions = 0
        candidateEvents = 0
        shadowCount = 0
        hipCandidateEvents = 0
        topCandidateEvents = 0
        styleAlternating = false
        styleKnown = false
        pendingStyle = null
        pendingStyleSince = 0f
        lastFusedCandidateAt = -1f
        hipDetector.reset()
        topDetector.reset()
        append("Shadow reset")
    }

    fun onPose(frame: PoseFrame, nowMs: Long) {
        val feature = extract(frame, nowMs) ?: return
        synchronized(buffer) {
            buffer.addLast(feature)
            while (buffer.isNotEmpty() && feature.t - buffer.first.t > 4f) {
                buffer.removeFirst()
            }
        }
        processCandidates(feature)
        if (lastWindowAt < 0f || feature.t - lastWindowAt >= 0.2f) {
            lastWindowAt = feature.t
            val window = synchronized(buffer) {
                buffer.filter { it.t >= feature.t - 0.8f && it.t <= feature.t }
            }
            if (window.size >= 6) {
                val vector = windowVector(window)
                val probability = predict(vector)
                updateState(probability, feature.t)
                lastProbability = probability
                val now = nowMs
                if (now - lastShadowLogAt >= 2000L) {
                    lastShadowLogAt = now
                    append(
                        "Shadow p=${"%.3f".format(probability)} " +
                            "state=${if (stateActive) "ACTIVE" else "FROZEN"} " +
                            "style=${if (styleAlternating) "ALTERNATING" else "FEET_TOGETHER"} " +
                            "hip=${hipCandidateEvents} top=${topCandidateEvents} " +
                            "events=$candidateEvents shadowCount=$shadowCount"
                    )
                }
            }
        }
    }

    fun logSummary() {
        append(
            "Shadow summary: events=$candidateEvents shadowCount=$shadowCount " +
                "hip=$hipCandidateEvents top=$topCandidateEvents " +
                "style=${if (styleAlternating) "ALTERNATING" else "FEET_TOGETHER"} " +
                "transitions=$transitions state=${if (stateActive) "ACTIVE" else "FROZEN"}"
        )
    }

    private fun processCandidates(feature: FrameFeature) {
        val hipEvent = hipDetector.update(feature.hipY, feature.t)
        val topEvent = topDetector.update(feature.topLeg, feature.t)
        if (hipEvent) hipCandidateEvents += 1
        if (topEvent) topCandidateEvents += 1
        updateStyle(feature)

        val selected = if (styleAlternating) hipEvent || topEvent else hipEvent
        if (!selected) return
        if (lastFusedCandidateAt >= 0f && feature.t - lastFusedCandidateAt < 0.2f) return
        lastFusedCandidateAt = feature.t
        candidateEvents += 1
        if (stateActive && lastProbability >= THRESHOLD) {
            shadowCount += 1
            val source = when {
                hipEvent && topEvent -> "FUSED"
                topEvent -> "TOP"
                else -> "HIP"
            }
            append(
                "Shadow candidate #$shadowCount source=$source " +
                    "p=${"%.3f".format(lastProbability)}"
            )
        }
    }

    private fun updateStyle(feature: FrameFeature) {
        val recent = synchronized(buffer) {
            buffer.filter { it.t >= feature.t - STYLE_WINDOW_S && it.t <= feature.t }
        }
        if (recent.size < 10) return
        val hipAmp = amplitude(recent.map { it.signal })
        val diffs = recent.mapNotNull { it.legDiff }
        if (hipAmp < 0.03f || diffs.size < 6) return
        val ratio = amplitude(diffs) / hipAmp
        if (!styleKnown) {
            styleKnown = true
            styleAlternating = ratio >= STYLE_ENTER
            append(
                "Shadow style -> ${if (styleAlternating) "ALTERNATING" else "FEET_TOGETHER"} " +
                    "ratio=${"%.2f".format(ratio)}"
            )
            return
        }
        val desired = if (styleAlternating) {
            // Leave alternating only after a clear drop.
            if (ratio <= STYLE_EXIT) false else styleAlternating
        } else {
            // Enter alternating only after a clear rise.
            if (ratio >= STYLE_ENTER) true else styleAlternating
        }
        if (desired == styleAlternating) {
            pendingStyle = null
            return
        }
        if (pendingStyle != desired) {
            pendingStyle = desired
            pendingStyleSince = feature.t
            return
        }
        if (feature.t - pendingStyleSince < STYLE_HOLD_S) return
        styleAlternating = desired
        pendingStyle = null
        append(
            "Shadow style -> ${if (styleAlternating) "ALTERNATING" else "FEET_TOGETHER"} " +
                "ratio=${"%.2f".format(ratio)}"
        )
    }

    private fun updateState(probability: Float, t: Float) {
        if (probability >= THRESHOLD) {
            lowSince = null
            if (highSince == null) highSince = t
            if (!stateActive && t - (highSince ?: t) >= RESUME_HOLD_S) {
                stateActive = true
                highSince = null
                transitions += 1
                append("Shadow state -> ACTIVE p=${"%.3f".format(probability)}")
            }
        } else {
            highSince = null
            if (lowSince == null) lowSince = t
            if (stateActive && t - (lowSince ?: t) >= FREEZE_HOLD_S) {
                stateActive = false
                lowSince = null
                transitions += 1
                append("Shadow state -> FROZEN p=${"%.3f".format(probability)}")
            }
        }
    }

    private fun extract(frame: PoseFrame, nowMs: Long): FrameFeature? {
        if (frame.size < MoveNetIndex.COUNT) return null
        fun visible(index: Int): Boolean = frame[index].visibility >= 0.2f
        if (!visible(MoveNetIndex.LEFT_SHOULDER) || !visible(MoveNetIndex.RIGHT_SHOULDER) ||
            !visible(MoveNetIndex.LEFT_HIP) || !visible(MoveNetIndex.RIGHT_HIP)
        ) {
            return null
        }
        val shoulderX = (frame[MoveNetIndex.LEFT_SHOULDER].x + frame[MoveNetIndex.RIGHT_SHOULDER].x) / 2f
        val shoulderY = (frame[MoveNetIndex.LEFT_SHOULDER].y + frame[MoveNetIndex.RIGHT_SHOULDER].y) / 2f
        val hipX = (frame[MoveNetIndex.LEFT_HIP].x + frame[MoveNetIndex.RIGHT_HIP].x) / 2f
        val hipY = (frame[MoveNetIndex.LEFT_HIP].y + frame[MoveNetIndex.RIGHT_HIP].y) / 2f
        val torso = sqrt((hipX - shoulderX) * (hipX - shoulderX) + (hipY - shoulderY) * (hipY - shoulderY))
        if (torso < 1e-3f) return null

        val ankles = listOf(MoveNetIndex.LEFT_ANKLE, MoveNetIndex.RIGHT_ANKLE)
            .filter { visible(it) }
            .map { frame[it].y }
        val leftAnkle = if (visible(MoveNetIndex.LEFT_ANKLE)) frame[MoveNetIndex.LEFT_ANKLE].y else null
        val rightAnkle = if (visible(MoveNetIndex.RIGHT_ANKLE)) frame[MoveNetIndex.RIGHT_ANKLE].y else null
        val topLeg = if (leftAnkle != null && rightAnkle != null) {
            min(leftAnkle, rightAnkle) / torso
        } else {
            null
        }
        val legDiff = if (leftAnkle != null && rightAnkle != null) {
            abs(leftAnkle - rightAnkle) / torso
        } else {
            null
        }
        val knees = listOf(MoveNetIndex.LEFT_KNEE, MoveNetIndex.RIGHT_KNEE)
            .filter { visible(it) }
            .map { frame[it].y }
        val headPoints = (0..4).filter { visible(it) }
        val wristPoints = listOf(9, 10).filter { visible(it) }
        val headX = if (headPoints.isEmpty()) null else headPoints.sumOf { frame[it].x.toDouble() }.toFloat() / headPoints.size
        val headY = if (headPoints.isEmpty()) null else headPoints.sumOf { frame[it].y.toDouble() }.toFloat() / headPoints.size
        val wristX = if (wristPoints.isEmpty()) null else wristPoints.sumOf { frame[it].x.toDouble() }.toFloat() / wristPoints.size
        val wristY = if (wristPoints.isEmpty()) null else wristPoints.sumOf { frame[it].y.toDouble() }.toFloat() / wristPoints.size
        val handHead = if (headX != null && headY != null && wristX != null && wristY != null) {
            sqrt((headX - wristX) * (headX - wristX) + (headY - wristY) * (headY - wristY)) / torso
        } else {
            null
        }
        var visibilitySum = 0f
        for (i in 0 until MoveNetIndex.COUNT) visibilitySum += frame[i].visibility
        return FrameFeature(
            t = nowMs / 1000f,
            signal = hipY / torso,
            hipX = hipX / torso,
            hipY = hipY / torso,
            torso = torso,
            ankleY = ankles.maxOrNull()?.div(torso),
            topLeg = topLeg,
            legDiff = legDiff,
            kneeY = knees.maxOrNull()?.div(torso),
            visibility = visibilitySum / MoveNetIndex.COUNT,
            shoulderHip = (shoulderY - hipY) / torso,
            headX = headX?.div(torso),
            headY = headY?.div(torso),
            wristX = wristX?.div(torso),
            wristY = wristY?.div(torso),
            handHead = handHead
        )
    }

    private fun windowVector(rows: List<FrameFeature>): FloatArray {
        val signals = rows.map { it.signal }
        val hipY = rows.map { it.hipY }
        val hipX = rows.map { it.hipX }
        val torsos = rows.map { it.torso }
        val ankles = rows.mapNotNull { it.ankleY }
        val knees = rows.mapNotNull { it.kneeY }
        val visibility = rows.map { it.visibility }
        val shoulderHip = rows.map { it.shoulderHip }
        val headY = rows.mapNotNull { it.headY }
        val headX = rows.mapNotNull { it.headX }
        val wristY = rows.mapNotNull { it.wristY }
        val wristX = rows.mapNotNull { it.wristX }
        val handHead = rows.mapNotNull { it.handHead }
        val amp = percentile(signals, 0.95f) - percentile(signals, 0.05f)
        val std = std(signals)
        val mad = if (signals.size > 1) {
            signals.zipWithNext { a, b -> abs(a - b) }.average().toFloat()
        } else {
            0f
        }
        val mean = signals.average().toFloat()
        val centered = signals.map { it - mean }
        val zcr = if (signals.size > 1) {
            centered.zipWithNext { a, b -> if ((a >= 0f) != (b >= 0f)) 1 else 0 }.sum().toFloat() /
                (signals.size - 1)
        } else {
            0f
        }
        var peaks = 0
        for (i in 1 until signals.size - 1) {
            if (signals[i] > signals[i - 1] && signals[i] >= signals[i + 1] && signals[i] > mean) peaks++
        }
        var acPeak = 0f
        var acPeriod = 0f
        if (centered.size >= 8) {
            val energy = centered.sumOf { it.toDouble() * it.toDouble() }
            if (energy > 1e-8) {
                val minLag = 6
                val maxLag = min(centered.size - 2, 27)
                if (maxLag > minLag) {
                    var bestLag = minLag
                    var best = -1.0
                    for (lag in minLag..maxLag) {
                        var sum = 0.0
                        for (i in 0 until centered.size - lag) {
                            sum += centered[i].toDouble() * centered[i + lag].toDouble()
                        }
                        val value = sum / energy
                        if (value > best) {
                            best = value
                            bestLag = lag
                        }
                    }
                    acPeak = best.toFloat()
                    acPeriod = bestLag / 30f
                }
            }
        }
        val hipDeriv = if (hipY.size > 1) {
            hipY.zipWithNext { a, b -> abs(a - b) }.average().toFloat()
        } else {
            0f
        }
        return floatArrayOf(
            amp, std, mad, zcr, peaks.toFloat(), acPeak, acPeriod,
            (hipY.maxOrNull() ?: 0f) - (hipY.minOrNull() ?: 0f),
            (hipX.maxOrNull() ?: 0f) - (hipX.minOrNull() ?: 0f),
            torsos.average().toFloat(), std(torsos),
            if (ankles.isEmpty()) 0f else (ankles.maxOrNull() ?: 0f) - (ankles.minOrNull() ?: 0f),
            if (knees.isEmpty()) 0f else (knees.maxOrNull() ?: 0f) - (knees.minOrNull() ?: 0f),
            visibility.average().toFloat(),
            rows.size / (0.8f * 30f),
            hipDeriv,
            (shoulderHip.maxOrNull() ?: 0f) - (shoulderHip.minOrNull() ?: 0f),
            if (headY.isEmpty()) 0f else (headY.maxOrNull() ?: 0f) - (headY.minOrNull() ?: 0f),
            if (headX.isEmpty()) 0f else (headX.maxOrNull() ?: 0f) - (headX.minOrNull() ?: 0f),
            if (wristY.isEmpty()) 0f else (wristY.maxOrNull() ?: 0f) - (wristY.minOrNull() ?: 0f),
            if (wristX.isEmpty()) 0f else (wristX.maxOrNull() ?: 0f) - (wristX.minOrNull() ?: 0f),
            if (handHead.isEmpty()) 0f else (handHead.minOrNull() ?: 0f),
            if (handHead.isEmpty()) 0f else handHead.average().toFloat()
        )
    }

    private fun predict(vector: FloatArray): Float {
        var logit = model.bias
        for (i in vector.indices) {
            logit += ((vector[i] - model.mean[i]) / model.std[i]) * model.weights[i]
        }
        return 1f / (1f + kotlin.math.exp(-logit))
    }

    private fun loadModel(): Model {
        val text = context.assets.open("jump_gate_model.json").bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val namesArray = json.getJSONArray("feature_names")
        val names = List(namesArray.length()) { namesArray.getString(it) }
        fun array(key: String): FloatArray {
            val values = json.getJSONArray(key)
            return FloatArray(values.length()) { values.getDouble(it).toFloat() }
        }
        return Model(names, array("mean"), array("std"), array("weights"), json.getDouble("bias").toFloat())
    }

    private fun append(line: String) {
        Log.d(TAG, line)
        synchronized(logLock) {
            try {
                logFile.appendText("${System.currentTimeMillis()} $line\n")
            } catch (error: Throwable) {
                Log.w(TAG, "Shadow log write failed", error)
            }
        }
    }

    private fun amplitude(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        return percentile(values, 0.95f) - percentile(values, 0.05f)
    }

    private fun percentile(values: List<Float>, q: Float): Float {        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val position = (sorted.size - 1) * q
        val lower = floor(position).toInt()
        val upper = min(sorted.size - 1, lower + 1)
        val fraction = position - lower
        return sorted[lower] * (1f - fraction) + sorted[upper] * fraction
    }

    private fun std(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average()
        var sum = 0.0
        for (value in values) {
            val delta = value - mean
            sum += delta * delta
        }
        return sqrt(sum / values.size).toFloat()
    }

    private data class Model(
        val featureNames: List<String>,
        val mean: FloatArray,
        val std: FloatArray,
        val weights: FloatArray,
        val bias: Float
    )

    companion object {
        private const val TAG = "JumpGateShadow"
        private const val THRESHOLD = 0.4f
        private const val FREEZE_HOLD_S = 0.8f
        private const val RESUME_HOLD_S = 0.4f
        private const val STYLE_WINDOW_S = 1.2f
        private const val STYLE_ENTER = 0.55f
        private const val STYLE_EXIT = 0.42f
        private const val STYLE_HOLD_S = 0.6f
    }
}
