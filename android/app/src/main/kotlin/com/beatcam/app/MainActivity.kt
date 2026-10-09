package com.beatcam.app

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.beatcam.core.CameraConfig
import com.beatcam.core.CropPath
import com.beatcam.core.PunchEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var run: Button
    private var uri: Uri? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        uri = it
        status.text = if (it == null) "No video selected" else "Selected: $it"
        run.isEnabled = it != null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        fun <T : android.view.View> add(v: T) = v.also { root.addView(it, ViewGroup.LayoutParams(-1, -2)) }
        val pick = add(Button(this).apply { text = "Pick video" })
        val beat = add(CheckBox(this).apply { text = "Beat punch-zoom"; isChecked = true })
        val pose = add(CheckBox(this).apply { text = "Pose-aware framing" })
        val sports = add(CheckBox(this).apply { text = "Ball / receiver prediction (sports)" })
        run = add(Button(this).apply { text = "Reframe to 9:16"; isEnabled = false })
        status = add(TextView(this).apply { text = "Pick a video to start" })
        setContentView(root)
        pick.setOnClickListener { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
        run.setOnClickListener { uri?.let { start(it, beat.isChecked, pose.isChecked, sports.isChecked) } }
    }

    private fun start(src: Uri, beat: Boolean, pose: Boolean, sports: Boolean) {
        run.isEnabled = false
        lifecycleScope.launch {
            try {
                val fps = 15.0
                val (path, info) = withContext(Dispatchers.Default) {
                    val punch = if (beat) {
                        status.post { status.text = "Analysing audio…" }
                        PunchEnvelope(AudioAnalyzer.onsets(this@MainActivity, src), 30.0)
                    } else null
                    FrameAnalyzer(this@MainActivity, pose, sports).use { fa ->
                        val info = fa.info(src)
                        val steps = fa.analyse(src, info, fps) { p -> status.post { status.text = "Analysing video ${(p * 100).toInt()}%" } }
                        CropPath.build(steps, info.width, info.height, fps, CameraConfig(), punch, savgolWindow = 7) to info
                    }
                }
                val out = File(getExternalFilesDir(null), "beatcam_${System.currentTimeMillis()}.mp4")
                Reframer(this@MainActivity).export(src, out.path, path, info.width, info.height,
                    onProgress = { status.text = "Rendering ${(it * 100).toInt()}%" },
                    onDone = { r ->
                        status.text = r.fold({ "Saved: ${out.path}" }, { "Failed: ${it.message}" })
                        run.isEnabled = true
                    })
            } catch (e: Exception) {
                status.text = "Failed: ${e.message}"
                run.isEnabled = true
            }
        }
    }
}
