package com.beatcam.app

import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate

/** Creates a MediaPipe task on the GPU when asked to, falling back to the CPU if the GPU delegate cannot start. */
object Delegates {
    /** True if the last create() actually ended up on the GPU (shown in the stats line). */
    @Volatile var lastOnGpu = false; private set

    fun <T> create(gpu: Boolean, build: (BaseOptions.Builder) -> T): T? {
        if (gpu) {
            val t = runCatching { build(BaseOptions.builder().setDelegate(Delegate.GPU)) }.getOrNull()
            if (t != null) { lastOnGpu = true; return t }
        }
        lastOnGpu = false
        return runCatching { build(BaseOptions.builder()) }.getOrNull()
    }
}
