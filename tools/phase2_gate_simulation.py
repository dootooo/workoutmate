#!/usr/bin/env python3
"""Offline gate simulation for the Phase 2 jump/non-jump classifier.

This does not touch the Android app. It turns every session into:
  pose signal -> candidate jump events -> window features -> classifier score
  -> threshold gate -> accepted count

The goal is to see whether a classifier gate reduces post-stop over-count
without killing real jumps. Candidate events come from the same basic
normalized-hip signal the app uses; this is a simulation, not the live counter.
"""
import csv
import pathlib
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from phase2_offline_classifier import (  # noqa: E402
    FEATURE_NAMES,
    SESSION_DIR,
    build_windows,
    frame_features,
    load_frames,
    parse_segments,
    predict,
    train_logistic,
)

MANIFEST = ROOT / "data" / "phase2_manifest.csv"
OUT = ROOT / "data" / "gate_simulation.json"
THRESHOLDS = [0.3, 0.4, 0.5, 0.6, 0.7, 0.8]


def detect_candidate_events(rows):
    """Adaptive-band upward crossing, with a 200 ms refractory period."""
    sm = None
    baseline = None
    above = False
    last = -1e9
    events = []
    for row in rows:
        t = row["t"]
        s = row["signal"]
        if sm is None:
            sm = s
            baseline = s
            continue
        sm = 0.60 * s + 0.40 * sm
        baseline = 0.12 * sm + 0.88 * baseline
        centered = sm - baseline
        was_above = above
        if centered > 0.028:
            above = True
        elif centered < -0.014:
            above = False
        if above and not was_above and t - last >= 0.200:
            events.append(t)
            last = t
    return events


def event_features(rows, event_time):
    window = [r for r in rows if event_time - 0.35 <= r["t"] <= event_time + 0.45]
    if len(window) < 5:
        return None
    return np.asarray(window_vector_compat(window), dtype=float)


def window_vector_compat(rows):
    # Import lazily to keep this script compatible with the prototype module.
    from phase2_offline_classifier import window_vector

    return window_vector(rows)


def main():
    with MANIFEST.open(encoding="utf-8") as handle:
        manifest = list(csv.DictReader(handle))

    sessions = []
    for row in manifest:
        if row.get("age", "").strip():
            try:
                if int(row["age"]) < 18:
                    continue
            except ValueError:
                pass
        if not row.get("actual_jumps", "").strip():
            continue
        path = SESSION_DIR / f"session_{row['session_id']}.jsonl"
        if not path.exists():
            continue
        frames = load_frames(path)
        rows = []
        for frame in frames:
            item = frame_features(frame)
            if item is not None:
                rows.append(item)
        if not rows:
            continue
        t0 = rows[0]["t"]
        for item in rows:
            item["t"] -= t0
        events = detect_candidate_events(rows)
        if not events:
            continue
        segments = parse_segments(row.get("segments", ""))
        # Manual windows are used for classifier training.
        features, labels = build_windows(row["session_id"], path, segments)
        if not features:
            continue
        event_vectors = []
        event_times = []
        for event_time in events:
            vector = event_features(rows, event_time)
            if vector is not None:
                event_vectors.append(vector)
                event_times.append(event_time)
        sessions.append(
            {
                "group": row["group"],
                "session_id": row["session_id"],
                "actual": int(row["actual_jumps"]),
                "app": int(row["app_count"]) if row.get("app_count", "").strip() else None,
                "windows_X": np.asarray(features),
                "windows_y": np.asarray(labels),
                "event_X": np.asarray(event_vectors),
                "event_t": np.asarray(event_times),
                "segments": segments,
            }
        )

    print(f"usable sessions: {len(sessions)}")
    results = {"thresholds": {}, "sessions": []}
    for threshold in THRESHOLDS:
        errors = []
        misses = 0
        false_positives = 0
        total_events = 0
        for held_out in range(len(sessions)):
            train = [s for i, s in enumerate(sessions) if i != held_out]
            test = sessions[held_out]
            if not train:
                continue
            X_train = np.vstack([s["windows_X"] for s in train])
            y_train = np.concatenate([s["windows_y"] for s in train])
            mean = X_train.mean(axis=0)
            std = X_train.std(axis=0)
            std[std < 1e-6] = 1.0
            Xt = (X_train - mean) / std
            Xe = (test["event_X"] - mean) / std
            pos = max(1, int(y_train.sum()))
            neg = max(1, len(y_train) - pos)
            weights = np.where(y_train == 1, neg / pos, 1.0)
            w, b = train_logistic(Xt, y_train, weights)
            scores = predict(Xe, w, b)
            accepted = scores >= threshold
            count = int(accepted.sum())
            errors.append(abs(count - test["actual"]))
            # Event-level false positives / misses using segment labels.
            for t, ok in zip(test["event_t"], accepted):
                label = 1
                for start, end, kind in test["segments"]:
                    if start <= t < end:
                        label = 1 if kind == "jump" else 0
                        break
                else:
                    label = 0
                total_events += 1
                if label == 0 and ok:
                    false_positives += 1
                if label == 1 and not ok:
                    misses += 1
        results["thresholds"][str(threshold)] = {
            "mean_abs_error": float(np.mean(errors)) if errors else None,
            "false_positive_events": false_positives,
            "missed_jump_events": misses,
            "accepted_events": total_events,
        }
        print(
            f"threshold={threshold:.1f} MAE={np.mean(errors):.2f} "
            f"false_pos={false_positives} misses={misses} events={total_events}"
        )

    # Detailed table at 0.5 for inspection.
    threshold = 0.5
    print("\nSessions (threshold 0.5):")
    for held_out in range(len(sessions)):
        train = [s for i, s in enumerate(sessions) if i != held_out]
        test = sessions[held_out]
        X_train = np.vstack([s["windows_X"] for s in train])
        y_train = np.concatenate([s["windows_y"] for s in train])
        mean = X_train.mean(axis=0)
        std = X_train.std(axis=0)
        std[std < 1e-6] = 1.0
        pos = max(1, int(y_train.sum()))
        neg = max(1, len(y_train) - pos)
        w, b = train_logistic((X_train - mean) / std, y_train, np.where(y_train == 1, neg / pos, 1.0))
        scores = predict((test["event_X"] - mean) / std, w, b)
        count = int((scores >= threshold).sum())
        print(
            f"  {test['group']:6s} actual={test['actual']:3d} "
            f"candidates={len(test['event_X']):3d} gated={count:3d} "
            f"error={count - test['actual']:+d}"
        )
        results["sessions"].append(
            {
                "group": test["group"],
                "actual": test["actual"],
                "candidates": int(len(test["event_X"])),
                "gated": count,
                "error": count - test["actual"],
            }
        )

    OUT.write_text(__import__("json").dumps(results, indent=2), encoding="utf-8")
    print(f"\nSaved {OUT}")


if __name__ == "__main__":
    main()
