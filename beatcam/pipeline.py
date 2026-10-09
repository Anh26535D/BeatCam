"""End-to-end pipeline: analyse (detect/track/beat/pose/ball) -> camera path -> render 9:16."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, List, Optional

import cv2
import numpy as np

from .audio import decode_audio, detect_onsets
from .ball import BallKalman, IntentPredictor
from .beat import PunchEnvelope
from .camera import CameraConfig, VirtualCamera
from .filters import savgol
from .pose import GestureAnalyzer
from .video import VideoWriter, probe, read_frames


@dataclass
class Options:
    out_height: int = 1920
    aspect: float = 9 / 16
    codec: str = "libx264"
    crf: int = 18
    beat: bool = True
    beat_latency: float = 0.0  # seconds; positive delays the zoom
    punch_frames: int = 4
    pose: bool = False
    sports: bool = False
    savgol_window: int = 0  # >0: offline Savitzky-Golay pass over the camera path
    camera: CameraConfig = field(default_factory=CameraConfig)


class SubjectLock:
    """Stick to one track id; only switch after it has been gone for `patience` frames."""

    def __init__(self, patience: int = 20):
        self.id, self.gone, self.patience = None, 0, patience

    def pick(self, people):
        if not people:
            return None
        cur = next((p for p in people if self.id is not None and p.track_id == self.id), None)
        if cur is not None:
            self.gone = 0
            return cur
        self.gone += 1
        if self.id is None or self.gone > self.patience:
            cur = max(people, key=lambda p: p.score * (p.box[2] - p.box[0]) * (p.box[3] - p.box[1]))
            self.id, self.gone = cur.track_id, 0
            return cur
        return None


def analyse(path: str, detector: Callable, opt: Options, info: dict):
    """Pass 1. Returns per-frame list of (subject_box|None, lead(dx,dy), widen)."""
    fps = info["fps"]
    lock, gest = SubjectLock(), GestureAnalyzer()
    kal, intent = BallKalman(fps), IntentPredictor(fps)
    plan: List[tuple] = []
    for frame in read_frames(path, info["width"], info["height"]):
        dets = detector(frame)
        people = [d for d in dets if d.label == "person"]
        box, lead, widen = None, (0.0, 0.0), 0.0
        subj = lock.pick(people)
        if opt.sports:
            balls = [d for d in dets if d.label == "ball"]
            meas = max(balls, key=lambda d: d.score).center if balls else None
            state = kal.update(meas)
            fp = intent.update(state, {p.track_id: p for p in people if p.track_id is not None})
            if fp is not None:
                box, widen = fp.box, fp.widen
        if box is None and subj is not None:
            box = subj.box
        if opt.pose and subj is not None and subj.keypoints is not None:
            cue = gest.update(subj.keypoints)
            h = subj.height
            lead, widen = (cue.dx * h, cue.dy * h), widen + cue.widen
        plan.append((box, lead, widen))
    return plan


def camera_path(plan, info: dict, opt: Options, punches: Optional[PunchEnvelope]):
    cam = VirtualCamera(info["width"], info["height"], info["fps"], opt.camera)
    rects = np.array([cam.update(b, l, w, punches.scale(i) if punches else 1.0)
                      for i, (b, l, w) in enumerate(plan)])
    if opt.savgol_window and len(rects) > 5:
        rects = savgol(rects, opt.savgol_window, 3)
        rects[:, 0] = np.clip(rects[:, 0], 0, info["width"] - rects[:, 2])
        rects[:, 1] = np.clip(rects[:, 1], 0, info["height"] - rects[:, 3])
    return rects


def render(path: str, out: str, rects: np.ndarray, info: dict, opt: Options) -> None:
    oh = opt.out_height
    ow = int(round(oh * opt.aspect / 2) * 2)
    wr = VideoWriter(out, (ow, oh), info["fps"], audio_src=path, codec=opt.codec, crf=opt.crf)
    try:
        for frame, (x, y, w, h) in zip(read_frames(path, info["width"], info["height"]), rects):
            s = ow / w  # affine: sub-pixel crop + resize in one resample
            M = np.array([[s, 0, -x * s], [0, oh / h, -y * oh / h]], dtype=np.float64)
            wr.write(cv2.warpAffine(frame, M, (ow, oh), flags=cv2.INTER_CUBIC if s > 1 else cv2.INTER_LINEAR,
                                    borderMode=cv2.BORDER_REPLICATE))
    finally:
        wr.close()


def process(path: str, out: str, detector: Callable, opt: Options | None = None) -> dict:
    opt = opt or Options()
    info = probe(path)
    punches = None
    if opt.beat:
        onsets = detect_onsets(decode_audio(path))
        punches = PunchEnvelope(onsets, info["fps"], opt.punch_frames, opt.beat_latency)
    plan = analyse(path, detector, opt, info)
    rects = camera_path(plan, info, opt, punches)
    render(path, out, rects, info, opt)
    return {"frames": len(rects), "rects": rects, "onsets": len(punches.events) if punches else 0}
