"""Virtual camera: dead-zone + One-Euro smoothing, asymmetric lead-room damping, punch zoom."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .filters import OneEuroFilter


@dataclass
class CameraConfig:
    aspect: float = 9 / 16  # output width / height
    base_zoom: float = 1.25  # >1 leaves room to pan horizontally and to widen
    margin: float = 0.18  # dead-zone: fraction of crop size kept as a safe border
    min_cutoff: float = 0.8
    beta: float = 0.01
    attack: float = 0.35  # lead-room smoothing per frame when offset grows (fast)
    release: float = 0.03  # ... and when it returns to centre (slow)
    max_widen: float = 0.5
    min_widen: float = -0.2


class VirtualCamera:
    def __init__(self, src_w: int, src_h: int, fps: float, cfg: CameraConfig | None = None):
        self.cfg = cfg or CameraConfig()
        self.W, self.H, self.dt = src_w, src_h, 1.0 / fps
        self._fx, self._fy = (OneEuroFilter(self.cfg.min_cutoff, self.cfg.beta) for _ in range(2))
        self._c: np.ndarray | None = None  # dead-zone-gated target centre
        self._off = np.zeros(2)  # lead-room offset in pixels
        self._widen = 0.0

    def size(self, widen: float = 0.0, punch: float = 1.0) -> tuple[float, float]:
        h = self.H / self.cfg.base_zoom * (1.0 + widen) / punch
        h = min(h, self.H, self.W / self.cfg.aspect)
        return h * self.cfg.aspect, h

    def _damp(self, cur, target):
        """Fast when |value| grows (explosive move), slow when it relaxes toward 0."""
        grow = np.abs(target) > np.abs(cur)
        a = np.where(grow, self.cfg.attack, self.cfg.release)
        return cur + a * (target - cur)

    def update(self, box, lead=(0.0, 0.0), widen: float = 0.0, punch: float = 1.0):
        """box: subject (x1,y1,x2,y2) or None to hold. lead: desired offset in px. Returns crop (x, y, w, h)."""
        cfg = self.cfg
        w, h = self.size(self._widen, 1.0)
        if self._c is None:
            self._c = np.array([self.W / 2, self.H / 2]) if box is None else np.array(
                [(box[0] + box[2]) / 2, (box[1] + box[3]) / 2])
            self._fx.reset(self._c[0]), self._fy.reset(self._c[1])
        elif box is not None:
            self._c = self._gate(box, self._c, w, h)

        cx, cy = self._fx(self._c[0], self.dt), self._fy(self._c[1], self.dt)
        self._off = self._damp(self._off, np.asarray(lead, dtype=float))
        self._widen = float(self._damp(np.array([self._widen]),
                                       np.array([np.clip(widen, cfg.min_widen, cfg.max_widen)]))[0])
        w, h = self.size(self._widen, punch)
        x = np.clip(cx + self._off[0] - w / 2, 0, self.W - w)
        y = np.clip(cy + self._off[1] - h / 2, 0, self.H - h)
        return float(x), float(y), float(w), float(h)

    def _gate(self, box, c, w, h):
        """Move the centre only as far as needed to bring the box back inside the safe region."""
        out = c.copy()
        for ax, (lo, hi, half) in enumerate(((box[0], box[2], w / 2), (box[1], box[3], h / 2))):
            m = self.cfg.margin * (w if ax == 0 else h)
            s_lo, s_hi = c[ax] - half + m, c[ax] + half - m
            if hi - lo > s_hi - s_lo:
                out[ax] = (lo + hi) / 2
            elif lo < s_lo:
                out[ax] += lo - s_lo
            elif hi > s_hi:
                out[ax] += hi - s_hi
        return out
