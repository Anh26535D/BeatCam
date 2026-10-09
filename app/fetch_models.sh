#!/bin/sh
# Downloads the on-device MediaPipe models into app/src/main/assets (not committed to git).
set -e
DIR="$(dirname "$0")/src/main/assets"
mkdir -p "$DIR"
cd "$DIR"
B=https://storage.googleapis.com/mediapipe-models
curl -fLo yolox_s.onnx "https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx"
curl -fLo pose_landmarker_lite.task "$B/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
curl -fLo mobilenet_v3_small.tflite "$B/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
