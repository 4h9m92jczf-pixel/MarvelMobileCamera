package vn.marvelmedia.mobilecamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.sources.video.VideoSource
import vn.marvelmedia.mobilecamera.model.CameraDescriptor
import java.util.concurrent.Executor

/** Camera2 source that can explicitly route output to an Android-exposed physical lens. */
@RequiresApi(Build.VERSION_CODES.P)
class PhysicalCameraSource(
    context: Context,
    private val descriptor: CameraDescriptor
) : VideoSource() {
    private val manager = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var outputSurface: Surface? = null
    @Volatile private var running = false

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        return descriptor.presets.any { it.width == width && it.height == height && it.fps == fps }
    }

    @SuppressLint("MissingPermission")
    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (running) return
        surfaceTexture.setDefaultBufferSize(width, height)
        outputSurface = Surface(surfaceTexture)
        thread = HandlerThread("MarvelCamera-${descriptor.stableId}").also { it.start() }
        handler = Handler(thread!!.looper)
        manager.openCamera(descriptor.logicalCameraId, stateCallback, handler)
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            running = false
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            running = false
        }
    }

    private fun createSession(camera: CameraDevice) {
        val surface = outputSurface ?: return
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            chooseFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }

        val outputConfig = OutputConfiguration(surface).apply {
            descriptor.physicalCameraId?.let(::setPhysicalCameraId)
        }
        val executor = Executor { command -> handler?.post(command) }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(outputConfig),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    session = captureSession
                    runCatching { captureSession.setRepeatingRequest(request.build(), null, handler) }
                        .onSuccess { running = true }
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    running = false
                }
            }
        )
        camera.createCaptureSession(config)
    }

    private fun chooseFpsRange(): Range<Int>? {
        val id = descriptor.physicalCameraId ?: descriptor.logicalCameraId
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return null
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges
            .filter { it.lower <= fps && it.upper >= fps }
            .minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenByDescending { it.lower })
    }

    override fun stop() {
        running = false
        runCatching { session?.stopRepeating() }
        session?.close()
        session = null
        device?.close()
        device = null
        outputSurface?.release()
        outputSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun release() = stop()
    override fun isRunning(): Boolean = running
}
