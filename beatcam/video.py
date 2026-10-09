"""ffmpeg-based video I/O (raw BGR frames over pipes)."""

from __future__ import annotations

import json
import subprocess
from typing import Iterator, Tuple

import numpy as np


def probe(path: str) -> dict:
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets",
                          "-show_entries", "stream=width,height,r_frame_rate,nb_read_packets",
                          "-of", "json", path], capture_output=True, check=True).stdout
    s = json.loads(out)["streams"][0]
    num, den = s["r_frame_rate"].split("/")
    return {"width": int(s["width"]), "height": int(s["height"]), "fps": float(num) / float(den),
            "frames": int(s.get("nb_read_packets", 0))}


def read_frames(path: str, w: int, h: int) -> Iterator[np.ndarray]:
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", path, "-f", "rawvideo", "-pix_fmt", "bgr24", "-"],
                         stdout=subprocess.PIPE)
    n = w * h * 3
    try:
        while True:
            buf = p.stdout.read(n)
            if len(buf) < n:
                return
            yield np.frombuffer(buf, np.uint8).reshape(h, w, 3)
    finally:
        p.stdout.close()
        p.wait()


class VideoWriter:
    def __init__(self, path: str, size: Tuple[int, int], fps: float, audio_src: str | None = None,
                 codec: str = "libx264", crf: int = 18, preset: str = "medium"):
        w, h = size
        cmd = ["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{w}x{h}",
               "-r", str(fps), "-i", "-"]
        if audio_src:
            cmd += ["-i", audio_src, "-map", "0:v", "-map", "1:a?", "-c:a", "aac", "-b:a", "192k", "-shortest"]
        cmd += ["-c:v", codec, "-crf", str(crf), "-preset", preset, "-pix_fmt", "yuv420p"]
        if codec == "libx265":
            cmd += ["-tag:v", "hvc1"]
        cmd += ["-movflags", "+faststart", path]
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE)

    def write(self, frame: np.ndarray) -> None:
        self.p.stdin.write(np.ascontiguousarray(frame).tobytes())

    def close(self) -> None:
        self.p.stdin.close()
        if self.p.wait() != 0:
            raise RuntimeError("ffmpeg encode failed")
