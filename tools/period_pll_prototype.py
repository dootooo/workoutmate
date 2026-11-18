#!/usr/bin/env python3
"""Offline prototype: autocorrelation period estimation + PLL phase counting.

Phase 1 only. It replays the recorded 17-keypoint sequences and compares:
  * the current zero-crossing counter (baseline)
  * an autocorrelation/PLL counter (candidate)
against each fixture's expected count.

Run:
    python tools/period_pll_prototype.py
"""
import json
import math
import pathlib
import statistics

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "app" / "src" / "test" / "resources" / "replay"


def load_fixture(path):
    expected = None
    frames = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("# expected="):
            expected = int(line.split("=", 1)[1])
            continue
        if line.startswith("#"):
            continue
        item = json.loads(line)
        if item.get("type") == "frame":
            frames.append(item)
    return expected, frames


def signal(frames):
    """Hip-center height normalized by torso length."""
    values = []
    for frame in frames:
        points = frame["p"]
        if len(points) < 17:
            values.append(None)
            continue
        if any(points[i][2] < 0.2 for i in (5, 6, 11, 12)):
            values.append(None)
            continue
        shoulder_y = (points[5][1] + points[6][1]) / 2.0
        hip_y = (points[11][1] + points[12][1]) / 2.0
        torso = abs(hip_y - shoulder_y)
        values.append(hip_y / torso if torso > 1e-3 else None)
    return values


def resample(frames, values, fs=30.0):
    times = np.array([frame["t"] for frame in frames], dtype=float) / 1000.0
    raw = np.array([np.nan if value is None else value for value in values], dtype=float)
    mask = ~np.isnan(raw)
    if mask.sum() < 8:
        return None, None
    times = times[mask]
    raw = raw[mask]
    uniform_times = np.arange(times[0], times[-1], 1.0 / fs)
    uniform = np.interp(uniform_times, times, raw)
    return uniform_times, uniform


def bandpass(samples, fs=30.0):
    """Cheap high-pass; the low-pass is a short moving average."""
    n = len(samples)
    if n == 0:
        return samples
    window = max(3, int(round(fs * 0.9)))
    kernel = np.ones(window) / window
    baseline = np.convolve(samples, kernel, mode="same")
    high = samples - baseline
    smooth_window = max(1, int(round(fs * 0.08)))
    smooth = np.convolve(high, np.ones(smooth_window) / smooth_window, mode="same")
    return smooth


def autocorrelation_period(window, fs=30.0):
    """Return (period seconds, confidence) for a centered window."""
    n = len(window)
    min_lag = max(3, int(round(0.20 * fs)))
    max_lag = min(n - 2, int(round(1.20 * fs)))
    if max_lag <= min_lag:
        return None, 0.0
    centered = window - window.mean()
    energy = float(np.dot(centered, centered))
    if energy < 1e-8:
        return None, 0.0
    ac = np.correlate(centered, centered, mode="full")[n - 1:]
    ac = ac / energy
    candidates = []
    for lag in range(min_lag, max_lag + 1):
        if ac[lag] > ac[lag - 1] and ac[lag] >= ac[lag + 1]:
            candidates.append((float(ac[lag]), lag))
    if not candidates:
        return None, 0.0
    confidence, lag = max(candidates)
    if confidence < 0.10:
        return None, confidence
    return lag / fs, confidence


def amplitude(samples, start, end):
    window = samples[max(0, start):max(start + 1, end)]
    if len(window) == 0:
        return 0.0
    return float(np.percentile(window, 95) - np.percentile(window, 5))


def zero_crossing_count(samples, fs=30.0):
    """Baseline similar to the live zero-crossing counter."""
    centered = bandpass(samples, fs)
    baseline = 0.0
    alpha = 0.12
    smoothed = None
    above = False
    last = -10**9
    count = 0
    for index, value in enumerate(centered):
        if smoothed is None:
            smoothed = value
            baseline = value
            continue
        smoothed = 0.60 * value + 0.40 * smoothed
        baseline = alpha * smoothed + (1.0 - alpha) * baseline
        delta = smoothed - baseline
        was_above = above
        if delta > 0.028:
            above = True
        elif delta < -0.028 * 0.50:
            above = False
        if above and not was_above:
            t_ms = index / fs * 1000.0
            if t_ms - last >= 180.0:
                last = t_ms
                count += 1
    return count


def autocorr_pll_count(samples, fs=30.0):
    """Candidate: autocorrelation period tracking + phase-locked loop."""
    samples = bandpass(samples, fs)
    period = None
    period_confidence = 0.0
    phase = 0.0
    last_count_t = -10**9
    window_len = int(round(1.6 * fs))
    update_every = max(1, int(round(0.20 * fs)))
    # Before the first autocorrelation estimate is available, count the
    # warm-up part with the ordinary zero-crossing method. PLL takes over once
    # the period is locked; this keeps short sets from losing their opening.
    count = zero_crossing_count(samples[:window_len], fs)

    for i in range(len(samples)):
        if i >= window_len and i % update_every == 0:
            window = samples[i - window_len + 1:i + 1]
            candidate_period, candidate_confidence = autocorrelation_period(window, fs)
            if candidate_period is not None:
                if period is None:
                    period = candidate_period
                else:
                    # Slow adaptation: the PLL must not jump on one noisy window.
                    period = 0.85 * period + 0.15 * candidate_period
                period_confidence = 0.7 * period_confidence + 0.3 * candidate_confidence

        if period is None or period_confidence < 0.12:
            continue

        phase += (1.0 / fs) / period
        if phase < 1.0:
            continue
        phase -= 1.0

        # A cycle is accepted only when the local vertical amplitude and the
        # autocorrelation confidence both support a real ballistic movement.
        local_amp = amplitude(samples, max(0, i - int(period * fs)), i + 1)
        if local_amp < 0.075:
            continue
        if i / fs * 1000.0 - last_count_t < 220.0:
            continue
        last_count_t = i / fs * 1000.0
        count += 1

    return count


def main():
    rows = []
    for fixture in sorted(FIXTURES.glob("*.jsonl")):
        expected, frames = load_fixture(fixture)
        if expected is None:
            continue
        values = signal(frames)
        _, samples = resample(frames, values)
        if samples is None:
            continue
        baseline = zero_crossing_count(samples)
        candidate = autocorr_pll_count(samples)
        rows.append((fixture.name, expected, baseline, candidate))

    print(f"{'fixture':28} {'expect':>6} {'zero':>6} {'PLL':>6} {'zero err':>9} {'PLL err':>8}")
    for name, expected, baseline, candidate in rows:
        print(
            f"{name:28} {expected:6d} {baseline:6d} {candidate:6d} "
            f"{abs(baseline - expected):9d} {abs(candidate - expected):8d}"
        )
    if rows:
        zero_mae = statistics.mean(abs(b - e) for _, e, b, _ in rows)
        pll_mae = statistics.mean(abs(c - e) for _, e, _, c in rows)
        print(f"\nMAE: zero-crossing={zero_mae:.2f}  autocorr+PLL={pll_mae:.2f}")


if __name__ == "__main__":
    main()

