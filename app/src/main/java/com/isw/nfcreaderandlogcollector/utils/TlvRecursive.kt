package com.isw.nfcreaderandlogcollector.utils

/** TLV parser */
object TlvRecursive {
    fun ByteArray.toHex(): String = joinToString("") { String.format("%02X", it.toInt() and 0xFF) }

    fun findTagRecursive(data: ByteArray, tagHex: String): ByteArray? {
        var i = 0
        while (i < data.size) {
            var tagLen = 1
            val tagByte = data[i].toInt() and 0xFF
            if ((tagByte and 0x1F) == 0x1F) tagLen = 2 // extended tag

            val tag = data.copyOfRange(i, i + tagLen).toHex()
            i += tagLen

            // Length parsing
            var len = data[i].toInt() and 0xFF
            i++
            if (len and 0x80 != 0) {
                val numBytes = len and 0x7F
                len = 0
                repeat(numBytes) {
                    len = (len shl 8) + (data[i].toInt() and 0xFF)
                    i++
                }
            }

            val value = data.copyOfRange(i, i + len)
            i += len

            // Found the tag
            if (tag.equals(tagHex, ignoreCase = true)) return value

            // If constructed, recurse
            if ((tagByte and 0x20) != 0) {
                val found = findTagRecursive(value, tagHex)
                if (found != null) return found
            }
        }
        return null
    }

}