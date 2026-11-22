// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CallerVerifierTest {
    @Test
    fun sha256_hex_is_lowercase_64_chars() {
        val digest = CallerVerifier.sha256Hex("nectarlink".toByteArray(Charsets.UTF_8))
        assertEquals(64, digest.length)
        assertEquals("754e5d3a2f2e0f6224739f492978d4a0bc977c86284304318a971e1650b60103".length, digest.length)
        assertEquals(digest.lowercase(), digest)
    }

    @Test
    fun decode_sha256_hex_parses_colon_and_plain_hex_and_rejects_invalid() {
        val plain = CallerVerifier.decodeSha256Hex("E055B7E6A622168DA36E7B9F730369C8F140E5BA87DF1E16F31FD754434EEA0D")
        val colon = CallerVerifier.decodeSha256Hex("e0:55:b7:e6:a6:22:16:8d:a3:6e:7b:9f:73:03:69:c8:f1:40:e5:ba:87:df:1e:16:f3:1f:d7:54:43:4e:ea:0d")
        assertNotNull(plain)
        assertNotNull(colon)
        assertEquals(32, plain!!.size)
        assertArrayEquals(plain, colon)
        assertNull(CallerVerifier.decodeSha256Hex("deadbeef"))
        assertNull(CallerVerifier.decodeSha256Hex("z".repeat(64)))
    }
}
