package com.metrolist.spotify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyAuthTotpTest {
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun base32DecodesRfcSecret() {
        assertArrayEquals("12345678901234567890".toByteArray(), SpotifyAuth.base32Decode(secret))
    }

    @Test
    fun totpMatchesRfcVectors() {
        assertEquals("287082", SpotifyAuth.generateTotp(secret, 59))
        assertEquals("081804", SpotifyAuth.generateTotp(secret, 1111111109))
        assertEquals("353130", SpotifyAuth.generateTotp(secret, 20000000000))
    }
}
