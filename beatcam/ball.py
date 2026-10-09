"""Phase 4: ball tracking (Kalman + coasting), trajectory extrapolation and receiver prediction."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional

import numpy as np

from .types import Detection, union_box


class BallKalman:
    """Constant-velocity Kalman filter that keeps coasting through motion-blur dropouts."""

    def __init__(self, fps: float, q: float = 400.0, r: float = 4.0, max_miss: int = 12, gate: float = 120.0):
        self.dt, self.max_miss, self.gate = 1.0 / fps, max_miss, gate
        self.F = np.eye(4)
        self.F[0, 2] = self.F[1, 3] = self.dt
        self.H = np.zeros((2, 4))
        self.H[0, 0] = self.H[1, 1] = 1
        self.Q = q * np.diag([self.dt ** 2, self.dt ** 2, 1.0, 1.0])
        self.R = np.eye(2) * r
        self.x: Optional[np.ndarray] = None
        self.P = np.eye(4) * 100
        self.miss = 0

    def update(self, meas: Optional[np.ndarray]) -> Optional[np.ndarray]:
        if self.x is None:
            if meas is None:
                return None
            self.x, self.P, self.miss = np.array([*meas, 0.0, 0.0]), np.diag([10, 10, 1e4, 1e4]), 0
            return self.x.copy()
        self.x = self.F @ self.x
        self.P = self.F @ self.P @ self.F.T + self.Q
        if meas is not None and np.linalg.norm(meas - self.x[:2]) <= self.gate + 30 * self.miss:
            S = self.H @ self.P @ self.H.T + self.R
            K = self.P @ self.H.T @ np.linalg.inv(S)
            self.x = self.x + K @ (meas - self.H @ self.x)
            self.P = (np.eye(4) - K @ self.H) @ self.P
            self.miss = 0
        else:
            self.miss += 1
            if self.miss > self.max_miss:
                self.x = None
                return None
        return self.x.copy()


def extrapolate(pos, vel, t: float, drag: float = 0.6):
    """Ball position after t seconds with exponential velocity decay (drag per second)."""
    k = max(drag, 1e-6)
    return pos + vel * (1 - np.exp(-k * t)) / k


@dataclass
class FramingPlan:
    phase: str  # "follow" | "release" | "approach"
    box: np.ndarray
    widen: float = 0.0
    receiver: Optional[int] = None


class IntentPredictor:
    """Pick the framing target from ball kinematics.

    release  : ball just left the passer -> loosen framing so the pass is visible
    approach : ball about to reach the receiver -> pre-frame and tighten on them
    """

    def __init__(self, fps: float, pass_speed: float = 3.0, horizon: float = 2.0, pre_frame_s: float = 0.5,
                 reach: float = 1.2, drag: float = 0.6):
        self.fps, self.pass_speed, self.horizon, self.pre_frame_s = fps, pass_speed, horizon, pre_frame_s
        self.reach, self.drag = reach, drag
        self.possessor: Optional[int] = None
        self.receiver: Optional[int] = None

    def update(self, ball: Optional[np.ndarray], players: Dict[int, Detection]) -> Optional[FramingPlan]:
        if not players:
            return None
        h = float(np.median([p.height for p in players.values()])) or 1.0
        if ball is None:
            self.receiver = None
            return self._follow(players)
        bp, bv = ball[:2], ball[2:]
        speed = np.linalg.norm(bv) / h  # player-heights per second
        near = min(players.values(), key=lambda p: np.linalg.norm(p.center - bp))
        near_d = np.linalg.norm(near.center - bp) / h

        if speed < self.pass_speed:
            self.receiver = None
            if near_d < self.reach:
                self.possessor = near.track_id
            return self._follow(players, ball_pos=bp)

        passer = players.get(self.possessor) if self.possessor is not None else None
        best, best_t, best_d = None, 0.0, 1e9
        for pid, p in players.items():
            if pid == self.possessor:
                continue
            for t in np.arange(0.0, self.horizon, 1.0 / self.fps):
                d = np.linalg.norm(p.center - extrapolate(bp, bv, t, self.drag)) / h
                if d < best_d:
                    best, best_t, best_d = pid, t, d
        if best is None or best_d > self.reach * 1.5:
            return self._follow(players, ball_pos=bp)
        if self.receiver is not None and self.receiver in players and self.receiver != best:
            old = players[self.receiver]  # keep lock unless the new candidate is clearly better
            if min(np.linalg.norm(old.center - extrapolate(bp, bv, t, self.drag))
                   for t in np.arange(0, self.horizon, 1 / self.fps)) / h < best_d + 0.3:
                best = self.receiver
        self.receiver = best
        rcv = players[best]
        if best_t <= self.pre_frame_s:
            return FramingPlan("approach", rcv.box, widen=-0.15, receiver=best)
        boxes = [rcv.box] + ([passer.box] if passer is not None else [])
        boxes.append(np.array([bp[0] - 0.2 * h, bp[1] - 0.2 * h, bp[0] + 0.2 * h, bp[1] + 0.2 * h]))
        return FramingPlan("release", union_box(boxes), widen=0.3, receiver=best)

    def _follow(self, players, ball_pos=None) -> FramingPlan:
        p = players.get(self.possessor) if self.possessor is not None else None
        if p is None:
            p = next(iter(players.values()))
        box = p.box if ball_pos is None else union_box(
            [p.box, [ball_pos[0] - 5, ball_pos[1] - 5, ball_pos[0] + 5, ball_pos[1] + 5]])
        return FramingPlan("follow", box)
