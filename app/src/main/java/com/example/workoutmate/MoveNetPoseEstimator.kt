package com.example.workoutmate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MoveNet Lightning single-pose estimator. The model input is a 192x192 RGB
 * uint8 tensor, and the output is [1, 1, 17, 3] as (y, x, score).
 */
class MoveNetPoseEstimator(context: Context) {

    private val interpreter: Interpreter
    var usingGpu: Boolean = false
        private set
    @Volatile
    var motionLevel: Float = 0f
        private set
    private var previousY: ByteArray? = null
    private var lastPerfLogAt = 0L
    private var inferenceCount = 0
    private var inferenceTotalMs = 0L
    private val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
        .order(ByteOrder.nativeOrder())
    private val inputBytes = ByteArray(INPUT_SIZE * INPUT_SIZE * 3)
    private val rotatedXForColumn = FloatArray(INPUT_SIZE)
    private val rotatedYForRow = FloatArray(INPUT_SIZE)
    private val sourceXForColumn = IntArray(INPUT_SIZE)
    private val sourceYForColumn = IntArray(INPUT_SIZE)
    private val sourceXForRow = IntArray(INPUT_SIZE)
    private val sourceYForRow = IntArray(INPUT_SIZE)
    private var lastBuildLogAt = 0L
    private var buildCount = 0
    private var buildTotalMs = 0L
    private val output = Array(1) { Array(1) { Array(MoveNetIndex.COUNT) { FloatArray(3) } } }
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val inferenceBusy = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        val model = loadModel(context)
        val options = Interpreter.Options()
            .setNumThreads(4)
            .setUseXNNPACK(true)
        interpreter = Interpreter(model, options)
        Log.d(
            TAG,
            "MoveNet input=${interpreter.getInputTensor(0).shape().contentToString()} " +
                "output=${interpreter.getOutputTensor(0).shape().contentToString()}"
        )
    }

    fun submit(
        image: ImageProxy,
        rotationDegrees: Int,
        onResult: (PoseFrame?) -> Unit
    ) {
        if (!inferenceBusy.compareAndSet(false, true)) return
        val input = try {
            buildInput(image, rotationDegrees)
        } catch (error: Exception) {
            Log.e(TAG, "MoveNet preprocessing failed", error)
            inferenceBusy.set(false)
            mainHandler.post { onResult(null) }
            return
        }

        inferenceExecutor.execute {
            val frame = try {
                runInference(input)
            } catch (error: Exception) {
                Log.e(TAG, "MoveNet inference failed", error)
                null
            } finally {
                inferenceBusy.set(false)
            }
            mainHandler.post { onResult(frame) }
        }
    }

    private fun buildInput(image: ImageProxy, rotationDegrees: Int): ByteBuffer {
        val buildStartedAt = SystemClock.elapsedRealtime()
        val width = image.width
        val height = image.height
        val rotation = ((rotationDegrees % 360) + 360) % 360

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBytes = copyBuffer(yPlane.buffer)
        val previous = previousY
        if (previous != null && previous.size == yBytes.size) {
            var difference = 0L
            var samples = 0
            var index = 0
            while (index < yBytes.size) {
                difference += kotlin.math.abs(
                    (yBytes[index].toInt() and 0xFF) - (previous[index].toInt() and 0xFF)
                )
                samples += 1
                index += 8
            }
            motionLevel = if (samples > 0) difference.toFloat() / samples else 0f
        }
        previousY = yBytes
        val uBytes = copyBuffer(uPlane.buffer)
        val vBytes = copyBuffer(vPlane.buffer)
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val rotatedWidth = if (rotation == 90 || rotation == 270) height else width
        val rotatedHeight = if (rotation == 90 || rotation == 270) width else height

        for (x in 0 until INPUT_SIZE) {
            val normalizedX = GUIDE_LEFT + (x + 0.5f) / INPUT_SIZE * GUIDE_WIDTH
            rotatedXForColumn[x] = normalizedX * rotatedWidth
            sourceXForColumn[x] = when (rotation) {
                180 -> (width - 1 - rotatedXForColumn[x]).toInt()
                0 -> rotatedXForColumn[x].toInt()
                else -> 0
            }
            sourceYForColumn[x] = when (rotation) {
                90 -> (height - 1 - rotatedXForColumn[x]).toInt()
                270 -> rotatedXForColumn[x].toInt()
                else -> 0
            }
        }
        for (y in 0 until INPUT_SIZE) {
            val normalizedY = GUIDE_TOP + (y + 0.5f) / INPUT_SIZE * GUIDE_HEIGHT
            rotatedYForRow[y] = normalizedY * rotatedHeight
            sourceXForRow[y] = when (rotation) {
                90 -> rotatedYForRow[y].toInt()
                270 -> (width - 1 - rotatedYForRow[y]).toInt()
                else -> 0
            }
            sourceYForRow[y] = when (rotation) {
                180 -> (height - 1 - rotatedYForRow[y]).toInt()
                0 -> rotatedYForRow[y].toInt()
                else -> 0
            }
        }

        var outputIndex = 0
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val sourceX = if (rotation == 90 || rotation == 270) {
                    sourceXForRow[y]
                } else {
                    sourceXForColumn[x]
                }
                val sourceY = if (rotation == 90 || rotation == 270) {
                    sourceYForColumn[x]
                } else {
                    sourceYForRow[y]
                }
                if (sourceX in 0 until width && sourceY in 0 until height) {
                    val yIndex = sourceY * yRowStride + sourceX * yPixelStride
                    val uvX = sourceX / 2
                    val uvY = sourceY / 2
                    val uIndex = uvY * uRowStride + uvX * uPixelStride
                    val vIndex = uvY * vRowStride + uvX * vPixelStride
                    val yValue = if (yIndex < yBytes.size) yBytes[yIndex].toInt() and 0xFF else 0
                    val uValue = if (uIndex < uBytes.size) uBytes[uIndex].toInt() and 0xFF else 128
                    val vValue = if (vIndex < vBytes.size) vBytes[vIndex].toInt() and 0xFF else 128
                    val u = uValue - 128
                    val v = vValue - 128
                    val red = (yValue + 1.402f * v).toInt().coerceIn(0, 255)
                    val green = (yValue - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                    val blue = (yValue + 1.772f * u).toInt().coerceIn(0, 255)
                    inputBytes[outputIndex++] = red.toByte()
                    inputBytes[outputIndex++] = green.toByte()
                    inputBytes[outputIndex++] = blue.toByte()
                } else {
                    inputBytes[outputIndex++] = 0
                    inputBytes[outputIndex++] = 0
                    inputBytes[outputIndex++] = 0
                }
            }
        }
        inputBuffer.clear()
        inputBuffer.put(inputBytes)
        inputBuffer.rewind()

        buildCount += 1
        buildTotalMs += SystemClock.elapsedRealtime() - buildStartedAt
        val now = SystemClock.elapsedRealtime()
        if (now - lastBuildLogAt >= 2000L) {
            Log.d(TAG, "build avg=${buildTotalMs / buildCount}ms frames=$buildCount")
            lastBuildLogAt = now
            buildCount = 0
            buildTotalMs = 0L
        }
        return inputBuffer
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        copy.rewind()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun runInference(input: ByteBuffer): PoseFrame? {
        val startedAt = SystemClock.elapsedRealtime()
        interpreter.run(input, output)

        inferenceCount += 1
        inferenceTotalMs += SystemClock.elapsedRealtime() - startedAt
        val now = SystemClock.elapsedRealtime()
        if (now - lastPerfLogAt >= 2000L) {
            Log.d(
                TAG,
                "inference avg=${inferenceTotalMs / inferenceCount}ms " +
                    "frames=$inferenceCount gpu=$usingGpu"
            )
            lastPerfLogAt = now
            inferenceCount = 0
            inferenceTotalMs = 0L
        }

        val points = ArrayList<PosePoint>(MoveNetIndex.COUNT)
        for (index in 0 until MoveNetIndex.COUNT) {
            val y = output[0][0][index][0].coerceIn(0f, 1f)
            val x = output[0][0][index][1].coerceIn(0f, 1f)
            val score = output[0][0][index][2].coerceIn(0f, 1f)
            points.add(PosePoint(x = x, y = y, confidence = score))
        }
        return PoseFrame(points)
    }

    fun close() {
        inferenceExecutor.shutdown()
        interpreter.close()
        previousY = null
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
        }
    }

    companion object {
        private const val TAG = "MoveNet"
        private const val MODEL_ASSET = "movenet_lightning.tflite"
        private const val INPUT_SIZE = 192
        private const val GUIDE_LEFT = 0.06f
        private const val GUIDE_TOP = 0.02f
        private const val GUIDE_WIDTH = 0.88f
        private const val GUIDE_HEIGHT = 0.96f
    }
}
