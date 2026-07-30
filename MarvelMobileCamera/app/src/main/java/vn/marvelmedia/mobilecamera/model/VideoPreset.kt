package vn.marvelmedia.mobilecamera.model

data class VideoPreset(
    val width: Int,
    val height: Int,
    val fps: Int,
    val label: String,
    val defaultRtmpBitrate: Int,
    val defaultSrtBitrate: Int
) {
    companion object {
        val supported = listOf(
            VideoPreset(1920, 1080, 25, "1080p25", 4_500_000, 6_000_000),
            VideoPreset(1920, 1080, 30, "1080p30", 5_000_000, 7_000_000),
            VideoPreset(1920, 1080, 60, "1080p60", 10_000_000, 12_000_000),
            VideoPreset(3840, 2160, 25, "4K25", 18_000_000, 20_000_000),
            VideoPreset(3840, 2160, 30, "4K30", 22_000_000, 24_000_000)
        )
        val default = supported.first { it.label == "1080p30" }
    }
}
