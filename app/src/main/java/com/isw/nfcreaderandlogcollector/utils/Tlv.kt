package com.isw.nfcreaderandlogcollector.utils

/** Minimal TLV SELECT builder */
object Tlv {
    fun buildSelect(aid: ByteArray): ByteArray {
        val header = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte())
        return header + aid + byteArrayOf(0x00)
    }
}