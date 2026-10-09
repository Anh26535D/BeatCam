#!/bin/sh
# Downloads the on-device MediaPipe models into app/src/main/assets (not committed to git).
set -e
DIR="$(dirname "$0")/src/main/assets"
mkdir -p "$DIR"
cd "$DIR"
B=https://storage.googleapis.com/mediapipe-models
curl -fLo efficientdet_lite2.tflite "$B/object_detector/efficientdet_lite2/float32/1/efficientdet_lite2.tflite"
curl -fLo pose_landmarker_lite.task "$B/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
curl -fLo mobilenet_v3_small.tflite "$B/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
