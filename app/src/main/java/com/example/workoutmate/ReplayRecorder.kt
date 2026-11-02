package com.example.workoutmate

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors

/**
 * Debug-only recorder for replay regression tests.
 *
 * Each frame is stored as a JSON line containing the 17 keypoints and the
 * timestamp. This is deliberately a pose sequence, not a video: replay tests
 * must exercise the same keypoints that the app actually counted, without a
 * second inference pass introducing different results.
 */
class ReplayRecorder(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null
    private var file: File? = null

    fun start(mode: Int, target: Int) {
        if (!BuildConfig.DEBUG) return
        stop(0)
        val directory = File(context.filesDir, "replay-sessions").apply { mkdirs() }
        val output = File(directory, "session_${System.currentTimeMillis()}.jsonl")
        file = output
        try {
            writer = BufferedWriter(FileWriter(output, false))
            append("""{"type":"header","mode":$mode,"target":$target}""")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to open replay recorder", error)
            writer = null
            file = null
        }
    }

    fun record(frame: PoseFrame, timestampMs: Long) {
        if (!BuildConfig.DEBUG) return
        if (writer == null) return
        val points = buildString {
            append('[')
            frame.points.forEachIndexed { index, point ->
                if (index > 0) append(',')
                append('[')
                append(point.x)
                append(',')
                append(point.y)
                append(',')
                append(point.confidence)
                append(']')
            }
            append(']')
        }
        append("""{"type":"frame","t":$timestampMs,"p":$points}""")
    }

    fun stop(count: Int) {
        val output = file
        val activeWriter = writer
        writer = null
        file = null
        if (output == null) return
        executor.execute {
            try {
                activeWriter?.append("""{"type":"summary","count":$count}""")
                activeWriter?.newLine()
                activeWriter?.flush()
                activeWriter?.close()
                Log.d(TAG, "Replay session saved: ${output.absolutePath}")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to close replay recorder", error)
            }
        }
    }

    fun close() {
        stop(0)
        executor.shutdown()
    }

    private fun append(line: String) {
        val activeWriter = writer ?: return
        executor.execute {
            try {
                activeWriter.append(line)
                activeWriter.newLine()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to write replay frame", error)
            }
        }
    }

    companion object {
        private const val TAG = "ReplayRecorder"
    }
}
