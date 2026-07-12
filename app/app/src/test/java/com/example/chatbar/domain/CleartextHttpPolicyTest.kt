package com.example.chatbar.domain

import org.junit.Assert.assertFalse
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
}
