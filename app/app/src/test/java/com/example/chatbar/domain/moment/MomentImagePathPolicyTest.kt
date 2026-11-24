package com.example.chatbar.domain.moment

import org.junit.Assert.*
import org.junit.Test

class MomentImagePathPolicyTest {
    @Test fun missingImageSpellingsShareOneMeaning() {
        for (path in listOf(null, "", " ", "\t\n")) assertNull(MomentImageVersions.imagePathOrNull(path))
    }
    @Test fun nonblankPathsKeepEveryCharacter() {
        for (path in listOf("real.png", " real.png", "real.png ", "A/../image.png", "a.png", "A.png")) {
            assertEquals(path, MomentImageVersions.imagePathOrNull(path))
        }
    }
}
