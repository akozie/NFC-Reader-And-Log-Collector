package com.isw.nfcreaderandlogcollector

import android.nfc.tech.IsoDep
import android.util.Log
import com.isw.nfcreaderandlogcollector.utils.PanMasker
import com.isw.nfcreaderandlogcollector.utils.PanMasker.extractExpiryFromTrack2
import com.isw.nfcreaderandlogcollector.utils.PanMasker.extractPanFromTrack2
import com.isw.nfcreaderandlogcollector.utils.PanMasker.formatExpiry
import com.isw.nfcreaderandlogcollector.utils.Tlv
import com.isw.nfcreaderandlogcollector.utils.TlvRecursive
import com.isw.nfcreaderandlogcollector.utils.Utils.buildGpoApdu
import com.isw.nfcreaderandlogcollector.utils.Utils.buildPdolFromMap
import com.isw.nfcreaderandlogcollector.utils.Utils.emptyLog
import com.isw.nfcreaderandlogcollector.utils.Utils.firstNonEmptyHexTag
import com.isw.nfcreaderandlogcollector.utils.Utils.hex
import com.isw.nfcreaderandlogcollector.utils.Utils.hexToAscii
import com.isw.nfcreaderandlogcollector.utils.Utils.hexToAsciiOrEmpty
import com.isw.nfcreaderandlogcollector.utils.Utils.isoTransceiveHandlingGetResponse
import com.isw.nfcreaderandlogcollector.utils.Utils.readAflRecords
import com.isw.nfcreaderandlogcollector.utils.Utils.stripStatusOrHandleGetResp
import com.isw.nfcreaderandlogcollector.utils.Utils.toHex
import org.json.JSONObject

/**
 * Full EMV reader utility:
 * - SELECT PPSE / AID
 * - Build PDOL using provided terminal parameters
 * - Send GPO (handles 6C/61)
 * - Parse AFL and READ RECORDS
 * - Extract PAN (5A/57), expiry (5F24), amount (9F02), currency (5F2A)
 */
object EmvReader {

    private const val TAG = "EMV"

    private val myData: Map<String, ByteArray> = mapOf(
        "5F2A" to byteArrayOf(0x02, 0x36), // NGN
        "9F09" to byteArrayOf(0x00, 0x8C.toByte()),
        "9F66" to byteArrayOf(0xE6.toByte(), 0x00, 0x40, 0x00), // TTQ
        "9F33" to byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte()), // terminal capabilities
        "9F40" to byteArrayOf(
            0x60.toByte(), 0x00, 0x00, 0x50, 0x01
        ),       // additional terminal capabilities
    )

    // Known fallback AIDs for Verve
    private val fallbackAids = listOf(
        "A0000005241010", "A0000005244010"
    )

    fun readCard(
        isoDep: IsoDep, verbose: Boolean = false, currencyCode: Int = 566, userAmount: Long? = null
    ): JSONObject {
        try {
            val myDataWithCurrency = myData.toMutableMap()
            myDataWithCurrency["5F2A"] =
                byteArrayOf((currencyCode shr 8).toByte(), (currencyCode and 0xFF).toByte())

            // SELECT PPSE
            val ppseApdu = hex("00A404000E325041592E5359532E444446303100")
            val ppseResp = isoTransceiveHandlingGetResponse(isoDep, ppseApdu)

            var aidBytes =
                TlvRecursive.findTagRecursive(ppseResp, "4F") ?: TlvRecursive.findTagRecursive(
                    ppseResp, "84"
                )

            // fallback: if PPSE fails, try known AIDs
            if (aidBytes == null) {
                for (aidHex in fallbackAids) {
                    val selectAidResp = isoTransceiveHandlingGetResponse(
                        isoDep, Tlv.buildSelect(hex(aidHex))
                    )
                    val foundAid = TlvRecursive.findTagRecursive(selectAidResp, "4F")
                        ?: TlvRecursive.findTagRecursive(selectAidResp, "84")
                    if (foundAid != null) {
                        aidBytes = foundAid
                        break
                    }
                }
            }

            if (aidBytes == null || aidBytes.isEmpty()) return emptyLog()

            val aidHex = aidBytes.toHex()
            val selectAidResp = isoTransceiveHandlingGetResponse(isoDep, Tlv.buildSelect(aidBytes))

            val appLabel =
                TlvRecursive.findTagRecursive(selectAidResp, "50")?.toHex()?.hexToAsciiOrEmpty()
                    ?: ""

            // Build PDOL
            val pdolTemplate = TlvRecursive.findTagRecursive(selectAidResp, "9F38")
            val pdolData =
                if (pdolTemplate != null) buildPdolFromMap(pdolTemplate, myData) else ByteArray(0)
            val gpoResp = isoTransceiveHandlingGetResponse(isoDep, buildGpoApdu(pdolData))

            // Extract AFL
            val aflBytes = TlvRecursive.findTagRecursive(gpoResp, "94")
            val records = if (aflBytes != null) readAflRecords(
                isoDep, aflBytes
            ) else bruteForceRecords(isoDep)

            val recordsToSearch = mutableListOf<ByteArray>().apply {
                add(selectAidResp)
                add(ppseResp)
                add(gpoResp)
                addAll(records)
            }

            // Extract PAN
            val panHex = firstNonEmptyHexTag(recordsToSearch, listOf("5A", "57"))
            val maskedPan = PanMasker.maskFromDigits(extractPanFromTrack2(panHex))

            // Currency / Amount / Expiry
            val currencyMap = mapOf(
                8 to "ALL", 36 to "AUD", 840 to "USD", 566 to "NGN", 978 to "EUR"
            )


            val currencyBytes = recordsToSearch.mapNotNull { record ->
                TlvRecursive.findTagRecursive(record, "5F2A")
            }.firstOrNull() ?: myData["5F2A"]


            val currencyCode = (((currencyBytes?.get(0)?.toInt())?.and(0xFF))?.shl(8))?.or(
                (currencyBytes?.get(1)?.toInt()!! and 0xFF)
            )

            currencyBytes?.joinToString(",") { it.toString() }?.let { Log.d("CURRENCY_BYTES", it) }

            val currency = currencyMap[currencyCode] ?: currencyCode

            // Amount
            val amountBytes = if (userAmount != null) {
                // userAmount is already in minor units (e.g., kobo for NGN)
                Log.d(TAG, "User amount provided: $userAmount")
                val b = ByteArray(6)
                var tmp = userAmount
                for (i in 5 downTo 0) {
                    if (tmp != null) {
                        b[i] = (tmp and 0xFF).toByte()
                    }
                    if (tmp != null) {
                        tmp = tmp shr 8
                    }
                }
                Log.d(
                    TAG, "Amount bytes (hex): ${b.joinToString("") { String.format("%02X", it) }}"
                )
                b
            } else {
                // extract from card
                val extracted =
                    recordsToSearch.mapNotNull { TlvRecursive.findTagRecursive(it, "9F02") }
                        .firstOrNull()
                if (extracted != null) {
                    Log.d(
                        TAG, "Amount bytes from card (hex): ${
                            extracted.joinToString("") {
                                String.format(
                                    "%02X", it
                                )
                            }
                        }")
                } else {
                    Log.d(TAG, "No amount bytes found on card")
                }
                extracted
            }

            val amountInt = amountBytes?.fold(0L) { acc, byte ->
                (acc shl 8) or (byte.toInt() and 0xFF).toLong()
            } ?: 0L

            val amountStr = String.format("%.2f", amountInt / 100.0)


            // Card Expiry
            val expiryHex = extractExpiry(recordsToSearch)
            val cardExpiry = formatExpiry(expiryHex.substring(0, 4))

            Log.d("EXPIRYYNN", cardExpiry)

            return JSONObject().apply {
                put("aid", aidHex)
                put("appLabel", appLabel)
                put("amount", amountStr)
                put("currency", currency)
                put("expiry", cardExpiry)
                put("panMasked", maskedPan)
                if (verbose) {
                    put("records", records.joinToString(",") { it.toHex() })
                    put("selectResp", selectAidResp.toHex())
                    put("ppseResp", ppseResp.toHex())
                    put("gpoResp", gpoResp.toHex())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during EMV read", e)
            return emptyLog()
        }
    }

    private fun extractExpiry(records: List<ByteArray>): String {
        val exp5F24 = firstNonEmptyHexTag(records, listOf("5F24"))
        if (exp5F24.isNotEmpty()) return exp5F24
        val track2 = firstNonEmptyHexTag(records, listOf("57"))
        if (track2.isNotEmpty()) {
            val ascii = hexToAscii(track2)
            Log.d(TAG, "Track2 Hex: $track2")
            Log.d(TAG, "Track2 ASCII: $ascii")
        }

        // fallback: try to parse PAN-like fields
        for (record in records) {
            val pan = TlvRecursive.findTagRecursive(record, "57") ?: TlvRecursive.findTagRecursive(
                record, "5A"
            )
            if (pan != null) return extractExpiryFromTrack2(pan.toHex())
        }
        return ""
    }


    private fun bruteForceRecords(iso: IsoDep): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        for (sfi in 1..30) {
            for (rec in 1..10) {
                val apdu = hex(String.format("00B2%02X%02X00", rec, (sfi shl 3) or 4))
                try {
                    val data = stripStatusOrHandleGetResp(iso, iso.transceive(apdu))
                    if (data != null && data.isNotEmpty()) records.add(data)
                } catch (_: Exception) {
                }
            }
        }
        return records
    }

    // ------ rest of your helpers unchanged (isoTransceiveHandlingGetResponse, readAflRecords, buildPdolFromMap, stripStatusOrHandleGetResp, firstNonEmptyHexTag, extractPanFromPossibleTrack2, extractExpiryFromTrack2, PanMasker, etc.) ------


}






