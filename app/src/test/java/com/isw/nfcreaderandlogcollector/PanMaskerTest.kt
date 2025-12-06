package com.isw.nfcreaderandlogcollector

import com.isw.nfcreaderandlogcollector.utils.PanMasker
import com.isw.nfcreaderandlogcollector.utils.PanMasker.formatExpiry
import org.junit.Assert.assertEquals
import org.junit.Test

class PanMaskerTest {

    @Test
    fun testMaskFromDigits() {
        // Example PAN
        val pan = "4111111111111111"

        // Expected masked PAN: first 6 + '*' + last 4
        val expectedMasked = "411111******1111"

        val masked = PanMasker.maskFromDigits(pan)

        assertEquals(expectedMasked, masked)
    }

    @Test
    fun testMaskShortPan() {
        // Short PAN, less than 6 digits, should remain unchanged
        val shortPan = "12345"
        val masked = PanMasker.maskFromDigits(shortPan)
        assertEquals(shortPan, masked)
    }

    @Test
    fun testExpiryFormatting() {
        val rawExpiry = "2610"
        val formatted = formatExpiry(rawExpiry)
        assertEquals("10/26", formatted)
    }

}
