#!/usr/bin/env python3
"""Phase 2 offline prototype: window-level jump / non-jump classifier.

The model is intentionally small and interpretable:
  - 0.8 s pose windows, 0.2 s stride
  - normalized vertical signal + motion/visibility/periodicity features
  - L2 logistic regression trained with class weighting
  - leave-one-session-out validation (no window leakage)

It is a feature/classifier prototype only. The live app still uses the existing
counter until this model has been validated on more sessions.
"""
import csv
import json
import math
import pathlib
import statistics

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
SESSION_DIR = ROOT / "data" / "sessions"
NEGATIVE_DIR = ROOT / "build" / "negatives_20261109"
MANIFEST = ROOT / "data" / "phase2_manifest.csv"
MODEL_OUT = ROOT / "data" / "jump_gate_model.json"

FS = 30.0
WINDOW_S = 0.8
STRIDE_S = 0.2
MIN_VALID = 6
# The participating children withdrew from data collection. Keep the raw
# files, but do not use under-18 sessions in the adult-first model.
ADULT_ONLY = True
FEATURE_NAMES = [
    "amp",
    "std",
    "mad",
    "zcr",
    "peaks",
    "ac_peak",
    "ac_period",
    "hip_y_range",
    "hip_x_range",
    "torso_mean",
    "torso_std",
    "ankle_range",
    "knee_range",
    "visibility_mean",
    "valid_ratio",
    "hip_deriv",
    "shoulder_hip_range",
    "head_y_range",
    "head_x_range",
    "wrist_y_range",
    "wrist_x_range",
    "hand_head_min",
    "hand_head_mean",
]


def load_frames(path):
    frames = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        item = json.loads(line)
        if item.get("type") == "frame":
            frames.append(item)
    return frames


def frame_features(frame):
    p = frame["p"]
    if len(p) < 17:
        return None
    # Required shoulder / hip points.
    required = (5, 6, 11, 12)
    if any(p[i][2] < 0.2 for i in required):
        return None
    shoulder_x = (p[5][0] + p[6][0]) / 2.0
    shoulder_y = (p[5][1] + p[6][1]) / 2.0
    hip_x = (p[11][0] + p[12][0]) / 2.0
    hip_y = (p[11][1] + p[12][1]) / 2.0
    torso = math.hypot(hip_x - shoulder_x, hip_y - shoulder_y)
    if torso < 1e-3:
        return None
    ankles = [p[15], p[16]]
    knee = [p[13], p[14]]
    # Lowest ankle is the most likely planted foot.
    ankle_y = max(q[1] for q in ankles if q[2] >= 0.2) if any(q[2] >= 0.2 for q in ankles) else None
    knee_y = max(q[1] for q in knee if q[2] >= 0.2) if any(q[2] >= 0.2 for q in knee) else None
    heads = [q for q in p[0:5] if q[2] >= 0.2]
    wrists = [q for q in p[9:11] if q[2] >= 0.2]
    head = None
    if heads:
        head = (
            sum(q[0] for q in heads) / len(heads),
            sum(q[1] for q in heads) / len(heads),
        )
    wrist = None
    if wrists:
        wrist = (
            sum(q[0] for q in wrists) / len(wrists),
            sum(q[1] for q in wrists) / len(wrists),
        )
    hand_head = None
    if head is not None and wrist is not None:
        hand_head = math.hypot(head[0] - wrist[0], head[1] - wrist[1]) / torso
    visibility = statistics.mean(q[2] for q in p)
    return {
        "t": frame["t"] / 1000.0,
        "signal": hip_y / torso,
        "hip_x": hip_x / torso,
        "hip_y": hip_y / torso,
        "torso": torso,
        "ankle_y": ankle_y / torso if ankle_y is not None else None,
        "knee_y": knee_y / torso if knee_y is not None else None,
        "visibility": visibility,
        "shoulder_hip": (shoulder_y - hip_y) / torso,
        "head_x": head[0] / torso if head is not None else None,
        "head_y": head[1] / torso if head is not None else None,
        "wrist_x": wrist[0] / torso if wrist is not None else None,
        "wrist_y": wrist[1] / torso if wrist is not None else None,
        "hand_head": hand_head,
    }


def parse_segments(spec):
    segments = []
    if not spec:
        return segments
    for item in spec.split(";"):
        item = item.strip()
        if not item:
            continue
        time_part, label = item.split(":", 1)
        start_s, end_s = time_part.split("-", 1)
        segments.append((float(start_s), float(end_s), label))
    return segments


def label_at(t, segments):
    for start, end, label in segments:
        if start <= t < end:
            return 1 if label == "jump" else 0
    return 0


def window_vector(rows):
    signals = [r["signal"] for r in rows]
    hip_y = [r["hip_y"] for r in rows]
    hip_x = [r["hip_x"] for r in rows]
    torsos = [r["torso"] for r in rows]
    ankles = [r["ankle_y"] for r in rows if r["ankle_y"] is not None]
    knees = [r["knee_y"] for r in rows if r["knee_y"] is not None]
    visibility = [r["visibility"] for r in rows]
    shoulder_hip = [r["shoulder_hip"] for r in rows]
    head_y = [r["head_y"] for r in rows if r["head_y"] is not None]
    head_x = [r["head_x"] for r in rows if r["head_x"] is not None]
    wrist_y = [r["wrist_y"] for r in rows if r["wrist_y"] is not None]
    wrist_x = [r["wrist_x"] for r in rows if r["wrist_x"] is not None]
    hand_head = [r["hand_head"] for r in rows if r["hand_head"] is not None]
    s = np.asarray(signals, dtype=float)
    amp = float(np.percentile(s, 95) - np.percentile(s, 5))
    std = float(np.std(s))
    mad = float(np.mean(np.abs(np.diff(s)))) if len(s) > 1 else 0.0
    centered = s - s.mean()
    zcr = float(np.sum(np.diff(np.sign(centered)) != 0)) / max(1, len(s) - 1)
    peaks = 0
    for i in range(1, len(s) - 1):
        if s[i] > s[i - 1] and s[i] >= s[i + 1] and s[i] > s.mean():
            peaks += 1
    ac_peak = 0.0
    ac_period = 0.0
    if len(centered) >= 8 and np.dot(centered, centered) > 1e-8:
        ac = np.correlate(centered, centered, mode="full")[len(centered) - 1:]
        ac = ac / ac[0]
        min_lag = max(3, int(0.2 * FS))
        max_lag = min(len(ac) - 2, int(0.9 * FS))
        if max_lag > min_lag:
            lag = min_lag + int(np.argmax(ac[min_lag:max_lag + 1]))
            ac_peak = float(ac[lag])
            ac_period = lag / FS
    return [
        amp,
        std,
        mad,
        zcr,
        float(peaks),
        ac_peak,
        ac_period,
        float(max(hip_y) - min(hip_y)),
        float(max(hip_x) - min(hip_x)),
        float(np.mean(torsos)),
        float(np.std(torsos)),
        float(max(ankles) - min(ankles)) if ankles else 0.0,
        float(max(knees) - min(knees)) if knees else 0.0,
        float(np.mean(visibility)),
        float(len(rows) / (WINDOW_S * FS)),
        float(np.mean(np.abs(np.diff(hip_y)))) if len(hip_y) > 1 else 0.0,
        float(max(shoulder_hip) - min(shoulder_hip)),
        float(max(head_y) - min(head_y)) if head_y else 0.0,
        float(max(head_x) - min(head_x)) if head_x else 0.0,
        float(max(wrist_y) - min(wrist_y)) if wrist_y else 0.0,
        float(max(wrist_x) - min(wrist_x)) if wrist_x else 0.0,
        float(min(hand_head)) if hand_head else 0.0,
        float(np.mean(hand_head)) if hand_head else 0.0,
    ]


def build_windows(session_id, path, segments):
    frames = load_frames(path)
    rows = []
    for frame in frames:
        item = frame_features(frame)
        if item is not None:
            rows.append(item)
    if not rows:
        return [], []
    t0 = rows[0]["t"]
    for row in rows:
        row["t"] -= t0
    features = []
    labels = []
    centers = []
    end_t = rows[-1]["t"]
    start = 0.0
    while start + WINDOW_S <= end_t:
        window = [r for r in rows if start <= r["t"] <= start + WINDOW_S]
        if len(window) >= MIN_VALID:
            center = start + WINDOW_S / 2
            features.append(window_vector(window))
            labels.append(label_at(center, segments))
            centers.append(center)
        start += STRIDE_S
    print(
        f"{session_id}: frames={len(frames)} valid={len(rows)} "
        f"windows={len(features)} positives={sum(labels)}"
    )
    return features, labels


def train_logistic(X, y, sample_weight=None, epochs=1200, lr=0.15, l2=0.002):
    n, d = X.shape
    weights = np.zeros(d)
    bias = 0.0
    if sample_weight is None:
        sample_weight = np.ones(n)
    for _ in range(epochs):
        logits = X @ weights + bias
        probs = 1.0 / (1.0 + np.exp(-np.clip(logits, -30, 30)))
        error = (probs - y) * sample_weight
        grad_w = (X.T @ error) / n + l2 * weights
        grad_b = float(error.mean())
        weights -= lr * grad_w
        bias -= lr * grad_b
    return weights, bias


def predict(X, weights, bias):
    logits = X @ weights + bias
    return 1.0 / (1.0 + np.exp(-np.clip(logits, -30, 30)))


def auc_score(y, scores):
    pos = scores[np.asarray(y) == 1]
    neg = scores[np.asarray(y) == 0]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    return float((pos[:, None] > neg[None, :]).mean() + 0.5 * (pos[:, None] == neg[None, :]).mean())


def main():
    with MANIFEST.open(encoding="utf-8") as handle:
        manifest = list(csv.DictReader(handle))

    sessions = []
    for row in manifest:
        if ADULT_ONLY and row.get("age", "").strip():
            try:
                if int(row["age"]) < 18:
                    print(f"exclude under-18 session: {row['session_id']}")
                    continue
            except ValueError:
                pass
        path = SESSION_DIR / f"session_{row['session_id']}.jsonl"
        if not path.exists():
            print("missing", path)
            continue
        segments = parse_segments(row.get("segments", ""))
        features, labels = build_windows(row["session_id"], path, segments)
        if features:
            sessions.append((row["group"], np.asarray(features), np.asarray(labels)))

    # Pure negative sessions: standing, rope adjustment. The child-irruption
    # clip is excluded while the model is adult-only.
    negative_sessions = [
        ("1794224539635", "standing"),
        ("1794224585167", "rope_adjustment"),
    ]
    if not ADULT_ONLY:
        negative_sessions.append(("1794224641880", "child_irruption"))
    for sid, action in negative_sessions:
        path = NEGATIVE_DIR / f"session_{sid}.jsonl"
        if not path.exists():
            continue
        features, labels = build_windows(sid, path, [])
        if features:
            sessions.append((action, np.asarray(features), np.asarray(labels)))

    if len(sessions) < 2:
        print("Not enough sessions")
        return

    # Leave-one-session-out validation.
    all_scores = []
    all_labels = []
    print("\nLeave-one-session-out:")
    for held_out in range(len(sessions)):
        train = [s for i, s in enumerate(sessions) if i != held_out]
        test = sessions[held_out]
        X_train = np.vstack([s[1] for s in train])
        y_train = np.concatenate([s[2] for s in train])
        X_test, y_test = test[1], test[2]
        mean = X_train.mean(axis=0)
        std = X_train.std(axis=0)
        std[std < 1e-6] = 1.0
        Xt = (X_train - mean) / std
        Xv = (X_test - mean) / std
        pos = max(1, int(y_train.sum()))
        neg = max(1, int(len(y_train) - pos))
        sample_weight = np.where(y_train == 1, neg / pos, 1.0)
        w, b = train_logistic(Xt, y_train, sample_weight)
        scores = predict(Xv, w, b)
        pred = (scores >= 0.5).astype(int)
        accuracy = float((pred == y_test).mean())
        tpr = float(pred[y_test == 1].mean()) if np.any(y_test == 1) else float("nan")
        tnr = float((1 - pred[y_test == 0]).mean()) if np.any(y_test == 0) else float("nan")
        print(
            f"  {test[0]}: n={len(y_test):4d} pos={int(y_test.sum()):4d} "
            f"acc={accuracy:.2f} tpr={tpr:.2f} tnr={tnr:.2f}"
        )
        all_scores.extend(scores.tolist())
        all_labels.extend(y_test.tolist())

    pred_all = (np.asarray(all_scores) >= 0.5).astype(int)
    accuracy_all = float(np.mean(pred_all == np.asarray(all_labels)))
    print(
        f"\nOverall window accuracy={accuracy_all:.3f} "
        f"AUC={auc_score(np.asarray(all_labels), np.asarray(all_scores)):.3f}"
    )

    # Final model for export.
    X = np.vstack([s[1] for s in sessions])
    y = np.concatenate([s[2] for s in sessions])
    mean = X.mean(axis=0)
    std = X.std(axis=0)
    std[std < 1e-6] = 1.0
    pos = max(1, int(y.sum()))
    neg = max(1, int(len(y) - pos))
    sample_weight = np.where(y == 1, neg / pos, 1.0)
    w, b = train_logistic((X - mean) / std, y, sample_weight)
    MODEL_OUT.write_text(
        json.dumps(
            {
                "feature_names": FEATURE_NAMES,
                "mean": mean.tolist(),
                "std": std.tolist(),
                "weights": w.tolist(),
                "bias": b,
                "window_s": WINDOW_S,
                "stride_s": STRIDE_S,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"\nSaved model: {MODEL_OUT}")


if __name__ == "__main__":
    main()
