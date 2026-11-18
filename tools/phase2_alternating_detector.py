#!/usr/bin/env python3
"""Style-aware candidate detection prototype for jump rope.

Feet-together jumps are detected on the hip-center signal. Alternating-step
jumps have less reliable hip motion, so the higher-foot signal is used when a
trailing window says the athlete is alternating.

This is offline only. It measures candidate counts against labelled sessions;
it does not modify the Android counter.
"""
import csv
import math
import pathlib
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from phase2_offline_classifier import SESSION_DIR, load_frames, parse_segments  # noqa: E402

MANIFEST = ROOT / "data" / "phase2_manifest.csv"
STYLE_WINDOW = 1.2
STYLE_RATIO = 0.50
HIP_THRESHOLD = 0.028
TOP_THRESHOLD = 0.020
REFRACTORY = 0.200


def load_rows(path):
    frames = load_frames(path)
    if not frames:
        return []
    t0 = frames[0]["t"]
    rows = []
    for frame in frames:
        p = frame["p"]
        if len(p) < 17:
            continue
        if any(p[i][2] < 0.2 for i in (5, 6, 11, 12)):
            continue
        shoulder_x = (p[5][0] + p[6][0]) / 2
        shoulder_y = (p[5][1] + p[6][1]) / 2
        hip_x = (p[11][0] + p[12][0]) / 2
        hip_y = (p[11][1] + p[12][1]) / 2
        torso = math.hypot(hip_x - shoulder_x, hip_y - shoulder_y)
        if torso < 1e-3:
            continue
        left = p[15]
        right = p[16]
        if left[2] < 0.2 or right[2] < 0.2:
            continue
        rows.append(
            {
                "t": (frame["t"] - t0) / 1000.0,
                "hip": hip_y / torso,
                "top": min(left[1], right[1]) / torso,
                "diff": abs(left[1] - right[1]) / torso,
            }
        )
    return rows


def amplitude(values):
    if not values:
        return 0.0
    return float(np.percentile(values, 95) - np.percentile(values, 5))


def style_at(rows, t):
    window = [r for r in rows if t - STYLE_WINDOW <= r["t"] <= t]
    if len(window) < 10:
        return False
    hip_amp = amplitude([r["hip"] for r in window])
    diff_amp = amplitude([r["diff"] for r in window])
    if hip_amp < 0.03:
        return False
    return diff_amp / hip_amp >= STYLE_RATIO


def detect(rows, key, threshold):
    smoothed = None
    baseline = None
    above = False
    last = -1e9
    events = []
    for row in rows:
        value = row[key]
        if smoothed is None:
            smoothed = value
            baseline = value
            continue
        smoothed = 0.60 * value + 0.40 * smoothed
        baseline = 0.12 * smoothed + 0.88 * baseline
        centered = smoothed - baseline
        was_above = above
        if centered > threshold:
            above = True
        elif centered < -0.5 * threshold:
            above = False
        if above and not was_above and row["t"] - last >= REFRACTORY:
            events.append(row["t"])
            last = row["t"]
    return events


def merged_events(rows):
    hip_events = [(t, "hip") for t in detect(rows, "hip", HIP_THRESHOLD)]
    top_events = [(t, "top") for t in detect(rows, "top", TOP_THRESHOLD)]
    selected = []
    for t, source in sorted(hip_events + top_events):
        alternating = style_at(rows, t)
        # Feet-together: hip only. Alternating: fuse hip and higher-foot events
        # so a small foot lift still contributes a candidate.
        if alternating:
            if source not in ("hip", "top"):
                continue
        elif source != "hip":
            continue
        if selected and abs(t - selected[-1]) < REFRACTORY:
            continue
        selected.append(t)
    return selected


def count_in(events, start, end):
    return sum(1 for t in events if start <= t < end)


def main():
    with MANIFEST.open(encoding="utf-8") as handle:
        manifest = list(csv.DictReader(handle))
    print("group    actual  app   events")
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
        rows = load_rows(path)
        if not rows:
            continue
        events = merged_events(rows)
        segments = parse_segments(row.get("segments", ""))
        positives = [s for s in segments if s[2] == "jump"]
        if positives:
            count = sum(count_in(events, s, e) for s, e, _ in positives)
        else:
            count = len(events)
        print(
            f"{row['group']:8s} {row['actual_jumps']:>6s} {row.get('app_count',''):>4s} "
            f"{count:6d}"
        )


if __name__ == "__main__":
    main()
