package org.szres.glyphlens.recognition

class RgbaFrame(
    val width: Int,
    val height: Int,
    val timestampNanos: Long,
    val pixels: ByteArray,
)
