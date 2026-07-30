package vn.marvelmedia.mobilecamera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Range
import vn.marvelmedia.mobilecamera.model.CameraDescriptor
import vn.marvelmedia.mobilecamera.model.VideoPreset

class CameraCapabilityScanner(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun scan(): List<CameraDescriptor> {
        val result = mutableListOf<CameraDescriptor>()
        manager.cameraIdList.forEach { logicalId ->
            val logical = runCatching { manager.getCameraCharacteristics(logicalId) }.getOrNull() ?: return@forEach
            val facing = logical.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) logical.physicalCameraIds else emptySet()

            // Keep the logical camera. Some manufacturers automatically change physical lens while zooming.
            descriptor(logicalId, null, logical, facing)?.let(result::add)

            // Also expose every physical lens Android publishes. The source will use a physical output route.
            physicalIds.forEach { physicalId ->
                val chars = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrElse { logical }
                descriptor(logicalId, physicalId, chars, facing)?.let(result::add)
            }
        }

        return result
            .distinctBy { it.logicalCameraId to it.physicalCameraId }
            .sortedWith(compareBy<CameraDescriptor> { it.isFront }.thenBy { it.focalLengthMm ?: 999f })
            .mapIndexed { index, item -> item.copy(label = friendlyLabel(item, index)) }
    }

    private fun descriptor(
        logicalId: String,
        physicalId: String?,
        chars: CameraCharacteristics,
        fallbackFacing: Int
    ): CameraDescriptor? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: fallbackFacing
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val presets = VideoPreset.supported.filter { preset ->
            sizes.any { it.width == preset.width && it.height == preset.height } && supportsFps(fpsRanges, preset.fps)
        }
        if (presets.isEmpty()) return null
        return CameraDescriptor(logicalId, physicalId, facing, focal, "Camera", presets)
    }

    private fun supportsFps(ranges: List<Range<Int>>, fps: Int): Boolean =
        ranges.any { range -> range.lower <= fps && range.upper >= fps }

    private fun friendlyLabel(item: CameraDescriptor, index: Int): String {
        if (item.isFront) return if (item.physicalCameraId == null) "FRONT" else "FRONT ${index + 1}"
        val focal = item.focalLengthMm ?: return if (item.physicalCameraId == null) "AUTO" else "LENS ${index + 1}"
        return when {
            focal < 2.5f -> "0.5×"
            focal < 4.5f -> "1×"
            focal < 8.0f -> "2×"
            else -> "TELE"
        }
    }
}
