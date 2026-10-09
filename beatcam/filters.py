"""Smoothing filters: One-Euro (causal, streaming) and Savitzky-Golay (offline)."""

from __future__ import annotations

import math

import numpy as np


class OneEuroFilter:
    """Casiez et al. 2012. Low cutoff when slow (kills jitter), higher when fast (kills lag)."""

    def __init__(self, min_cutoff: float = 0.8, beta: float = 0.01, d_cutoff: float = 1.0):
        self.min_cutoff, self.beta, self.d_cutoff = min_cutoff, beta, d_cutoff
        self._x: float | None = None
        self._dx = 0.0

    @staticmethod
    def _alpha(cutoff: float, dt: float) -> float:
        tau = 1.0 / (2 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / dt)

    def reset(self, x: float | None = None) -> None:
        self._x, self._dx = x, 0.0

    def __call__(self, x: float, dt: float) -> float:
        if self._x is None:
            self._x = x
            return x
        dx = (x - self._x) / dt
        a_d = self._alpha(self.d_cutoff, dt)
        self._dx += a_d * (dx - self._dx)
        cutoff = self.min_cutoff + self.beta * abs(self._dx)
        a = self._alpha(cutoff, dt)
        self._x += a * (x - self._x)
        return self._x


def savgol(x: np.ndarray, window: int = 15, order: int = 3) -> np.ndarray:
    """Savitzky-Golay smoothing of a 1-D (or (N, k)) signal; edges use a polynomial fit of the first/last window."""
    x = np.asarray(x, dtype=float)
    if x.ndim == 2:
        return np.stack([savgol(x[:, i], window, order) for i in range(x.shape[1])], axis=1)
    window = int(window) | 1  # force odd
    if len(x) < window or window <= order:
        return x.copy()
    half = window // 2
    k = np.arange(-half, half + 1)
    A = np.vander(k, order + 1, increasing=True)
    coeffs = np.linalg.pinv(A)[0]  # row 0 gives the fitted value at the window centre
    y = np.convolve(x, coeffs[::-1], mode="same")
    for sl, ref in ((slice(0, half), slice(0, window)), (slice(len(x) - half, len(x)), slice(len(x) - window, len(x)))):
        pos = np.arange(len(x))[ref]
        poly = np.polynomial.Polynomial.fit(pos, x[ref], order)
        y[sl] = poly(np.arange(len(x))[sl])
    return y
