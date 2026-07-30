package vn.marvelmedia.mobilecamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.marvelmedia.mobilecamera.camera.CameraCapabilityScanner
import vn.marvelmedia.mobilecamera.databinding.ActivityMainBinding
import vn.marvelmedia.mobilecamera.model.CameraDescriptor
import vn.marvelmedia.mobilecamera.model.VideoPreset
import vn.marvelmedia.mobilecamera.stream.StreamController
import vn.marvelmedia.mobilecamera.stream.StreamListener
import vn.marvelmedia.mobilecamera.stream.StreamMode
import java.util.Locale

class MainActivity : AppCompatActivity(), StreamListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences
    private var cameras: List<CameraDescriptor> = emptyList()
    private var currentCamera: CameraDescriptor? = null
    private var currentPreset: VideoPreset = VideoPreset.default
    private var mode = StreamMode.SRT
    private var controller: StreamController? = null
    private var streaming = false
    private var startedAt = 0L
    private var lastBitrate = 0L
    private var updatingSpinners = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            initializeCameraStack()
        } else {
            onStatus("Không có quyền camera")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferences = AppPreferences(this)

        binding.rtmpUrl.setText(preferences.rtmpUrl)
        binding.streamKey.setText(preferences.streamKey)
        binding.srtHost.setText(preferences.srtHost)
        binding.modeGroup.check(binding.srtMode.id)

        bindControls()
        binding.startStopButton.setOnClickListener { toggleStreaming() }
        binding.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                controller?.attachPreview(binding.preview)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })

        requestPermissionsIfNeeded()
        startTelemetryClock()
    }

    private fun requestPermissionsIfNeeded() {
        val required = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (required.isEmpty()) initializeCameraStack() else permissions.launch(required.toTypedArray())
    }

    private fun initializeCameraStack() {
        if (controller != null) return
        cameras = CameraCapabilityScanner(this).scan()
        if (cameras.isEmpty()) {
            onStatus("Không tìm thấy camera hỗ trợ 1080p hoặc 4K")
            binding.startStopButton.isEnabled = false
            return
        }
        currentCamera = cameras.firstOrNull { !it.isFront } ?: cameras.first()
        currentPreset = currentCamera!!.presets.firstOrNull { it.label == VideoPreset.default.label }
            ?: currentCamera!!.presets.first()
        controller = StreamController(this, currentCamera!!, this)
        if (binding.preview.holder.surface.isValid) controller?.attachPreview(binding.preview)
        renderCameraButtons()
        updatePresetSelectors()
        updateModeUi()
        onStatus("Sẵn sàng • ${cameras.size} camera/ống kính")
    }

    private fun bindControls() {
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || streaming) return@addOnButtonCheckedListener
            mode = if (checkedId == binding.rtmpMode.id) StreamMode.RTMP else StreamMode.SRT
            updateModeUi()
            updateSuggestedBitrate()
        }
        binding.resolutionSpinner.onItemSelectedListener = SimpleSelectionListener {
            if (!updatingSpinners) refreshFpsOptions()
        }
        binding.fpsSpinner.onItemSelectedListener = SimpleSelectionListener {
            if (!updatingSpinners) {
                selectCurrentPreset()
                updateSuggestedBitrate()
            }
        }
    }

    private fun updateModeUi() {
        val srt = mode == StreamMode.SRT
        binding.srtHostLayout.visibility = if (srt) View.VISIBLE else View.GONE
        binding.srtDefaults.visibility = if (srt) View.VISIBLE else View.GONE
        binding.rtmpUrlLayout.visibility = if (srt) View.GONE else View.VISIBLE
        binding.streamKeyLayout.visibility = if (srt) View.GONE else View.VISIBLE
        binding.capabilityNote.text = if (srt) {
            "Nhập IPv4 của máy tính chạy vMix. vMix dùng SRT Listener cổng 10080."
        } else {
            "Nhập RTMP URL và stream key của server."
        }
    }

    private fun renderCameraButtons() {
        binding.cameraButtons.removeAllViews()
        cameras.forEach { camera ->
            val button = MaterialButton(this).apply {
                text = camera.label
                isCheckable = true
                isChecked = camera.stableId == currentCamera?.stableId
                setOnClickListener { selectCamera(camera) }
            }
            binding.cameraButtons.addView(button)
        }
    }

    private fun selectCamera(camera: CameraDescriptor) {
        if (camera.stableId == currentCamera?.stableId) return
        if (streaming) {
            if (camera.presets.none { it.label == currentPreset.label }) {
                onStatus("${camera.label} không hỗ trợ ${currentPreset.label}")
                return
            }
            if (controller?.switchCamera(camera) != true) {
                onStatus("Không thể chuyển sang ${camera.label} khi đang phát")
                return
            }
            currentCamera = camera
            onStatus("Đã chuyển sang ${camera.label}")
        } else {
            currentCamera = camera
            if (camera.presets.none { it.label == currentPreset.label }) {
                currentPreset = camera.presets.firstOrNull { it.label == VideoPreset.default.label }
                    ?: camera.presets.first()
            }
            updatePresetSelectors()
            updateSuggestedBitrate()
            val bitrate = readBitrate()
            controller?.configure(camera, currentPreset, bitrate, binding.audioSwitch.isChecked, mode)
            onStatus("Đã chọn ${camera.label}")
        }
        updateCameraButtonState()
    }

    private fun updateCameraButtonState() {
        binding.cameraButtons.children.filterIsInstance<MaterialButton>().forEachIndexed { index, button ->
            button.isChecked = cameras[index].stableId == currentCamera?.stableId
        }
    }

    private fun updatePresetSelectors() {
        val camera = currentCamera ?: return
        updatingSpinners = true
        val resolutions = camera.presets.map { if (it.width == 3840) "4K" else "1080p" }.distinct()
        val selectedResolution = if (currentPreset.width == 3840) "4K" else "1080p"
        binding.resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        binding.resolutionSpinner.setSelection(resolutions.indexOf(selectedResolution).coerceAtLeast(0))
        updatingSpinners = false
        refreshFpsOptions()
    }

    private fun refreshFpsOptions() {
        val camera = currentCamera ?: return
        updatingSpinners = true
        val resolution = binding.resolutionSpinner.selectedItem?.toString() ?: "1080p"
        val fpsValues = camera.presets
            .filter { (it.width == 3840) == (resolution == "4K") }
            .map { it.fps }
            .distinct()
        binding.fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsValues)
        binding.fpsSpinner.setSelection(fpsValues.indexOf(currentPreset.fps).coerceAtLeast(0))
        updatingSpinners = false
        selectCurrentPreset()
        updateSuggestedBitrate()
    }

    private fun selectCurrentPreset() {
        val camera = currentCamera ?: return
        val is4k = binding.resolutionSpinner.selectedItem?.toString() == "4K"
        val fps = binding.fpsSpinner.selectedItem as? Int ?: 30
        currentPreset = camera.presets.firstOrNull { (it.width == 3840) == is4k && it.fps == fps }
            ?: camera.presets.firstOrNull { it.label == VideoPreset.default.label }
            ?: camera.presets.first()
    }

    private fun updateSuggestedBitrate() {
        val suggested = if (mode == StreamMode.SRT) currentPreset.defaultSrtBitrate else currentPreset.defaultRtmpBitrate
        binding.bitrateMbps.setText(String.format(Locale.US, "%.0f", suggested / 1_000_000f))
    }

    private fun readBitrate(): Int {
        val fallback = if (mode == StreamMode.SRT) currentPreset.defaultSrtBitrate else currentPreset.defaultRtmpBitrate
        return ((binding.bitrateMbps.text?.toString()?.toFloatOrNull() ?: fallback / 1_000_000f) * 1_000_000).toInt()
            .coerceIn(2_000_000, 50_000_000)
    }

    private fun toggleStreaming() {
        if (streaming) {
            controller?.stop()
            onStreamingChanged(false)
            return
        }
        selectCurrentPreset()
        val camera = currentCamera ?: return
        val bitrate = readBitrate()
        val audio = binding.audioSwitch.isChecked
        val ready = controller?.configure(camera, currentPreset, bitrate, audio, mode) == true
        if (!ready) {
            onStatus("Thiết bị không mã hóa được ${currentPreset.label}")
            return
        }

        when (mode) {
            StreamMode.SRT -> {
                val host = binding.srtHost.text?.toString()?.trim().orEmpty()
                if (host.isBlank()) {
                    onStatus("Hãy nhập IP của máy tính chạy vMix")
                    return
                }
                preferences.srtHost = host
                controller?.start("srt://$host:10080?streamid=marvel-mobile&latency=60")
            }
            StreamMode.RTMP -> {
                val base = binding.rtmpUrl.text?.toString()?.trim().orEmpty().trimEnd('/')
                val key = binding.streamKey.text?.toString()?.trim().orEmpty().trimStart('/')
                if (base.isBlank() || key.isBlank()) {
                    onStatus("Hãy nhập RTMP URL và stream key")
                    return
                }
                preferences.rtmpUrl = base
                preferences.streamKey = key
                controller?.start("$base/$key")
            }
        }
    }

    override fun onStatus(text: String) = runOnUiThread { binding.statusText.text = text }

    override fun onStreamingChanged(streaming: Boolean) = runOnUiThread {
        this.streaming = streaming
        if (streaming) {
            startedAt = SystemClock.elapsedRealtime()
        } else {
            startedAt = 0L
            lastBitrate = 0L
        }
        binding.startStopButton.text = if (streaming) "DỪNG PHÁT" else "BẮT ĐẦU PHÁT"
        binding.liveBadge.text = if (streaming) "LIVE" else "STANDBY"
        binding.modeGroup.isEnabled = !streaming
        binding.resolutionSpinner.isEnabled = !streaming
        binding.fpsSpinner.isEnabled = !streaming
        binding.bitrateMbps.isEnabled = !streaming
        binding.audioSwitch.isEnabled = !streaming
        binding.srtHost.isEnabled = !streaming
        binding.rtmpUrl.isEnabled = !streaming
        binding.streamKey.isEnabled = !streaming
    }

    override fun onBitrate(bitsPerSecond: Long) {
        lastBitrate = bitsPerSecond
    }

    private fun startTelemetryClock() {
        lifecycleScope.launch {
            while (isActive) {
                val elapsed = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt
                val seconds = elapsed / 1000
                val time = String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                binding.telemetryText.text = String.format(Locale.US, "%.1f Mbps  •  %s", lastBitrate / 1_000_000f, time)
                delay(500)
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

private class SimpleSelectionListener(private val action: () -> Unit) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
