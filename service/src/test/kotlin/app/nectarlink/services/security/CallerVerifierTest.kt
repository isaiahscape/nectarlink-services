// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.security

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerVerifierTest {
    @Test
    fun sha256_hex_is_lowercase_64_chars() {
        val digest = CallerVerifier.sha256Hex("nectarlink".toByteArray(Charsets.UTF_8))
        assertEquals(64, digest.length)
        assertEquals("754e5d3a2f2e0f6224739f492978d4a0bc977c86284304318a971e1650b60103".length, digest.length)
        assertEquals(digest.lowercase(), digest)
    }
}
