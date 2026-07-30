package vn.marvelmedia.mobilecamera.stream

import android.content.Context
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.library.generic.GenericStream
import vn.marvelmedia.mobilecamera.camera.PhysicalCameraSource
import vn.marvelmedia.mobilecamera.model.CameraDescriptor
import vn.marvelmedia.mobilecamera.model.VideoPreset

class StreamController(
    private val context: Context,
    initialCamera: CameraDescriptor,
    private val listener: StreamListener
) : ConnectChecker {
    private var currentCamera = initialCamera
    private var currentPreset = initialCamera.presets.firstOrNull { it.label == "1080p30" } ?: initialCamera.presets.first()
    private var currentBitrate = currentPreset.defaultSrtBitrate
    private var audioEnabled = true
    private var preview: SurfaceView? = null
    private var prepared = false
    private var activeMode = StreamMode.SRT
    private var stream = createStream()

    init {
        prepared = prepare()
    }

    private fun createStream(): GenericStream = GenericStream(
        context,
        this,
        PhysicalCameraSource(context, currentCamera),
        if (audioEnabled) MicrophoneSource() else NoAudioSource()
    ).also {
        it.getStreamClient().setReTries(30)
        it.getStreamClient().setOnlyVideo(!audioEnabled)
        it.getStreamClient().setCheckServerAlive(false)
    }

    private fun prepare(): Boolean = runCatching {
        val video = stream.prepareVideo(
            width = currentPreset.width,
            height = currentPreset.height,
            bitrate = currentBitrate,
            fps = currentPreset.fps,
            iFrameInterval = 1,
            rotation = 0
        )
        val audio = stream.prepareAudio(48_000, true, 128_000)
        video && audio
    }.getOrDefault(false)

    fun attachPreview(surfaceView: SurfaceView) {
        preview = surfaceView
        if (prepared && surfaceView.holder.surface.isValid && !stream.isOnPreview) {
            runCatching { stream.startPreview(surfaceView) }
        }
    }

    fun configure(camera: CameraDescriptor, preset: VideoPreset, bitrate: Int, audio: Boolean, mode: StreamMode): Boolean {
        if (stream.isStreaming) return false
        currentCamera = camera
        currentPreset = preset
        currentBitrate = bitrate
        audioEnabled = audio
        activeMode = mode
        runCatching { stream.release() }
        stream = createStream()
        prepared = prepare()
        preview?.let { if (prepared && it.holder.surface.isValid) runCatching { stream.startPreview(it) } }
        return prepared
    }

    fun start(endpoint: String) {
        if (!prepared) {
            listener.onStatus("Encoder chưa sẵn sàng")
            return
        }
        if (!stream.isStreaming) stream.startStream(endpoint)
    }

    fun stop() {
        if (stream.isStreaming) stream.stopStream()
    }

    fun switchCamera(camera: CameraDescriptor): Boolean {
        if (camera.stableId == currentCamera.stableId) return true
        val supported = camera.presets.any {
            it.width == currentPreset.width && it.height == currentPreset.height && it.fps == currentPreset.fps
        }
        if (!supported) return false
        return runCatching {
            stream.changeVideoSource(PhysicalCameraSource(context, camera))
            currentCamera = camera
            true
        }.getOrDefault(false)
    }

    fun release() = runCatching { stream.release() }.getOrNull()

    override fun onConnectionStarted(url: String) {
        listener.onStatus(if (activeMode == StreamMode.SRT) "Đang kết nối vMix…" else "Đang kết nối RTMP…")
    }

    override fun onConnectionSuccess() {
        listener.onStatus(if (activeMode == StreamMode.SRT) "SRT đã kết nối vMix" else "RTMP đã kết nối")
        listener.onStreamingChanged(true)
    }

    override fun onConnectionFailed(reason: String) {
        if (stream.getStreamClient().reTry(1_000, reason, null)) {
            listener.onStatus("Mất kết nối, đang thử lại…")
        } else {
            runCatching { stream.stopStream() }
            listener.onStatus("Lỗi kết nối: $reason")
            listener.onStreamingChanged(false)
        }
    }

    override fun onNewBitrate(bitrate: Long) = listener.onBitrate(bitrate)

    override fun onDisconnect() {
        listener.onStatus("Đã ngắt kết nối")
        listener.onStreamingChanged(false)
    }

    override fun onAuthError() = listener.onStatus("Sai xác thực RTMP")
    override fun onAuthSuccess() = listener.onStatus("Xác thực RTMP thành công")
}
