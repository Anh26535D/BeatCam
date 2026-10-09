# BeatCam for Android (Kotlin)

Offline "reframe to 9:16" editor: pick a video, get a smooth virtual-camera crop with beat-synced punch zoom,
optional pose-aware and ball-intent framing.

```

  core/   pure Kotlin/JVM, no Android deps, unit-tested (gradle :core:test)
          Filters (One-Euro, Savitzky-Golay), Camera (dead-zone, asymmetric damping), Tracker (ByteTrack-style),
          Audio (FFT, kick/snare band onsets), Beat (bounce-eased punch), Pose (gestures), Ball (Kalman + intent),
          Planner (per-frame state machine) and CropPath (interpolated path + punch at render time)
  app/    Android UI + platform glue
          AudioAnalyzer  MediaExtractor/MediaCodec -> mono PCM -> onsets
          FrameAnalyzer  MediaMetadataRetriever frames @15 fps -> MediaPipe ObjectDetector (+PoseLandmarker) -> Planner
          Reframer       Media3 Transformer: per-timestamp crop matrix on the GPU + 9:16 encode (H.264/HEVC), audio kept
```

## Build
```
sh app/fetch_models.sh        # downloads efficientdet_lite0.tflite + pose_landmarker_lite.task into assets/
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :core:test             # works without the Android SDK
gradle :app:assembleDebug
```
Requires JDK 17+, Android SDK 35. minSdk 26.

## Design notes
- Analysis runs at 15 fps; the camera path is interpolated to video timestamps and the punch zoom is applied at
  render time, so beats stay frame-accurate regardless of the analysis rate.
- Phase 4 uses COCO "sports ball" from EfficientDet-Lite0. Small fast balls need a dedicated model (TrackNet-style);
  plug it in by emitting `Detection(label = BALL)` from `FrameAnalyzer`.
