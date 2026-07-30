package vn.marvelmedia.mobilecamera.model

import android.hardware.camera2.CameraCharacteristics

data class CameraDescriptor(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val facing: Int,
    val focalLengthMm: Float?,
    val label: String,
    val presets: List<VideoPreset>
) {
    val stableId: String = "$logicalCameraId:${physicalCameraId ?: "logical"}"
    val isFront: Boolean = facing == CameraCharacteristics.LENS_FACING_FRONT
}
