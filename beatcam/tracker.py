"""Lightweight ByteTrack-style tracker (two-stage IoU association) for detectors without built-in tracking."""

from __future__ import annotations

from typing import List

import numpy as np

from .types import Detection, iou


class _Track:
    def __init__(self, tid: int, box: np.ndarray):
        self.id, self.box, self.vel, self.lost, self.hits = tid, box.astype(float), np.zeros(4), 0, 1

    def predicted(self) -> np.ndarray:
        return self.box + self.vel


class SimpleTracker:
    def __init__(self, high: float = 0.5, low: float = 0.1, iou_hi: float = 0.3, iou_lo: float = 0.5, max_lost: int = 30):
        self.high, self.low, self.iou_hi, self.iou_lo, self.max_lost = high, low, iou_hi, iou_lo, max_lost
        self.tracks: List[_Track] = []
        self._next = 1

    def _match(self, tracks, dets, thr):
        pairs = sorted(
            ((iou(t.predicted(), d.box), ti, di) for ti, t in enumerate(tracks) for di, d in enumerate(dets)),
            reverse=True,
        )
        used_t, used_d, out = set(), set(), []
        for v, ti, di in pairs:
            if v < thr:
                break
            if ti in used_t or di in used_d:
                continue
            used_t.add(ti), used_d.add(di), out.append((ti, di))
        return out, [i for i in range(len(tracks)) if i not in used_t], [i for i in range(len(dets)) if i not in used_d]

    def _apply(self, t: _Track, d: Detection):
        new = d.box.astype(float)
        t.vel = 0.7 * t.vel + 0.3 * (new - t.box)
        t.box, t.lost, t.hits = new, 0, t.hits + 1
        d.track_id = t.id

    def update(self, dets: List[Detection]) -> List[Detection]:
        hi = [d for d in dets if d.score >= self.high]
        lo = [d for d in dets if self.low <= d.score < self.high]
        m1, rem_t, rem_hi = self._match(self.tracks, hi, self.iou_hi)
        for ti, di in m1:
            self._apply(self.tracks[ti], hi[di])
        leftover = [self.tracks[i] for i in rem_t]
        m2, still_lost, _ = self._match(leftover, lo, self.iou_lo)
        for ti, di in m2:
            self._apply(leftover[ti], lo[di])
        for i in still_lost:
            leftover[i].lost += 1
        for di in rem_hi:
            t = _Track(self._next, hi[di].box)
            self._next += 1
            hi[di].track_id = t.id
            self.tracks.append(t)
        self.tracks = [t for t in self.tracks if t.lost <= self.max_lost]
        return [d for d in hi + lo if d.track_id is not None]
