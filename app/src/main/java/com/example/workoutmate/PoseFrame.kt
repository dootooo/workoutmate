package com.example.workoutmate

/**
 * MoveNet single-pose keypoints, in the order defined by the model:
 * nose, left/right eye, left/right ear, left/right shoulder, left/right elbow,
 * left/right wrist, left/right hip, left/right knee, left/right ankle.
 */
object MoveNetIndex {
    const val NOSE = 0
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16
    const val COUNT = 17
}

data class PosePoint(
    val x: Float,
    val y: Float,
    val confidence: Float
) {
    val visibility: Float
        get() = confidence
}

class PoseFrame(val points: List<PosePoint>) {
    val size: Int
        get() = points.size

    operator fun get(index: Int): PosePoint = points[index]
}
