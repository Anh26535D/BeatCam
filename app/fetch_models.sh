#!/bin/sh
# Downloads the on-device MediaPipe models into app/src/main/assets (not committed to git).
set -e
cd "$(dirname "$0")/src/main/assets"
B=https://storage.googleapis.com/mediapipe-models
curl -fLo efficientdet_lite0.tflite "$B/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite"
curl -fLo pose_landmarker_lite.task "$B/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
