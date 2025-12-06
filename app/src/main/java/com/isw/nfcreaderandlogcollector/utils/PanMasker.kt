package com.isw.nfcreaderandlogcollector.utils

/** Mask PAN helper */
object PanMasker {

    fun maskFromDigits(pan: String): String {
        if (pan.length < 6) return pan
        val prefix = pan.substring(0, 6)
        val suffix = pan.takeLast(4)
        val middleLength = (pan.length - 10).coerceAtLeast(1)
        val middle = "*".repeat(middleLength)
        return prefix + middle + suffix
    }


    fun extractPanFromTrack2(track2Hex: String): String {
        val sb = StringBuilder()
        for (i in track2Hex.indices step 2) {
            val b = Integer.parseInt(track2Hex.substring(i, i + 2), 16)
            val hi = (b ushr 4) and 0xF
            val lo = b and 0xF
            if (hi == 0xD) break
            sb.append(hi)
            if (lo == 0xD) break
            sb.append(lo)
        }
        return sb.toString()
    }

    fun extractExpiryFromTrack2(track2Hex: String): String {
        val sb = StringBuilder()
        for (i in track2Hex.indices step 2) {
            val b = Integer.parseInt(track2Hex.substring(i, i + 2), 16)
            val hi = (b ushr 4) and 0xF
            val lo = b and 0xF
            sb.append(if (hi == 0xD) 'D' else hi)
            sb.append(if (lo == 0xD) 'D' else lo)
        }
        val track2 = sb.toString()
        val dIndex = track2.indexOf('D')
        if (dIndex != -1 && dIndex + 5 <= track2.length) {
            return track2.substring(dIndex + 1, dIndex + 5)
        }
        return ""
    }


    fun formatExpiry(yyMm: String): String {
        if (yyMm.length != 4) return yyMm
        val year = yyMm.substring(0, 2)
        val month = yyMm.substring(2, 4)
        return "$month/$year"
    }

}