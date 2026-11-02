# Third-party notices

WorkoutMate is an offline jump-rope counting app. The following open-source
components are used in the application. All listed components are distributed
under the Apache License, Version 2.0 unless otherwise stated.

## AndroidX

- AndroidX Core KTX
- AndroidX AppCompat
- AndroidX Activity KTX
- AndroidX ConstraintLayout
- AndroidX CameraX (camera-core, camera-camera2, camera-lifecycle, camera-view)
- Upstream: https://developer.android.com/jetpack/androidx
- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0

## Material Components for Android

- Upstream: https://github.com/material-components/material-components-android
- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0

## TensorFlow Lite

- Upstream: https://www.tensorflow.org/lite
- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0

## Kotlin runtime

- Upstream: https://kotlinlang.org/
- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0

## MoveNet Lightning

The pose estimation model in `app/src/main/assets/movenet_lightning.tflite` is
downloaded from TensorFlow Hub's MoveNet single-pose Lightning model.

- Upstream model card: https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/int8/4
- Runtime: TensorFlow Lite (`org.tensorflow:tensorflow-lite`)
- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0

## HappyJumping (reference implementation)

Parts of the jump counting pipeline (`JumpCounter.kt`, `PoseSignal`) are adapted
from [hanklzl/HappyJumping](https://github.com/hanklzl/HappyJumping), which is
licensed under the Apache License, Version 2.0.

- Upstream: https://github.com/hanklzl/HappyJumping
- License: https://www.apache.org/licenses/LICENSE-2.0
- Reference files:
  - `app/src/main/java/com/example/happyjumping/core/JumpCounter.kt`
  - `app/src/main/java/com/example/happyjumping/core/PoseSignal.kt`
  - `app/src/main/java/com/example/happyjumping/pose/MediaPipePoseDetector.kt`
    (rotation/delegate approach reference)

## Full license text

The full Apache License 2.0 text is available at:

https://www.apache.org/licenses/LICENSE-2.0

For app-store distribution, ship this notice with the application or include it
in the hosted privacy policy / legal page. Preserve the upstream copyright and
attribution notices.
