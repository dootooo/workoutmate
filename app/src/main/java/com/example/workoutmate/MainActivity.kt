package com.example.workoutmate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var countPanel: View
    private lateinit var countText: TextView
    private lateinit var timerText: TextView
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var modeText: TextView
    private lateinit var switchModeText: TextView
    private lateinit var hintText: TextView
    private lateinit var guideOverlay: View
    private lateinit var rippleView: FootRippleView
    private val footCounter = FootContactCounter()
    private var sessionMode = ModeActivity.MODE_FREE
    private var targetCount = 100
    private var autoStartTriggered = false

    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val replayRecorder by lazy { ReplayRecorder(this) }

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private var poseEstimator: MoveNetPoseEstimator? = null
    private var poseReady = false

    @Volatile
    private var isSessionRunning = false
    @Volatile
    private var waitingForBody = false
    @Volatile
    private var isCounting = false
    @Volatile
    private var jumpCount = 0
    private var sessionStartAt = 0L

    @Volatile
    private var fullBodySince = 0L
    private val jumpCounter = JumpCounter()
    private var lastJumpAt = 0L
    private var lastMotionAt = 0L
    private var lastMotionLogAt = 0L
    private var sessionEndAt = 0L
    @Volatile
    private var isPaused = false
    private var pausedAt = 0L
    private var resumeCandidateCount = 0
    private var lastResumeCandidateAt = 0L
    private val resumeCandidateTimes = mutableListOf<Long>()
    private var permissionDialogVisible = false
    private val permissionPrefs by lazy {
        getSharedPreferences("permission", MODE_PRIVATE)
    }
    private var cameraPermissionRequested: Boolean
        get() = permissionPrefs.getBoolean("camera_requested", false)
        set(value) = permissionPrefs.edit().putBoolean("camera_requested", value).apply()
    private var lastPoseAt = 0L
    private var lastPoseLostLogAt = 0L
    private var lastStatusAt = 0L
    private var lastSignalLogAt = 0L

    private var lockHipX: Float? = null
    private var lockHipY: Float? = null
    private var lockTorso: Float? = null
    @Volatile
    private var isOccluded = false
    private var mismatchFrames = 0
    private var reacquireFrames = 0

    private var poseResultCount = 0
    private var poseFpsWindowStart = 0L
    private var cameraFrameCount = 0
    private var cameraFpsWindowStart = 0L

    private val sessionLogFile by lazy { File(filesDir, "workout-session.log") }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
            autoStartIfReady()
        } else {
            statusText.text = getString(R.string.status_permission_needed)
            startButton.text = getString(R.string.grant_permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = findViewById(R.id.previewView)
        countPanel = findViewById(R.id.countPanel)
        countText = findViewById(R.id.countText)
        timerText = findViewById(R.id.timerText)
        statusText = findViewById(R.id.statusText)
        countdownText = findViewById(R.id.countdownText)
        startButton = findViewById(R.id.startButton)
        modeText = findViewById(R.id.modeText)
        switchModeText = findViewById(R.id.switchModeText)
        hintText = findViewById(R.id.hintText)
        guideOverlay = findViewById(R.id.guideOverlay)
        rippleView = findViewById(R.id.rippleView)

        sessionMode = intent.getIntExtra(ModeActivity.EXTRA_MODE, ModeActivity.MODE_FREE)
        targetCount = intent.getIntExtra(ModeActivity.EXTRA_TARGET, 100)
        applyModeToUi()

        switchModeText.setOnClickListener {
            returnToModeSelection()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToModeSelection()
            }
        })

        setupPoseEstimator()

        startButton.setOnClickListener {
            when {
                !hasCameraPermission() -> showCameraPermissionDialog()
                isSessionRunning -> stopSession()
                else -> startSession()
            }
        }

        if (hasCameraPermission()) {
            startCamera()
            autoStartIfReady()
        } else {
            showCameraPermissionDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            startCamera()
            autoStartIfReady()
        }
    }

    private fun autoStartIfReady() {
        if (autoStartTriggered || isSessionRunning || !poseReady || !hasCameraPermission()) {
            return
        }
        autoStartTriggered = true
        startSession()
    }

    private fun applyModeToUi() {
        when (sessionMode) {
            ModeActivity.MODE_MINUTE -> {
                modeText.setText(R.string.mode_one_minute)
                hintText.setText(R.string.hint_one_minute)
            }
            ModeActivity.MODE_TARGET -> {
                modeText.text = getString(R.string.mode_target_format, targetCount)
                hintText.setText(R.string.hint_target)
            }
            else -> {
                modeText.setText(R.string.mode_free)
                hintText.setText(R.string.hint)
            }
        }
        if (!isSessionRunning) {
            timerText.text = getString(
                if (isMinuteMode()) R.string.timer_minute_default else R.string.timer_default
            )
        }
    }

    private fun isMinuteMode(): Boolean = sessionMode == ModeActivity.MODE_MINUTE

    private fun isTargetMode(): Boolean = sessionMode == ModeActivity.MODE_TARGET

    private fun returnToModeSelection() {
        if (isSessionRunning) {
            stopSession()
        }
        startActivity(
            Intent(this, ModeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (isSessionRunning) {
            stopSession()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (
            isSessionRunning &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            stopSession()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        poseEstimator?.close()
        poseEstimator = null
        replayRecorder.close()
        super.onDestroy()
    }

    private fun setupPoseEstimator() {
        try {
            poseEstimator = MoveNetPoseEstimator(this)
            poseReady = true
            logEvent("MoveNet Lightning loaded; gpu=${poseEstimator?.usingGpu}")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load MoveNet", error)
            poseReady = false
            statusText.text = getString(R.string.status_model_error)
            startButton.isEnabled = false
        }
    }

    private fun startCamera() {
        if (cameraBound) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener

            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(Size(640, 480))
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(30, 30)
                )
                val imageAnalysis = analysisBuilder.build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
                cameraBound = true
            } catch (error: Exception) {
                Log.e(TAG, "Failed to start camera", error)
                statusText.text = getString(R.string.status_camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!poseReady) {
            imageProxy.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        cameraFrameCount += 1
        if (cameraFpsWindowStart == 0L) cameraFpsWindowStart = now
        if (now - cameraFpsWindowStart >= 2000L) {
            val seconds = (now - cameraFpsWindowStart) / 1000f
            logEvent("Camera frames ${"%.1f".format(cameraFrameCount / seconds)} fps")
            cameraFrameCount = 0
            cameraFpsWindowStart = now
        }
        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            poseEstimator?.submit(imageProxy, rotation) { frame ->
                handlePoseFrame(frame)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to process camera frame", error)
        } finally {
            imageProxy.close()
        }
    }

    private fun handlePoseFrame(frame: PoseFrame?) {
        if (!isSessionRunning) return

        val now = SystemClock.elapsedRealtime()
        poseResultCount += 1
        if (poseFpsWindowStart == 0L) poseFpsWindowStart = now
        if (now - poseFpsWindowStart >= 2000L) {
            val seconds = (now - poseFpsWindowStart) / 1000f
            logEvent("Pose results ${"%.1f".format(poseResultCount / seconds)} fps")
            poseResultCount = 0
            poseFpsWindowStart = now
        }

        val landmarks = frame
        if (landmarks == null || landmarks.size < MoveNetIndex.COUNT) {            if (waitingForBody) {
                fullBodySince = 0L
                updateSessionStatus(R.string.status_waiting_full_body)
                logGateBlocked("no landmarks", null)
            } else if (isCounting || isPaused) {
                updateSessionStatus(R.string.status_no_person)
                logPoseLost("no landmarks")
                jumpCounter.process(null, now)
                markTargetOccluded()
            }
            return
        }

        replayRecorder.record(landmarks, now)

        if (waitingForBody) {
            if (isFullBodyVisible(landmarks)) {
                if (fullBodySince == 0L) {
                    fullBodySince = now
                }
                if (now - fullBodySince >= FULL_BODY_STABLE_MS) {
                    waitingForBody = false
                    setTargetLock(landmarks)
                    footCounter.calibrate(landmarks)
                    logEvent("Full body detected; starting countdown")
                    runOnUiThread {
                        if (isSessionRunning && !isCounting) {
                            statusText.text = getString(R.string.status_full_body_ready)
                            startCountdown(3)
                        }
                    }
                } else {
                    updateSessionStatus(R.string.status_full_body_hold)
                }
            } else {
                fullBodySince = 0L
                updateSessionStatus(R.string.status_waiting_full_body)
                logGateBlocked("landmarks incomplete", landmarks)
            }
            return
        }

        if (!isCounting && !isPaused) return

        val bodyMotion = poseEstimator?.motionLevel ?: 0f
        if (now - lastMotionLogAt >= 2000L) {
            lastMotionLogAt = now
            logEvent("Motion level=${"%.2f".format(bodyMotion)}")
        }
        if (bodyMotion >= MOTION_LEVEL_THRESHOLD) {
            lastMotionAt = now
        } else if (isCounting || isPaused) {
            // Standing-still ankle jitter must not generate landing events,
            // including while paused and waiting for resume candidates.
            updateSessionStatus(R.string.status_no_motion)
            return
        }

        val metrics = targetMetrics(landmarks)
        if (metrics == null) {
            updateSessionStatus(R.string.status_no_person)
            logPoseLost("shoulders or hips invisible")
            jumpCounter.process(null, now)
            markTargetOccluded()
            return
        }
        if (!matchesTarget(metrics)) {
            if (isOccluded) return
            mismatchFrames += 1
            if (mismatchFrames >= 15) {
                markTargetOccluded()
                return
            }
        } else {
            mismatchFrames = 0
            if (isOccluded) {
                reacquireFrames += 1
                if (reacquireFrames < 2) {
                    return
                }
                markTargetReacquired(now)
            }
            reacquireFrames = 0
            updateTargetLock(metrics)
        }

        if (lastPoseAt > 0L && now - lastPoseAt > POSE_LOST_RESET_MS) {
            jumpCounter.reset()
            clearResumeCandidates()
            logEvent("Pose gap ${now - lastPoseAt}ms; resetting counter")
        }
        lastPoseAt = now

        val signal = PoseSignal.extract(landmarks, MIN_VISIBILITY)
        if (signal == null) {
            updateSessionStatus(R.string.status_no_person)
            logPoseLost("shoulders or hips invisible")
            jumpCounter.process(null, now)
            markTargetOccluded()
            return
        }
        val counted = jumpCounter.process(signal, now)
        val footPoint = lowestFootPoint(landmarks)
        if (counted && footPoint != null) {
            val guide = guideOverlay
            val screenX = guide.left + footPoint.first * guide.width
            val screenY = guide.top + footPoint.second * guide.height
            runOnUiThread { rippleView.showAt(screenX, screenY) }
        }

        if (now - lastSignalLogAt >= 2000L) {
            lastSignalLogAt = now
            logEvent(
                "Signal amp=${jumpCounter.state.amplitude} " +
                    "phase=${jumpCounter.state.phase}"
            )
        }

        if (counted) {
            if (isCounting) {
                jumpCount += 1
                lastJumpAt = now
                val reachedTarget = isTargetMode() && jumpCount >= targetCount
                logEvent(
                    "Jump #$jumpCount signal=$signal amp=${jumpCounter.state.amplitude}"
                )
                runOnUiThread {
                    countText.text = jumpCount.toString()
                    if (reachedTarget) {
                        stopSession(targetReached = true)
                    }
                }
            } else if (isPaused) {
                val gap = if (lastResumeCandidateAt == 0L) Long.MAX_VALUE else now - lastResumeCandidateAt
                if (lastResumeCandidateAt != 0L && gap > RESUME_CANDIDATE_MAX_GAP_MS) {
                    clearResumeCandidates()
                }
                val canAdd = lastResumeCandidateAt == 0L ||
                    gap in RESUME_CANDIDATE_MIN_GAP_MS..RESUME_CANDIDATE_MAX_GAP_MS
                if (canAdd) {
                    resumeCandidateTimes.add(now)
                    lastResumeCandidateAt = now
                    resumeCandidateCount = resumeCandidateTimes.size
                    if (resumeCandidateCount >= RESUME_CANDIDATES_REQUIRED) {
                        val intervals = resumeCandidateTimes
                            .zipWithNext { first, second -> second - first }
                        val validCadence = intervals.all {
                            it in RESUME_CANDIDATE_MIN_GAP_MS..RESUME_CANDIDATE_MAX_GAP_MS
                        }
                        if (validCadence) {
                            val pending = resumeCandidateCount
                            clearResumeCandidates()
                            resumeCounting(now)
                            jumpCount += pending
                            lastJumpAt = now
                            val reachedTarget = isTargetMode() && jumpCount >= targetCount
                            logEvent("Resumed; committed $pending verified jumps")
                            runOnUiThread {
                                countText.text = jumpCount.toString()
                                if (reachedTarget) {
                                    stopSession(targetReached = true)
                                }
                            }
                        } else {
                            // Keep the newest two candidates and wait for a
                            // third one that forms a plausible jump cadence.
                            while (resumeCandidateTimes.size >= RESUME_CANDIDATES_REQUIRED) {
                                resumeCandidateTimes.removeAt(0)
                            }
                            resumeCandidateCount = resumeCandidateTimes.size
                            logEvent("Resume candidates rejected: irregular cadence")
                        }
                    } else {
                        logEvent("Resume candidate $resumeCandidateCount/$RESUME_CANDIDATES_REQUIRED")
                    }
                }
            }
        }

        updateSessionStatus(
            if (isPaused) R.string.status_paused else R.string.status_counting
        )
    }

    private fun logPoseLost(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoseLostLogAt < POSE_LOG_INTERVAL_MS) return
        lastPoseLostLogAt = now
        logEvent("Pose lost: $reason")
    }

    private fun logGateBlocked(reason: String, landmarks: PoseFrame?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoseLostLogAt < POSE_LOG_INTERVAL_MS) return
        lastPoseLostLogAt = now
        if (landmarks == null || landmarks.size < MoveNetIndex.COUNT) {
            logEvent("Gate blocked: $reason")
        } else {
            logEvent(
                "Gate blocked: $reason nose=${landmarks[MoveNetIndex.NOSE].x},${landmarks[MoveNetIndex.NOSE].y}/${landmarks[MoveNetIndex.NOSE].visibility} " +
                    "shoulders=${landmarks[MoveNetIndex.LEFT_SHOULDER].visibility}/${landmarks[MoveNetIndex.RIGHT_SHOULDER].visibility} " +
                    "hips=${landmarks[MoveNetIndex.LEFT_HIP].visibility}/${landmarks[MoveNetIndex.RIGHT_HIP].visibility} " +
                    "knees=${landmarks[MoveNetIndex.LEFT_KNEE].visibility}/${landmarks[MoveNetIndex.RIGHT_KNEE].visibility} " +
                    "ankles=${landmarks[MoveNetIndex.LEFT_ANKLE].visibility}/${landmarks[MoveNetIndex.RIGHT_ANKLE].visibility}"
            )
        }
    }

    private fun logEvent(message: String) {
        Log.d(TAG, message)
        if (!BuildConfig.DEBUG) return
        try {
            sessionLogFile.appendText("${System.currentTimeMillis()} $message\n")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to write session log", error)
        }
    }

    private data class TargetMetrics(
        val hipX: Float,
        val hipY: Float,
        val torso: Float
    )

    private fun targetMetrics(landmarks: PoseFrame): TargetMetrics? {
        if (landmarks.size < MoveNetIndex.COUNT) return null
        val leftShoulder = landmarks[MoveNetIndex.LEFT_SHOULDER]
        val rightShoulder = landmarks[MoveNetIndex.RIGHT_SHOULDER]
        val leftHip = landmarks[MoveNetIndex.LEFT_HIP]
        val rightHip = landmarks[MoveNetIndex.RIGHT_HIP]
        if (
            leftShoulder.visibility < MIN_VISIBILITY ||
            rightShoulder.visibility < MIN_VISIBILITY ||
            leftHip.visibility < MIN_VISIBILITY ||
            rightHip.visibility < MIN_VISIBILITY
        ) {
            return null
        }
        val shoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val hipX = (leftHip.x + rightHip.x) / 2f
        val hipY = (leftHip.y + rightHip.y) / 2f
        val torso = abs(hipY - shoulderY)
        if (torso < 0.01f) return null
        return TargetMetrics(hipX, hipY, torso)
    }

    private fun setTargetLock(landmarks: PoseFrame) {
        val metrics = targetMetrics(landmarks) ?: return
        lockHipX = metrics.hipX
        lockHipY = metrics.hipY
        lockTorso = metrics.torso
        logEvent("Target locked at hip=(${metrics.hipX},${metrics.hipY}) torso=${metrics.torso}")
    }

    private fun matchesTarget(metrics: TargetMetrics): Boolean {
        val lockedX = lockHipX ?: run {
            lockHipX = metrics.hipX
            lockHipY = metrics.hipY
            lockTorso = metrics.torso
            return true
        }
        val lockedY = lockHipY ?: metrics.hipY
        val lockedTorso = lockTorso ?: metrics.torso
        val torsoRatio = metrics.torso / lockedTorso
        return abs(metrics.hipX - lockedX) <= LOCK_MAX_DX &&
            abs(metrics.hipY - lockedY) <= LOCK_MAX_DY &&
            torsoRatio in LOCK_TORSO_MIN..LOCK_TORSO_MAX
    }

    private fun updateTargetLock(metrics: TargetMetrics) {
        lockHipX = (lockHipX ?: metrics.hipX) * 0.9f + metrics.hipX * 0.1f
        lockHipY = (lockHipY ?: metrics.hipY) * 0.9f + metrics.hipY * 0.1f
        lockTorso = (lockTorso ?: metrics.torso) * 0.9f + metrics.torso * 0.1f
    }

    private fun markTargetOccluded() {
        if (isOccluded) return
        isOccluded = true
        mismatchFrames = 0
        reacquireFrames = 0
        jumpCounter.reset()
        runOnUiThread {
            if (isSessionRunning) {
                statusText.text = getString(R.string.status_occluded)
            }
        }
        logEvent("Target occluded; waiting for reacquire")
    }

    private fun markTargetReacquired(now: Long) {
        if (!isOccluded) return
        isOccluded = false
        mismatchFrames = 0
        reacquireFrames = 0
        jumpCounter.reset()
        lastJumpAt = now
        runOnUiThread {
            if (isSessionRunning) {
                statusText.text = getString(
                    if (isPaused) R.string.status_paused else R.string.status_counting
                )
            }
        }
        logEvent("Target reacquired; resuming count")
    }

    private fun isFullBodyVisible(landmarks: PoseFrame): Boolean {
        val required = intArrayOf(
            MoveNetIndex.LEFT_SHOULDER,
            MoveNetIndex.RIGHT_SHOULDER,
            MoveNetIndex.LEFT_HIP,
            MoveNetIndex.RIGHT_HIP,
            MoveNetIndex.LEFT_KNEE,
            MoveNetIndex.RIGHT_KNEE
        )
        if (required.any { landmarks[it].visibility < FULL_BODY_VISIBILITY }) return false

        val head = headPoint(landmarks)
        if (head.visibility < FULL_BODY_VISIBILITY) return false

        val leftAnkleVisibility = landmarks[MoveNetIndex.LEFT_ANKLE].visibility
        val rightAnkleVisibility = landmarks[MoveNetIndex.RIGHT_ANKLE].visibility
        if (maxOf(leftAnkleVisibility, rightAnkleVisibility) < FULL_BODY_VISIBILITY) return false

        val noseY = head.y
        val shoulderY = (
            landmarks[MoveNetIndex.LEFT_SHOULDER].y +
                landmarks[MoveNetIndex.RIGHT_SHOULDER].y
            ) / 2f
        val hipY = (
            landmarks[MoveNetIndex.LEFT_HIP].y +
                landmarks[MoveNetIndex.RIGHT_HIP].y
            ) / 2f
        val kneeY = (
            landmarks[MoveNetIndex.LEFT_KNEE].y +
                landmarks[MoveNetIndex.RIGHT_KNEE].y
            ) / 2f
        val ankleY = if (leftAnkleVisibility >= rightAnkleVisibility) {
            landmarks[MoveNetIndex.LEFT_ANKLE].y
        } else {
            landmarks[MoveNetIndex.RIGHT_ANKLE].y
        }

        if (noseY < 0.01f || ankleY > 0.99f) return false

        return noseY + 0.03f < shoulderY &&
            shoulderY + 0.03f < hipY &&
            hipY + 0.03f < kneeY &&
            kneeY + 0.03f < ankleY
    }

    private fun headPoint(landmarks: PoseFrame): PosePoint {
        var best = landmarks[MoveNetIndex.NOSE]
        for (index in MoveNetIndex.NOSE..4) {
            if (landmarks[index].visibility > best.visibility) {
                best = landmarks[index]
            }
        }
        return best
    }

    private fun startSession() {
        if (!poseReady) {
            statusText.text = getString(R.string.status_model_error)
            return
        }

        isSessionRunning = true
        waitingForBody = true
        isCounting = false
        isPaused = false
        jumpCount = 0
        sessionStartAt = 0L
        sessionEndAt = 0L
        pausedAt = 0L
        clearResumeCandidates()
        fullBodySince = 0L
        jumpCounter.reset()
        footCounter.reset()
        lockHipX = null
        lockHipY = null
        lockTorso = null
        isOccluded = false
        mismatchFrames = 0
        reacquireFrames = 0
        lastJumpAt = 0L
        lastMotionAt = 0L
        lastMotionLogAt = 0L
        lastPoseAt = 0L
        lastPoseLostLogAt = 0L
        lastStatusAt = 0L
        lastSignalLogAt = 0L
        poseResultCount = 0
        poseFpsWindowStart = 0L

        try {
            logEvent("===== NEW SESSION =====")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start session log", error)
        }
        logEvent("Session started; waiting for full body; mode=$sessionMode target=$targetCount")
        replayRecorder.start(sessionMode, targetCount)

        countText.text = getString(R.string.count_default)
        countPanel.visibility = View.INVISIBLE
        countdownText.visibility = View.GONE
        timerText.text = getString(
            if (isMinuteMode()) R.string.timer_minute_default else R.string.timer_default
        )
        startButton.text = getString(R.string.cancel)
        statusText.text = getString(R.string.status_waiting_full_body)
        switchModeText.isEnabled = false
        switchModeText.alpha = 0.4f
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startCountdown(seconds: Int) {
        if (!isSessionRunning) return

        isCounting = false
        countPanel.visibility = View.INVISIBLE
        startButton.text = getString(R.string.cancel)

        if (seconds > 0) {
            countdownText.visibility = View.VISIBLE
            countdownText.text = seconds.toString()
            handler.postDelayed({ startCountdown(seconds - 1) }, 700L)
        } else {
            countdownText.text = getString(R.string.countdown_go)
            handler.postDelayed({
                if (!isSessionRunning) return@postDelayed
                countdownText.visibility = View.GONE
                countPanel.visibility = View.VISIBLE
                isCounting = true
                sessionStartAt = SystemClock.elapsedRealtime()
                sessionEndAt = 0L
                lastMotionAt = SystemClock.elapsedRealtime()
                footCounter.resetPhase()
                statusText.text = getString(R.string.status_counting)
                startButton.text = getString(R.string.stop)
                logEvent("Countdown finished; counting started")
                startTimer()
                if (!isMinuteMode()) {
                    startAutoPause()
                }
            }, 450L)
        }
    }

    private fun startTimer() {
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isCounting) return
            val elapsed = SystemClock.elapsedRealtime() - sessionStartAt
            if (isMinuteMode()) {
                val remaining = MODE_DURATION_MS - elapsed
                if (remaining <= 0L) {
                    timerText.text = formatDuration(0L)
                    stopSession(timedOut = true)
                    return
                }
                timerText.text = formatDuration(remaining)
                handler.postDelayed(this, 100L)
            } else {
                timerText.text = formatDuration(elapsed)
                handler.postDelayed(this, 200L)
            }
        }
    }

    private fun startAutoPause() {
        handler.removeCallbacks(autoPauseRunnable)
        handler.post(autoPauseRunnable)
    }

    private val autoPauseRunnable = object : Runnable {
        override fun run() {
            if (!isCounting) return
            if (isOccluded) {
                handler.postDelayed(this, 200L)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (jumpCount > 0 && now - lastMotionAt >= MOTION_PAUSE_MS) {
                pauseCounting()
                return
            }
            handler.postDelayed(this, 200L)
        }
    }

    private fun pauseCounting() {
        if (!isCounting) return
        isCounting = false
        isPaused = true
        pausedAt = SystemClock.elapsedRealtime()
        sessionEndAt = pausedAt
        clearResumeCandidates()
        isOccluded = false
        mismatchFrames = 0
        reacquireFrames = 0
        handler.removeCallbacks(autoPauseRunnable)
        handler.removeCallbacks(timerRunnable)
        jumpCounter.reset()
        footCounter.resetPhase()

        if (sessionStartAt > 0L) {
            timerText.text = formatDuration(pausedAt - sessionStartAt)
        }
        statusText.text = getString(R.string.status_paused)
        logEvent(
            "Auto pause after ${pausedAt - lastJumpAt}ms idle; count=$jumpCount"
        )
    }

    private fun resumeCounting(now: Long) {
        if (!isPaused) return
        val pauseDuration = now - pausedAt
        if (pauseDuration > 0L) {
            sessionStartAt += pauseDuration
        }
        isPaused = false
        isCounting = true
        pausedAt = 0L
        sessionEndAt = 0L
        clearResumeCandidates()
        lastJumpAt = now
        lastMotionAt = now
        jumpCounter.reset()
        footCounter.resetPhase()
        runOnUiThread {
            statusText.text = getString(R.string.status_counting)
            startButton.text = getString(R.string.stop)
        }
        logEvent("Resumed after ${pauseDuration}ms pause")
        startTimer()
        startAutoPause()
    }

    private fun stopSession(timedOut: Boolean = false, targetReached: Boolean = false) {
        isSessionRunning = false
        waitingForBody = false
        isCounting = false
        isPaused = false
        pausedAt = 0L
        clearResumeCandidates()
        fullBodySince = 0L
        jumpCounter.reset()
        footCounter.reset()
        lockHipX = null
        lockHipY = null
        lockTorso = null
        isOccluded = false
        mismatchFrames = 0
        reacquireFrames = 0
        lastPoseAt = 0L
        handler.removeCallbacksAndMessages(null)
        countdownText.visibility = View.GONE
        countPanel.visibility = View.VISIBLE
        switchModeText.isEnabled = true
        switchModeText.alpha = 1f
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (timedOut) {
            timerText.text = formatDuration(0L)
        } else if (sessionStartAt > 0L) {
            val endAt = if (sessionEndAt > 0L) sessionEndAt else SystemClock.elapsedRealtime()
            if (isMinuteMode()) {
                val remaining = MODE_DURATION_MS - (endAt - sessionStartAt)
                timerText.text = formatDuration(remaining.coerceAtLeast(0L))
            } else {
                timerText.text = formatDuration(endAt - sessionStartAt)
            }
        }

        startButton.text = getString(R.string.restart)
        statusText.text = when {
            targetReached -> getString(R.string.status_target_done, jumpCount)
            timedOut -> getString(R.string.status_time_up, jumpCount)
            sessionStartAt > 0L || jumpCount > 0 -> getString(R.string.status_stopped, jumpCount)
            else -> getString(R.string.status_cancelled)
        }
        logEvent(
            "Session stopped; timedOut=$timedOut targetReached=$targetReached count=$jumpCount"
        )
        replayRecorder.stop(jumpCount)
        sessionEndAt = 0L
    }

    private fun updateSessionStatus(resId: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusAt < STATUS_INTERVAL_MS) return
        lastStatusAt = now
        runOnUiThread {
            if (isSessionRunning) {
                statusText.text = getString(resId)
            }
        }
    }

    private fun requestCameraPermission() {
        cameraPermissionRequested = true
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showCameraPermissionDialog() {
        if (isFinishing || permissionDialogVisible) return
        val permanentlyDenied = cameraPermissionRequested &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (permanentlyDenied) {
            showCameraSettingsDialog()
            return
        }
        permissionDialogVisible = true
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_camera_permission)
            .setTitle(R.string.permission_dialog_title)
            .setMessage(R.string.permission_dialog_message)
            .setPositiveButton(R.string.permission_dialog_grant) { _, _ ->
                requestCameraPermission()
            }
            .setNegativeButton(R.string.permission_dialog_later) { _, _ ->
                statusText.text = getString(R.string.status_permission_needed)
                startButton.text = getString(R.string.grant_permission)
            }
            .setOnDismissListener { permissionDialogVisible = false }
            .show()
    }

    private fun showCameraSettingsDialog() {
        permissionDialogVisible = true
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_camera_permission)
            .setTitle(R.string.permission_settings_title)
            .setMessage(R.string.permission_settings_message)
            .setPositiveButton(R.string.permission_settings_grant) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.permission_dialog_later) { _, _ ->
                statusText.text = getString(R.string.status_permission_needed)
                startButton.text = getString(R.string.grant_permission)
            }
            .setOnDismissListener { permissionDialogVisible = false }
            .show()
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to open app settings", error)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun clearResumeCandidates() {
        resumeCandidateCount = 0
        lastResumeCandidateAt = 0L
        resumeCandidateTimes.clear()
    }

    private fun visibility(landmark: PosePoint): Float {
        return landmark.visibility
    }

    private fun lowestFootPoint(landmarks: PoseFrame): Pair<Float, Float>? {
        val left = landmarks[MoveNetIndex.LEFT_ANKLE]
        val right = landmarks[MoveNetIndex.RIGHT_ANKLE]
        if (left.visibility < 0.2f && right.visibility < 0.2f) return null
        return if (left.visibility < 0.2f) {
            right.x to right.y
        } else if (right.visibility < 0.2f) {
            left.x to left.y
        } else if (left.y >= right.y) {
            left.x to left.y
        } else {
            right.x to right.y
        }
    }

    private fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MIN_VISIBILITY = 0.2f
        private const val FULL_BODY_VISIBILITY = 0.25f
        private const val FULL_BODY_STABLE_MS = 650L
        private const val POSE_LOST_RESET_MS = 700L
        private const val AUTO_PAUSE_MS = 3000L
        private const val MOTION_PAUSE_MS = 2500L
        private const val MOTION_LEVEL_THRESHOLD = 1.0f
        private const val LOCK_MAX_DX = 0.80f
        private const val LOCK_MAX_DY = 0.90f
        private const val LOCK_TORSO_MIN = 0.25f
        private const val LOCK_TORSO_MAX = 3.00f
        private const val MODE_DURATION_MS = 60_000L
        private const val RESUME_CANDIDATES_REQUIRED = 3
        private const val RESUME_CANDIDATE_MIN_GAP_MS = 250L
        private const val RESUME_CANDIDATE_MAX_GAP_MS = 900L
        private const val POSE_LOG_INTERVAL_MS = 2000L
        private const val STATUS_INTERVAL_MS = 700L
    }
}
