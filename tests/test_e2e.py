import shutil
import subprocess

import cv2
import numpy as np
import pytest

from beatcam.detectors import MotionDetector
from beatcam.pipeline import Options, process
from beatcam.video import VideoWriter, probe

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")


def make_clip(path, tmp_path):
    W, H, fps, n = 640, 360, 30, 90
    silent = str(tmp_path / "v.mp4")
    w = VideoWriter(silent, (W, H), fps)
    rng = np.random.default_rng(0)
    bg = rng.integers(60, 90, (H, W, 3), dtype=np.uint8)
    for i in range(n):
        f = bg.copy()
        x = 80 + int(i * 5.5)
        cv2.rectangle(f, (x, 120), (x + 50, 300), (40, 200, 240), -1)
        w.write(f)
    w.close()
    # kick at 1.0s and 2.0s
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", silent, "-f", "lavfi", "-i",
                    "aevalsrc='sin(2*PI*60*t)*exp(-30*mod(t,1))*(gte(t,1))':s=22050:d=3",
                    "-c:v", "copy", "-c:a", "aac", "-shortest", path], check=True)


def test_pipeline(tmp_path):
    src, out = str(tmp_path / "in.mp4"), str(tmp_path / "out.mp4")
    make_clip(src, tmp_path)
    r = process(src, out, MotionDetector(), Options(out_height=640, crf=28))
    info = probe(out)
    assert (info["width"], info["height"]) == (360, 640)
    assert info["frames"] >= 85
    assert r["onsets"] >= 1
    rects = r["rects"]
    cx = rects[:, 0] + rects[:, 2] / 2
    assert cx[-1] > cx[10] + 150                      # camera followed the subject
    assert np.abs(np.diff(cx)).max() < 25             # no jumps
    sizes = rects[:, 3]
    assert sizes.min() < 0.95 * np.median(sizes)      # punch zoom visible
