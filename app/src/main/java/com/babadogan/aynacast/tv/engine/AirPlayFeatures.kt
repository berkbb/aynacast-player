package com.babadogan.aynacast.tv.engine

/**
 * AirPlay "features" bit mask advertised over mDNS (_airplay._tcp "features") and GET /info.
 * Keep in sync with the integer in raop_rtsp_server.cpp (GET /info "features").
 */
object AirPlayFeatures {

    const val VIDEO = 1L shl 0
    const val VIDEO_HTTP_LIVE_STREAMS = 1L shl 4

    /**
     * RPiPlay's mask this receiver was validated with for screen mirroring + audio. It includes URL
     * video playback (bits 0 and 4, which UxPlay sets with "-hls"), now that video casting is
     * implemented (VideoCastHandler), so apps such as YouTube can hand over their video.
     */
    const val VALUE = 0x1E5A7FFFF7L

    /** mDNS TXT form: "<low 32 bits>,<high 32 bits>". */
    fun txtValue(): String = "0x%X,0x%X".format(VALUE and 0xFFFFFFFFL, VALUE ushr 32)
}
