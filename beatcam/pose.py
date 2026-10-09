"""Pose-aware framing: turn COCO-17 keypoints into lead-room offset and margin (widen) cues."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .types import *  # noqa: F401,F403  (keypoint indices)


@dataclass
class GestureCue:
    dx: float = 0.0  # fraction of subject height; +x = right
    dy: float = 0.0  # +y = down
    widen: float = 0.0  # >0 zoom out (more margin), <0 zoom in
    reach_up: float = 0.0
    floor_drop: float = 0.0
    arms_spread: float = 0.0


class GestureAnalyzer:
    def __init__(self, conf: float = 0.3, gain: float = 0.35):
        self.conf, self.gain = conf, gain
        self._prev_rel: dict[int, np.ndarray] = {}
        self._stand_leg: float | None = None  # reference hip-knee distance when standing
        self._vel = np.zeros(2)

    def _ok(self, kp, *idx):
        return all(kp[i, 2] >= self.conf for i in idx)

    def update(self, kp: np.ndarray | None) -> GestureCue:
        if kp is None:
            return GestureCue()
        sh = [i for i in (L_SHO, R_SHO) if kp[i, 2] >= self.conf]
        hp = [i for i in (L_HIP, R_HIP) if kp[i, 2] >= self.conf]
        if not sh or not hp:
            return GestureCue()
        sho_c, hip_c = kp[sh, :2].mean(0), kp[hp, :2].mean(0)
        torso = max(np.linalg.norm(sho_c - hip_c), 1e-6)
        cue = GestureCue()

        # reach-up: a wrist well above the head/nose
        head_y = kp[NOSE, 1] if self._ok(kp, NOSE) else sho_c[1] - 0.5 * torso
        wr = [i for i in (L_WRI, R_WRI) if self._ok(kp, i)]
        if wr:
            rise = (head_y - kp[wr, 1].min()) / torso
            cue.reach_up = float(np.clip(rise / 0.8, 0, 1))

        # arms spread: wrist span vs shoulder width
        if self._ok(kp, L_WRI, R_WRI, L_SHO, R_SHO):
            sw = max(abs(kp[L_SHO, 0] - kp[R_SHO, 0]), 0.35 * torso)
            cue.arms_spread = float(np.clip((abs(kp[L_WRI, 0] - kp[R_WRI, 0]) / sw - 2.0) / 2.0, 0, 1))

        # floor drop: hip-knee distance collapses relative to the standing reference
        kn = [i for i in (L_KNE, R_KNE) if self._ok(kp, i)]
        if kn:
            leg = float(np.mean(np.abs(kp[kn, 1] - hip_c[1])))
            self._stand_leg = leg if self._stand_leg is None else max(leg, 0.995 * self._stand_leg + 0.005 * leg)
            cue.floor_drop = float(np.clip(1.0 - leg / max(self._stand_leg, 1e-6), 0, 1) / 0.6) if leg else 0.0
            cue.floor_drop = min(cue.floor_drop, 1.0)

        # wrist motion relative to shoulder (global camera/body motion cancels) -> lead direction
        vel = np.zeros(2)
        for i in wr:
            rel = (kp[i, :2] - sho_c) / torso
            prev = self._prev_rel.get(i)
            if prev is not None:
                vel += rel - prev
            self._prev_rel[i] = rel
        self._vel = 0.6 * self._vel + 0.4 * vel

        cue.dy = self.gain * (-0.6 * cue.reach_up + 0.5 * cue.floor_drop)
        cue.dx = float(np.clip(self.gain * self._vel[0], -0.3, 0.3))
        cue.dy += float(np.clip(self.gain * self._vel[1], -0.3, 0.3))
        cue.widen = 0.35 * cue.reach_up + 0.25 * cue.arms_spread + 0.2 * cue.floor_drop
        return cue
