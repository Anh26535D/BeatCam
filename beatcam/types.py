from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

import numpy as np

# COCO-17 keypoint indices
NOSE, L_SHO, R_SHO, L_ELB, R_ELB, L_WRI, R_WRI = 0, 5, 6, 7, 8, 9, 10
L_HIP, R_HIP, L_KNE, R_KNE, L_ANK, R_ANK = 11, 12, 13, 14, 15, 16


@dataclass
class Detection:
    """One object in one frame. `box` is (x1, y1, x2, y2) in source pixels."""

    box: np.ndarray
    score: float = 1.0
    label: str = "person"  # "person" | "ball"
    track_id: Optional[int] = None
    keypoints: Optional[np.ndarray] = None  # (17, 3): x, y, conf

    @property
    def center(self) -> np.ndarray:
        b = self.box
        return np.array([(b[0] + b[2]) / 2, (b[1] + b[3]) / 2], dtype=float)

    @property
    def height(self) -> float:
        return float(self.box[3] - self.box[1])


def iou(a: np.ndarray, b: np.ndarray) -> float:
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return float(inter / ua) if ua > 0 else 0.0


def union_box(boxes) -> np.ndarray:
    arr = np.asarray(list(boxes), dtype=float)
    return np.array([arr[:, 0].min(), arr[:, 1].min(), arr[:, 2].max(), arr[:, 3].max()])
