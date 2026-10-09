"""Audio analysis: decode via ffmpeg, band-limited onset detection (kick 20-120 Hz, snare/clap 1-3 kHz)."""

from __future__ import annotations

import subprocess
from dataclasses import dataclass

import numpy as np

BANDS = {"kick": (20.0, 120.0), "snare": (1000.0, 3000.0)}


@dataclass
class Onset:
    time: float  # seconds
    strength: float  # 0..1, relative to the strongest onset of its band
    kind: str  # "kick" | "snare"


def decode_audio(path: str, sr: int = 22050) -> np.ndarray:
    cmd = ["ffmpeg", "-v", "error", "-i", path, "-vn", "-ac", "1", "-ar", str(sr), "-f", "f32le", "-"]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        return np.zeros(0, dtype=np.float32)
    return np.frombuffer(r.stdout, dtype=np.float32)


def mel_filterbank(sr: int, n_fft: int, n_mels: int, fmin: float, fmax: float) -> np.ndarray:
    mel = lambda f: 2595 * np.log10(1 + f / 700)
    inv = lambda m: 700 * (10 ** (m / 2595) - 1)
    pts = inv(np.linspace(mel(fmin), mel(fmax), n_mels + 2))
    freqs = np.linspace(0, sr / 2, n_fft // 2 + 1)
    fb = np.zeros((n_mels, len(freqs)))
    for i in range(n_mels):
        l, c, r = pts[i], pts[i + 1], pts[i + 2]
        up, down = (freqs - l) / max(c - l, 1e-9), (r - freqs) / max(r - c, 1e-9)
        fb[i] = np.maximum(0, np.minimum(up, down))
    return fb


def band_flux(y: np.ndarray, sr: int, band: tuple[float, float], n_fft: int = 2048, hop: int = 256):
    """Half-wave-rectified log spectral flux restricted to `band`. Returns (flux, frame_times)."""
    y = np.pad(y, n_fft // 2)  # centred frames: frame i is centred on sample i*hop
    n = 1 + (len(y) - n_fft) // hop
    idx = np.arange(n_fft)[None, :] + hop * np.arange(n)[:, None]
    spec = np.abs(np.fft.rfft(y[idx] * np.hanning(n_fft), axis=1)) ** 2
    freqs = np.fft.rfftfreq(n_fft, 1 / sr)
    # Mel-spaced triangular filters inside the band (mel scale, restricted to the band edges)
    n_mels = 8 if band[1] > 500 else 4
    fb = mel_filterbank(sr, n_fft, n_mels, *band)
    if band[1] < 500:  # low band: mel filters narrower than a bin would be empty; use plain bin sum
        fb = ((freqs >= band[0]) & (freqs <= band[1])).astype(float)[None, :]
    logm = np.log1p(1e3 * (spec @ fb.T) / n_fft)
    flux = np.maximum(0, np.diff(logm, axis=0, prepend=logm[:1])).sum(axis=1)
    return flux, np.arange(n) * hop / sr


def pick_peaks(flux: np.ndarray, times: np.ndarray, k: float = 3.0, win: int = 86, min_gap_s: float = 0.12):
    """Adaptive threshold (local median + k*MAD) peak picking."""
    out, last = [], -1e9
    for i in range(1, len(flux) - 1):
        if not (flux[i] > flux[i - 1] and flux[i] >= flux[i + 1]):
            continue
        seg = flux[max(0, i - win): i + win]
        med = np.median(seg)
        thr = med + k * np.median(np.abs(seg - med)) + 1e-6
        if flux[i] <= thr or flux[i] < 0.05 * flux.max():
            continue
        if times[i] - last < min_gap_s:
            if out and flux[i] > out[-1][1]:
                out[-1] = (times[i], flux[i])
                last = times[i]
            continue
        out.append((times[i], flux[i]))
        last = times[i]
    return out


def detect_onsets(y: np.ndarray, sr: int = 22050, bands: dict | None = None) -> list[Onset]:
    onsets: list[Onset] = []
    if len(y) < 4096:
        return onsets
    for kind, band in (bands or BANDS).items():
        flux, times = band_flux(y, sr, band)
        peaks = pick_peaks(flux, times)
        if not peaks:
            continue
        top = max(p[1] for p in peaks)
        onsets += [Onset(float(t), float(v / top), kind) for t, v in peaks]
    return sorted(onsets, key=lambda o: o.time)
