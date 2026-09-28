package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class EchoSelfTestWavTest {
    private fun le32(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    @Test
    fun walksPaddingChunksToTheData() {
        val fmt = le16(1) + le16(1) + le32(48_000) + le32(96_000) + le16(2) + le16(16)
        val data = byteArrayOf(1, 0, 2, 0, 3, 0)
        val wav = "RIFF".toByteArray() + le32(0) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(fmt.size) + fmt +
            "FLLR".toByteArray() + le32(3) + byteArrayOf(9, 9, 9, 0) + // odd size, padded
            "data".toByteArray() + le32(data.size) + data
        assertArrayEquals(data, EchoSelfTest.pcmFromWav(wav, 48_000))
    }
}
