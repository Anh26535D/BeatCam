"""Beat -> scale mapping: punch-zoom envelope with bounce easing, time-aligned to video frames."""

from __future__ import annotations

from typing import Sequence

import numpy as np

from .audio import Onset

DEFAULT_AMP = {"kick": 0.09, "snare": 0.06}  # within the 5-10 % punch range


def ease_out_bounce(p: float) -> float:
    n1, d1 = 7.5625, 2.75
    if p < 1 / d1:
        return n1 * p * p
    if p < 2 / d1:
        p -= 1.5 / d1
        return n1 * p * p + 0.75
    if p < 2.5 / d1:
        p -= 2.25 / d1
        return n1 * p * p + 0.9375
    p -= 2.625 / d1
    return n1 * p * p + 0.984375


class PunchEnvelope:
    """scale(frame) = 1 + max over onsets of amp * strength * (1 - bounce(progress)).

    The zoom hits full amplitude on the first frame at/after the onset and releases over
    `frames` frames (3-5). `latency` shifts audio vs. video to compensate decode / display delay.
    """

    def __init__(self, onsets: Sequence[Onset], fps: float, frames: int = 4, latency: float = 0.0,
                 amp: dict | None = None, min_strength: float = 0.25):
        self.fps, self.frames = fps, frames
        amp = amp or DEFAULT_AMP
        self.events = sorted((o.time + latency, amp.get(o.kind, 0.06) * (0.6 + 0.4 * o.strength))
                             for o in onsets if o.strength >= min_strength)
        self._times = np.array([e[0] for e in self.events])

    def scale(self, frame: int) -> float:
        t = frame / self.fps
        half, span = 0.5 / self.fps, self.frames / self.fps
        lo, hi = np.searchsorted(self._times, [t - span, t + half])
        best = 0.0
        for et, a in self.events[lo:hi]:
            dt = t - et
            if dt < -half or dt >= span:
                continue
            p = min(max(dt, 0.0) * self.fps / self.frames, 1.0)
            best = max(best, a * (1.0 - ease_out_bounce(p)))
        return 1.0 + best
