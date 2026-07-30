package vn.marvelmedia.mobilecamera

import android.content.Context

class AppPreferences(context: Context) {
    private val p = context.getSharedPreferences("marvel_mobile_camera", Context.MODE_PRIVATE)

    var rtmpUrl: String
        get() = p.getString("rtmp_url", "") ?: ""
        set(value) = p.edit().putString("rtmp_url", value).apply()

    var streamKey: String
        get() = p.getString("stream_key", "") ?: ""
        set(value) = p.edit().putString("stream_key", value).apply()

    var srtHost: String
        get() = p.getString("srt_host", "") ?: ""
        set(value) = p.edit().putString("srt_host", value).apply()
}
