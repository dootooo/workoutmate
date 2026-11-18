#!/usr/bin/env python3
"""Time-state-machine gate simulation for Phase 2.

Instead of gating every candidate event independently, this script keeps a
  ACTIVE / FROZEN
state derived from the classifier's window probabilities:

  - ACTIVE -> FROZEN after `freeze_hold` seconds of sustained non-jump
  - FROZEN -> ACTIVE after `resume_hold` seconds of sustained jump

Candidate jump events are counted only while ACTIVE. This is closer to what a
live counter would do, and it is still offline: the Android counter is untouched.
"""
import csv
import json
import pathlib
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from phase2_offline_classifier import (  # noqa: E402
    FEATURE_NAMES,
    SESSION_DIR,
    frame_features,
    load_frames,
    parse_segments,
    predict,
    train_logistic,
    window_vector,
)

MANIFEST = ROOT / "data" / "phase2_manifest.csv"
OUT = ROOT / "data" / "time_gate_simulation.json"
WINDOW_S = 0.8
STRIDE_S = 0.2

THRESHOLDS = [0.4, 0.5, 0.6]
FREEZE_HOLDS = [0.8, 1.2, 1.6, 2.0]
RESUME_HOLDS = [0.2, 0.4, 0.8]


def detect_candidate_events(rows):
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


def timed_windows(rows, segments):
    features = []
    labels = []
    times = []
    end_t = rows[-1]["t"]
    start = 0.0
    while start + WINDOW_S <= end_t:
        window = [r for r in rows if start <= r["t"] <= start + WINDOW_S]
        if len(window) >= 6:
            center = start + WINDOW_S / 2.0
            features.append(window_vector(window))
            label = 0
            for seg_start, seg_end, kind in segments:
                if seg_start <= center < seg_end:
                    label = 1 if kind == "jump" else 0
                    break
            labels.append(label)
            times.append(center)
        start += STRIDE_S
    return np.asarray(features), np.asarray(labels), np.asarray(times)


def event_features(rows, event_time):
    window = [r for r in rows if event_time - 0.35 <= r["t"] <= event_time + 0.45]
    if len(window) < 5:
        return None
    return np.asarray(window_vector(window), dtype=float)


def event_label(t, segments):
    for start, end, kind in segments:
        if start <= t < end:
            return 1 if kind == "jump" else 0
    return 0


def state_counts(events, event_scores, window_times, window_scores, threshold,
                 freeze_hold, resume_hold):
    active = True
    low_since = None
    high_since = None
    accepted = 0
    wi = 0
    n = len(window_times)
    for event_t, event_p in zip(events, event_scores):
        while wi < n and window_times[wi] <= event_t:
            center = window_times[wi]
            p = window_scores[wi]
            if p >= threshold:
                low_since = None
                if high_since is None:
                    high_since = center
                if not active and center - high_since >= resume_hold:
                    active = True
                    high_since = None
            else:
                high_since = None
                if low_since is None:
                    low_since = center
                if active and center - low_since >= freeze_hold:
                    active = False
                    low_since = None
            wi += 1
        if active and event_p >= threshold:
            accepted += 1
    return accepted


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
        segments = parse_segments(row.get("segments", ""))
        win_X, win_y, win_t = timed_windows(rows, segments)
        if len(win_X) == 0:
            continue
        events = detect_candidate_events(rows)
        ev_X = []
        ev_t = []
        for event_t in events:
            vec = event_features(rows, event_t)
            if vec is not None:
                ev_X.append(vec)
                ev_t.append(event_t)
        if len(ev_X) == 0:
            continue
        sessions.append(
            {
                "group": row["group"],
                "actual": int(row["actual_jumps"]),
                "win_X": win_X,
                "win_y": win_y,
                "win_t": win_t,
                "ev_X": np.asarray(ev_X),
                "ev_t": np.asarray(ev_t),
            }
        )

    print(f"usable sessions: {len(sessions)}")

    # Train/predict once per held-out session, then sweep gate parameters.
    folds = []
    for held_out in range(len(sessions)):
        train = [s for i, s in enumerate(sessions) if i != held_out]
        test = sessions[held_out]
        X_train = np.vstack([s["win_X"] for s in train])
        y_train = np.concatenate([s["win_y"] for s in train])
        mean = X_train.mean(axis=0)
        std = X_train.std(axis=0)
        std[std < 1e-6] = 1.0
        pos = max(1, int(y_train.sum()))
        neg = max(1, len(y_train) - pos)
        weights = np.where(y_train == 1, neg / pos, 1.0)
        w, b = train_logistic((X_train - mean) / std, y_train, weights)
        win_p = predict((test["win_X"] - mean) / std, w, b)
        ev_p = predict((test["ev_X"] - mean) / std, w, b)
        folds.append((test, win_p, ev_p))

    results = {"grid": [], "best": None, "sessions": []}
    best = None
    for threshold in THRESHOLDS:
        for freeze_hold in FREEZE_HOLDS:
            for resume_hold in RESUME_HOLDS:
                errors = []
                false_pos = 0
                misses = 0
                total_events = 0
                for test, win_p, ev_p in folds:
                    count = state_counts(
                        test["ev_t"], ev_p, test["win_t"], win_p,
                        threshold, freeze_hold, resume_hold
                    )
                    errors.append(abs(count - test["actual"]))
                    # Event-level errors against segment labels.
                    for t, p in zip(test["ev_t"], ev_p):
                        label = event_label(t, [])
                        # Recover label from session segments stored on test? not stored.
                        total_events += 1
                        if p < threshold:
                            # Approximate: low event probability means missed jump.
                            misses += 1
                mae = float(np.mean(errors))
                entry = {
                    "threshold": threshold,
                    "freeze_hold": freeze_hold,
                    "resume_hold": resume_hold,
                    "mae": mae,
                }
                results["grid"].append(entry)
                if best is None or mae < best["mae"]:
                    best = entry
                    results["best"] = entry
                print(
                    f"thr={threshold:.1f} freeze={freeze_hold:.1f} "
                    f"resume={resume_hold:.1f} MAE={mae:.2f}"
                )

    # Per-session table at the best parameter set.
    best = results["best"]
    print("\nBest:", best)
    for test, win_p, ev_p in folds:
        count = state_counts(
            test["ev_t"], ev_p, test["win_t"], win_p,
            best["threshold"], best["freeze_hold"], best["resume_hold"]
        )
        print(
            f"  {test['group']:6s} actual={test['actual']:3d} "
            f"candidates={len(test['ev_t']):3d} gated={count:3d} "
            f"error={count - test['actual']:+d}"
        )
        results["sessions"].append(
            {
                "group": test["group"],
                "actual": test["actual"],
                "candidates": int(len(test["ev_t"])),
                "gated": count,
                "error": count - test["actual"],
            }
        )

    OUT.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"\nSaved {OUT}")


if __name__ == "__main__":
    main()
