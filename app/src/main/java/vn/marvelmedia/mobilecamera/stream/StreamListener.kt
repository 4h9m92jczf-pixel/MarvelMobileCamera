package vn.marvelmedia.mobilecamera.stream

interface StreamListener {
    fun onStatus(text: String)
    fun onStreamingChanged(streaming: Boolean)
    fun onBitrate(bitsPerSecond: Long)
}
