"""Detector backends. YOLO (ultralytics) for real use; MotionDetector as a dependency-light fallback."""

from __future__ import annotations

from typing import List, Protocol

import numpy as np

from .types import Detection
from .tracker import SimpleTracker

COCO_PERSON, COCO_BALL = 0, 32


class Detector(Protocol):
    def __call__(self, frame: np.ndarray) -> List[Detection]: ...


class YoloDetector:
    """YOLO11 / YOLOv8 (+ -pose) with built-in ByteTrack or BoT-SORT via ultralytics.

    model: e.g. "yolo11n.pt", "yolo11n-pose.pt". tracker: "bytetrack.yaml" | "botsort.yaml".
    A dedicated ball model (TrackNet etc.) can be passed as `ball_detector` (callable frame -> Detection|None).
    """

    def __init__(self, model: str = "yolo11n.pt", tracker: str = "bytetrack.yaml", conf: float = 0.25,
                 imgsz: int = 640, ball_detector=None, device=None):
        from ultralytics import YOLO  # optional dependency

        self.model, self.tracker, self.conf, self.imgsz, self.device = YOLO(model), tracker, conf, imgsz, device
        self.ball_detector = ball_detector

    def __call__(self, frame):
        classes = [COCO_PERSON] + ([COCO_BALL] if self.ball_detector is None else [])
        res = self.model.track(frame, persist=True, tracker=self.tracker, conf=self.conf, imgsz=self.imgsz,
                               classes=classes, device=self.device, verbose=False)[0]
        out: List[Detection] = []
        if res.boxes is None:
            return out
        xyxy, cls, sc = res.boxes.xyxy.cpu().numpy(), res.boxes.cls.cpu().numpy(), res.boxes.conf.cpu().numpy()
        ids = res.boxes.id.cpu().numpy().astype(int) if res.boxes.id is not None else [None] * len(xyxy)
        kps = res.keypoints.data.cpu().numpy() if getattr(res, "keypoints", None) is not None else None
        for i in range(len(xyxy)):
            is_ball = int(cls[i]) == COCO_BALL
            out.append(Detection(xyxy[i], float(sc[i]), "ball" if is_ball else "person",
                                 None if ids[i] is None else int(ids[i]),
                                 kps[i] if kps is not None and not is_ball and kps.shape[1] == 17 else None))
        if self.ball_detector is not None:
            b = self.ball_detector(frame)
            if b is not None:
                out.append(b)
        return out


class MotionDetector:
    """Background-subtraction blob detector (no model needed). Good for demos/tests with a static camera."""

    def __init__(self, min_area: int = 400):
        import cv2

        self.cv2, self.min_area = cv2, min_area
        self.bg = cv2.createBackgroundSubtractorMOG2(history=60, varThreshold=32, detectShadows=False)
        self.tracker = SimpleTracker(high=0.5)

    def __call__(self, frame):
        cv2 = self.cv2
        mask = self.bg.apply(frame)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        mask = cv2.dilate(mask, np.ones((9, 9), np.uint8))
        cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        dets = []
        for c in cnts:
            x, y, w, h = cv2.boundingRect(c)
            if w * h >= self.min_area:
                dets.append(Detection(np.array([x, y, x + w, y + h], float), 0.9))
        return self.tracker.update(dets)
