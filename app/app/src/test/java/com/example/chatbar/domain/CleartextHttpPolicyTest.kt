package com.example.chatbar.domain

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextHttpPolicyTest {
    @Test
    fun https_isAlwaysAllowed() {
        assertTrue(CleartextHttpPolicy.isAllowed(isHttps = true, allowCleartextHttp = false))
    }

    @Test
    fun http_isDeniedByDefault() {
        assertFalse(CleartextHttpPolicy.isAllowed(isHttps = false, allowCleartextHttp = false))
    }

    @Test
    fun http_isAllowedAfterExplicitOptIn() {
        assertTrue(CleartextHttpPolicy.isAllowed(isHttps = false, allowCleartextHttp = true))
    }

    @Test
    fun blankApiKeyOmitsAuthorizationHeader() {
        val request = Request.Builder()
            .url("http://127.0.0.1:8080/v1/models")
            .addModelApiAuthorization("  ")
            .build()

        assertNull(request.header("Authorization"))
    }

    @Test
    fun configuredApiKeyAddsBearerAuthorizationHeader() {
        val request = Request.Builder()
            .url("http://127.0.0.1:8080/v1/models")
            .addModelApiAuthorization(" local-key ")
            .build()

        assertEquals("Bearer local-key", request.header("Authorization"))
    }
}
