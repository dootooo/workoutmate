# Labeled data collection for Phase 2 (all ages)

Phase 2 needs labeled **jump / non-jump** windows, not just final counts. The
debug build already records 17 MoveNet keypoints for every session under:

```
/data/data/com.example.workoutmate/files/replay-sessions/
```

Each session is one JSONL file. The missing part is the human ground truth:
actual jump count, when jumping started/stopped, and what happened during
interruptions.

## What to collect first (minimum useful batch)

- 20–30 sessions
- 10+ participants across age bands:
  - 6–9, 10–13, 14–17, 18–40, 41–60, 60+
- 2–3 sessions per participant where possible
- Both jumping styles:
  - feet together
  - alternating / running step
- Slow, normal and fast cadence
- At least two camera distances and two camera angles

That is enough to train and validate a small temporal classifier. More data is
better, but this batch is enough to start.

## Per-session protocol

1. Put the phone in a fixed position; keep the whole body visible.
2. Start a session in the app.
3. Stand still for ~5 seconds (this is a negative/standing segment).
4. Jump a known number of times.
5. Stop and stand still for 2–3 seconds.
6. Press Stop.
7. Record the actual count and relative timestamps in the manifest.

Optional but very useful: record the phone screen or an external video while
testing. It lets us check labels later. Do not upload it; keep it local.

## Negative clips are as important as jumps

Record short negative sessions (10–15 seconds each) of:

- standing still
- adjusting the rope
- walking around
- swinging arms without jumping
- other people walking behind the athlete
- sitting / picking up the phone

These teach the classifier to reject the exact false positives that currently
cause counting after the athlete stops.

## Alternating-step (running-step) protocol

Do this with at least one shorter participant and one taller participant:

1. Start a session with the whole body visible.
2. Stand still for 5 seconds.
3. Jump 30–50 times using the alternating/running-step style.
4. Stop and stand still for 5 seconds.
5. Press Stop.
6. In the manifest set `jump_style=alternating` and enter the real count.

Do the same number with the feet-together style if possible, so the classifier
sees both styles from the same person.

## Negative-only protocol

Record one action per short session. Do not jump:

1. Start a session with the whole body visible.
2. Perform only the negative action for 10–15 seconds.
3. Press Stop.
4. Set `actual_jumps=0` and put the whole session range in
   `negative_segments`, e.g. `0-12:standing`.

Negative actions to collect:

- standing still
- adjusting the rope with hands/arms
- walking around the frame
- swinging arms without jumping
- bending down / picking up the phone
- another person walking behind the athlete

## Jump-then-freeze negative protocol

This is the most important negative for the current false-count bug:

1. Start a session, whole body visible.
2. Stand still for 5 seconds.
3. Jump a known number, e.g. 30 or 50.
4. **Stop immediately and do not move for 8–10 seconds.**
5. Do not press Stop during those seconds; let the app keep counting.
6. Press Stop.
7. In the manifest set the real jump count and mark the post-jump range as
   negative, e.g. `18-28:standing_after_jump`.

This captures the exact “counter keeps counting after the girl stops” failure.

## Manifest format

Use `tools/dataset_manifest_template.csv` as the template. One row per session.

Columns:

```text
session_id
participant_id
age_band
height_cm
camera_distance_m
camera_angle
jump_style
speed
actual_jumps
jump_start_s
jump_end_s
negative_segments
notes
```

`jump_start_s` and `jump_end_s` are **relative to the first frame in the JSONL
file**, not wall-clock time. `negative_segments` can list e.g.
`0-5:standing;18-21:rope_adjust`.

## Privacy and consent

- Use anonymous participant ids; do not put names in files.
- For minors, obtain parent/guardian consent.
- Keep recordings local; the app has no network permission.
- Do not collect unnecessary personal data.
- We only need pose/body data and labels for algorithm testing.

## What to send back

For each batch:

1. The JSONL files from the app
2. The filled manifest CSV
3. Optional videos, clearly named with the same `session_id`

With that, Phase 2 can build positive/negative windows automatically from the
timestamps and train a small jump/non-jump classifier.
