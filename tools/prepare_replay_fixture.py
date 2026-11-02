#!/usr/bin/env python3
"""Convert an app replay-session JSONL into a regression fixture.

Usage:
    python tools/prepare_replay_fixture.py \
        session_123.jsonl 70 app/src/test/resources/replay/real_70.jsonl

The app records 17 MoveNet keypoints per frame. The expected count is supplied
by the person who performed the session, so the fixture has a real ground truth.
"""
import argparse
import json
import pathlib


def load_jsonl(path: pathlib.Path):
    frames = []
    summary = None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            # A session can be killed while the last line is being flushed.
            # Keep all complete frames and ignore the partial tail.
            continue
        if item.get("type") == "frame":
            frames.append(item)
        elif item.get("type") == "summary":
            summary = item.get("count")
    return frames, summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=pathlib.Path)
    parser.add_argument("expected", type=int)
    parser.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()

    frames, recorded_count = load_jsonl(args.input)
    if not frames:
        raise SystemExit(f"No frames in {args.input}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as out:
        out.write(f"# expected={args.expected}\n")
        out.write(json.dumps({"type": "header", "source": str(args.input),
                              "recorded_count": recorded_count}) + "\n")
        for frame in frames:
            out.write(json.dumps(frame, separators=(",", ":")) + "\n")

    print(f"wrote {len(frames)} frames to {args.output}")
    print(f"expected={args.expected} recorded_count={recorded_count}")


if __name__ == "__main__":
    main()
