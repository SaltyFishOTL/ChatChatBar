package com.example.chatbar.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `missing patch version matches release patch zero`() {
        assertEquals(0, compareReleaseVersions("v1.0.0", "1.0"))
        assertFalse(isReleaseVersionNewer("v1.0.0", "1.0"))
    }

    @Test
    fun `newer patch and minor releases are updates`() {
        assertTrue(isReleaseVersionNewer("1.0.1", "1.0.0"))
        assertTrue(isReleaseVersionNewer("v1.1.0", "1.0.9"))
    }

    @Test
    fun `older release is not update`() {
        assertFalse(isReleaseVersionNewer("0.9.9", "1.0.0"))
    }
}
