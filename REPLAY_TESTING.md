# Replay regression testing

This project records the 17 MoveNet keypoints used by the live counter, so an
algorithm change can be replayed against the same pose sequence instead of
being tuned by feel.

## 1. Record a real session

Install the debug build, enter a counting mode, and complete a set with a known
ground-truth count.

The app writes one JSONL file per session under:

```text
/data/data/com.example.workoutmate/files/replay-sessions/
```

Pull it with:

```powershell
adb shell run-as com.example.workoutmate ls files/replay-sessions
adb shell run-as com.example.workoutmate cat files/replay-sessions/session_XXXX.jsonl > session_XXXX.jsonl
```

Each line is either a header, a `frame` with 17 `[x, y, confidence]` points and
a timestamp, or a summary with the app's count.

## 2. Add the ground truth and create a fixture

You know how many jumps were actually performed. Convert the recording into a
fixture and put it under `app/src/test/resources/replay/`:

```powershell
python tools/prepare_replay_fixture.py session_XXXX.jsonl 70 `
  app/src/test/resources/replay/real_70.jsonl
```

`70` is the real count. The generated fixture starts with `# expected=70`.
The app's own count is retained in the header as `recorded_count`, for
comparison, but is not used as the expected value.

## 3. Run all fixtures

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
```

The test prints one line per fixture:

```text
Replay real_70.jsonl: expected=70 actual=67 error=3 (4.3%) lastT=...
```

It fails only when the error is larger than
`max(2, 5% of expected)`, so small boundary losses are visible but do not block
every change.

## Why keypoints instead of video?

A video would need a second MoveNet inference pass, which can produce different
keypoints from the live run. Recording the exact keypoints makes the replay
deterministic and keeps the regression focused on the counting algorithm.
Video can still be kept separately for visual review.

## Current limitation

`Motion level` comes from camera frame differences, not from the keypoint
sequence. The replay test covers the pose signal and counter; it does not
replay the camera motion gate. Keep that in mind when comparing replay and live
counts.
