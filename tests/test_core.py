import numpy as np

from beatcam.audio import detect_onsets
from beatcam.ball import BallKalman, IntentPredictor, extrapolate
from beatcam.beat import PunchEnvelope
from beatcam.camera import VirtualCamera
from beatcam.filters import OneEuroFilter, savgol
from beatcam.audio import Onset
from beatcam.pose import GestureAnalyzer
from beatcam.tracker import SimpleTracker
from beatcam.types import Detection


def test_one_euro_reduces_jitter():
    rng = np.random.default_rng(0)
    raw = 100 + rng.normal(0, 3, 300)
    f = OneEuroFilter(0.5, 0.0)
    out = np.array([f(v, 1 / 30) for v in raw])
    assert out[30:].std() < raw[30:].std() / 2


def test_savgol_preserves_polynomial():
    t = np.linspace(0, 1, 100)
    y = 3 * t ** 2 - t
    assert np.allclose(savgol(y, 15, 3), y, atol=1e-8)


def test_deadzone_holds_then_follows():
    cam = VirtualCamera(1920, 1080, 30)
    x0, *_ = cam.update([900, 400, 1000, 700])
    # small wiggle inside the safe box: camera must not move
    for dx in (-10, 10, -5, 0):
        r = cam.update([900 + dx, 400, 1000 + dx, 700])
    assert abs(r[0] - x0) < 1e-6
    for _ in range(60):  # walk far right: camera must follow
        r = cam.update([1500, 400, 1600, 700])
    assert r[0] + r[2] / 2 > 1400


def test_crop_stays_in_frame_and_aspect():
    cam = VirtualCamera(1920, 1080, 30)
    for i in range(100):
        x, y, w, h = cam.update([i * 19, 0, i * 19 + 50, 100], widen=0.5, punch=1.1)
        assert x >= 0 and y >= 0 and x + w <= 1920 + 1e-6 and y + h <= 1080 + 1e-6
        assert abs(w / h - 9 / 16) < 1e-6


def test_asymmetric_damping():
    cam = VirtualCamera(1920, 1080, 30)
    cam.update([900, 400, 1000, 700])
    for _ in range(3):
        cam.update([900, 400, 1000, 700], lead=(0, -100))
    up = abs(cam._off[1])
    for _ in range(3):
        cam.update([900, 400, 1000, 700], lead=(0, 0))
    assert up > 50 and abs(cam._off[1]) > 0.7 * up  # fast out, slow back


def test_onsets_kick_and_snare():
    sr = 22050
    y = np.zeros(sr * 4, dtype=np.float32)
    t = np.arange(int(0.12 * sr)) / sr
    kick = np.sin(2 * np.pi * 60 * t) * np.exp(-t * 30)
    rng = np.random.default_rng(1)
    snare = rng.normal(0, 1, len(t)) * np.exp(-t * 40)
    spec = np.fft.rfft(snare)
    f = np.fft.rfftfreq(len(snare), 1 / sr)
    snare = np.fft.irfft(spec * ((f > 1000) & (f < 3000)), len(snare))
    snare /= np.abs(snare).max()
    for s in (0.5, 1.5, 2.5, 3.5):
        y[int(s * sr): int(s * sr) + len(t)] += kick
    for s in (1.0, 2.0, 3.0):
        y[int(s * sr): int(s * sr) + len(t)] += snare
    on = detect_onsets(y, sr)
    for kind, expect in (("kick", (0.5, 1.5, 2.5, 3.5)), ("snare", (1.0, 2.0, 3.0))):
        got = [o.time for o in on if o.kind == kind]
        for e in expect:
            assert min(abs(g - e) for g in got) < 0.04, (kind, e, got)


def test_punch_envelope():
    env = PunchEnvelope([Onset(1.0, 1.0, "kick")], fps=30, frames=4)
    peak = env.scale(30)
    assert 1.05 <= peak <= 1.10
    assert env.scale(29) == 1.0 and env.scale(40) == 1.0
    assert env.scale(30) > env.scale(32) >= 1.0
    late = PunchEnvelope([Onset(1.0, 1.0, "kick")], fps=30, latency=1 / 30)
    assert late.scale(31) == peak


def _kp(wrist_y=0.0, knee_dy=1.0):
    kp = np.zeros((17, 3))
    kp[:, 2] = 1
    kp[0] = [100, 20, 1]
    kp[5], kp[6] = [80, 50, 1], [120, 50, 1]
    kp[11], kp[12] = [85, 130, 1], [115, 130, 1]
    kp[13], kp[14] = [85, 130 + 70 * knee_dy, 1], [115, 130 + 70 * knee_dy, 1]
    kp[9], kp[10] = [70, 100 + wrist_y, 1], [130, 100 + wrist_y, 1]
    return kp


def test_gestures():
    g = GestureAnalyzer()
    for _ in range(5):
        base = g.update(_kp())
    assert base.reach_up == 0 and base.floor_drop == 0
    assert g.update(_kp(wrist_y=-130)).reach_up > 0.5
    assert g.update(_kp(knee_dy=0.3)).floor_drop > 0.5
    spread = _kp()
    spread[9, 0], spread[10, 0] = 0, 200
    assert g.update(spread).arms_spread > 0.5


def test_ball_kalman_coasts():
    k = BallKalman(30)
    for i in range(20):
        k.update(np.array([100.0 + 10 * i, 50.0]))
    s = k.update(None)
    assert abs(s[0] - 300) < 15 and abs(s[2] - 300) < 40  # vel ~ 10px/frame = 300px/s
    for _ in range(20):
        s = k.update(None)
    assert s is None


def test_intent_prediction():
    fps = 30
    mk = lambda i, x: Detection(np.array([x - 20, 300, x + 20, 400.0]), 1, track_id=i)
    players = {1: mk(1, 100), 2: mk(2, 900), 3: mk(3, 500)}
    ip = IntentPredictor(fps)
    ip.update(np.array([110.0, 380, 0, 0]), players)  # A has the ball
    assert ip.possessor == 1
    vel = np.array([900.0, 0])  # pass along y=380 towards x=900
    plan = ip.update(np.array([160.0, 380, *vel]), players)
    assert plan.receiver in (2, 3) and plan.phase == "release" and plan.widen > 0
    # ball is about to arrive at B
    plan = ip.update(np.array([850.0, 380, 300.0, 0]), players)
    assert plan.phase == "approach" and plan.receiver == 2 and plan.widen < 0
    assert np.allclose(extrapolate(np.zeros(2), np.array([10.0, 0]), 0.0), 0)


def test_tracker_ids_stable():
    tr = SimpleTracker()
    ids = []
    for i in range(10):
        d = Detection(np.array([10 + 5 * i, 10, 60 + 5 * i, 110.0]), 0.9)
        ids.append(tr.update([d])[0].track_id)
    assert len(set(ids)) == 1
