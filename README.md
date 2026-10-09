# BeatCam

AI virtual cameraman: reframes landscape video to 9:16 with smooth tracking, beat-synced punch zoom,
pose-aware lead room and ball-intent prediction.

```
pip install -e ".[yolo,dev]"
beatcam in.mp4 out.mp4                    # phase 1+2: tracking + beat punch-zoom
beatcam in.mp4 out.mp4 --pose             # + phase 3 (YOLO11-pose keypoints)
beatcam in.mp4 out.mp4 --sports           # + phase 4 (ball + receiver prediction)
beatcam in.mp4 out.mp4 --detector motion  # no model needed (static camera demo)
```

| Phase | Module | What it does |
|---|---|---|
| 1 Tracking & framing | `detectors.py` (YOLO + ByteTrack/BoT-SORT, `SimpleTracker` fallback), `camera.py`, `filters.py`, `video.py` | Dead-zone gating, One-Euro filter (streaming) or Savitzky-Golay (`--savgol N`, offline), FFmpeg H.264/HEVC export |
| 2 Beat sync | `audio.py`, `beat.py` | Band-limited spectral-flux onsets (kick 20-120 Hz, snare/clap 1-3 kHz), 5-10 % bounce-eased punch zoom over 4 frames, `--beat-latency` for A/V alignment |
| 3 Pose-aware | `pose.py` | Reach-up / floor-drop / arms-spread cues + wrist-vs-shoulder velocity -> lead room and margin widening; asymmetric damping (fast attack, slow release) in `camera.py` |
| 4 Intent prediction | `ball.py` | Kalman ball tracker that coasts through blur, drag-aware trajectory extrapolation, receiver selection; zoom out on release, pre-frame + zoom in on approach |

Notes / limits: the ball detector is pluggable (`YoloDetector(ball_detector=...)` for a TrackNet-style model);
by default COCO "sports ball" is used. The YOLO paths need `ultralytics` + weights and were not exercised
in tests (core algorithms and an end-to-end run with the motion detector are). Run `pytest`.
