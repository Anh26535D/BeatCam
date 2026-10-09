from __future__ import annotations

import argparse

from .camera import CameraConfig
from .pipeline import Options, process


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(prog="beatcam", description="Auto-reframe to 9:16 with beat-synced punch zoom")
    ap.add_argument("input"), ap.add_argument("output")
    ap.add_argument("--detector", default="yolo", choices=["yolo", "motion"])
    ap.add_argument("--model", default=None, help="default yolo11n.pt (yolo11n-pose.pt with --pose)")
    ap.add_argument("--tracker", default="bytetrack.yaml", help="bytetrack.yaml | botsort.yaml")
    ap.add_argument("--pose", action="store_true", help="Phase 3: gesture-driven lead room")
    ap.add_argument("--sports", action="store_true", help="Phase 4: ball tracking + receiver prediction")
    ap.add_argument("--no-beat", action="store_true")
    ap.add_argument("--beat-latency", type=float, default=0.0)
    ap.add_argument("--zoom", type=float, default=1.25)
    ap.add_argument("--margin", type=float, default=0.18)
    ap.add_argument("--savgol", type=int, default=0)
    ap.add_argument("--codec", default="libx264", choices=["libx264", "libx265"])
    ap.add_argument("--height", type=int, default=1920)
    a = ap.parse_args(argv)

    if a.detector == "yolo":
        from .detectors import YoloDetector
        det = YoloDetector(a.model or ("yolo11n-pose.pt" if a.pose else "yolo11n.pt"), a.tracker)
    else:
        from .detectors import MotionDetector
        det = MotionDetector()
    opt = Options(out_height=a.height, codec=a.codec, beat=not a.no_beat, beat_latency=a.beat_latency,
                  pose=a.pose, sports=a.sports, savgol_window=a.savgol,
                  camera=CameraConfig(base_zoom=a.zoom, margin=a.margin))
    r = process(a.input, a.output, det, opt)
    print(f"wrote {a.output}: {r['frames']} frames, {r['onsets']} punches")


if __name__ == "__main__":
    main()
