package com.isw.nfcreaderandlogcollector.utils

import android.nfc.tech.IsoDep
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object Utils {


    fun emptyLog(): JSONObject = JSONObject().apply {
        put("aid", "")
        put("appLabel", "")
        put("amount", "")
        put("currency", "")
        put("panMasked", "")
    }


    fun buildPdolFromMap(
        pdolTemplate: ByteArray, dataMap: Map<String, ByteArray>
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        var i = 0
        val pdolHex = pdolTemplate.toHex()

        while (i < pdolHex.length) {
            var tag = pdolHex.substring(i, i + 2); i += 2
            val firstByte = Integer.parseInt(tag, 16)
            if ((firstByte and 0x1F) == 0x1F) {
                while (i + 2 <= pdolHex.length) {
                    val nb = pdolHex.substring(i, i + 2)
                    tag += nb
                    i += 2
                    if ((Integer.parseInt(nb, 16) and 0x80) == 0) break
                }
            }
            if (i + 2 > pdolHex.length) break
            val len = Integer.parseInt(pdolHex.substring(i, i + 2), 16); i += 2

            val value = dataMap[tag.uppercase()] ?: ByteArray(len) { 0x00 }
            baos.write(if (value.size == len) value else value.copyOf(len))
        }
        return baos.toByteArray()
    }

    // ----- Helpers -----

    fun buildGpoApdu(pdolData: ByteArray): ByteArray {
        val data = if (pdolData.isEmpty()) hex("8300")
        else concat(hex("83"), byteArrayOf(pdolData.size.toByte()), pdolData)
        val header = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, data.size.toByte())
        return concat(header, data, byteArrayOf(0x00))
    }

    fun isoTransceiveHandlingGetResponse(iso: IsoDep, apdu: ByteArray): ByteArray {
        var resp = try {
            iso.transceive(apdu)
        } catch (_: Exception) {
            return ByteArray(0)
        }
        if (resp.size >= 2) {
            val sw1 = resp[resp.size - 2].toInt() and 0xFF
            val sw2 = resp[resp.size - 1].toInt() and 0xFF
            if (sw1 == 0x6C) {
                val corrected = apdu.copyOf(apdu.size)
                corrected[corrected.size - 1] = sw2.toByte()
                resp = try {
                    iso.transceive(corrected)
                } catch (_: Exception) {
                    return ByteArray(0)
                }
            }
        }
        var agg = ByteArray(0)
        var current = resp
        while (true) {
            if (current.size < 2) {
                agg = concat(agg, current); break
            }
            val sw1 = current[current.size - 2].toInt() and 0xFF
            val sw2 = current[current.size - 1].toInt() and 0xFF
            val data = current.copyOfRange(0, current.size - 2)
            agg = concat(agg, data)
            when {
                sw1 == 0x90 && sw2 == 0x00 -> break
                sw1 == 0x61 && sw2 > 0 -> {
                    val getResp = hex(String.format("00C00000%02X", sw2))
                    current = try {
                        iso.transceive(getResp)
                    } catch (_: Exception) {
                        ByteArray(0)
                    }
                }

                else -> break
            }
        }
        return agg
    }

    fun readAflRecords(iso: IsoDep, afl: ByteArray): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        var i = 0
        while (i + 3 < afl.size) {
            val byte1 = afl[i].toInt() and 0xFF
            val sfi = (byte1 and 0xF8) shr 3
            val firstRec = afl[i + 1].toInt() and 0xFF
            val lastRec = afl[i + 2].toInt() and 0xFF
            i += 4
            for (rec in firstRec..lastRec) {
                val p1 = rec
                val p2 = (sfi shl 3) or 4
                val apdu = hex(String.format("00B2%02X%02X00", p1, p2))
                try {
                    val raw = iso.transceive(apdu)
                    val data = stripStatusOrHandleGetResp(iso, raw)
                    if (data != null && data.isNotEmpty()) records.add(data)
                } catch (_: Exception) {
                }
            }
        }
        return records
    }

    fun stripStatusOrHandleGetResp(iso: IsoDep, resp: ByteArray): ByteArray? {
        if (resp.size < 2) return resp
        val sw1 = resp[resp.size - 2].toInt() and 0xFF
        val sw2 = resp[resp.size - 1].toInt() and 0xFF
        val data = resp.copyOfRange(0, resp.size - 2)
        return when {
            sw1 == 0x6C -> ByteArray(0)
            sw1 == 0x61 && sw2 > 0 -> {
                val more = try {
                    iso.transceive(hex(String.format("00C00000%02X", sw2)))
                } catch (_: Exception) {
                    ByteArray(0)
                }
                concat(data, more.copyOfRange(0, more.size - 2))
            }

            else -> data
        }
    }

    fun firstNonEmptyHexTag(buffers: List<ByteArray>, tags: List<String>): String {
        for (t in tags) for (b in buffers) {
            val v = TlvRecursive.findTagRecursive(b, t)
            if (v != null && v.isNotEmpty()) return v.toHex()
        }
        return ""
    }

    // ----- Utilities -----
    fun concat(vararg parts: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        for (p in parts) baos.write(p)
        return baos.toByteArray()
    }

    fun hex(s: String): ByteArray {
        val str = s.replace("\\s+".toRegex(), "")
        val out = ByteArray(str.length / 2)
        var i = 0
        while (i < str.length) {
            out[i / 2] =
                ((Character.digit(str[i], 16) shl 4) + Character.digit(str[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    fun ByteArray.toHex(): String = joinToString("") { String.format("%02X", it.toInt() and 0xFF) }

    fun String.hexToAsciiOrEmpty(): String = try {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < this.length) {
            val b = Integer.parseInt(this.substring(i, i + 2), 16)
            if (b == 0) break
            sb.append(b.toChar())
            i += 2
        }
        sb.toString()
    } catch (_: Exception) {
        ""
    }

    fun hexToAscii(hex: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hex.length) {
            sb.append(Integer.parseInt(hex.substring(i, i + 2), 16).toChar())
            i += 2
        }
        return sb.toString()
    }
}