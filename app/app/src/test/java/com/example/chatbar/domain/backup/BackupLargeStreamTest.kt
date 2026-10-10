package com.example.chatbar.domain.backup

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupLargeStreamTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun contentRecognitionReadsOnlyEightBytesRegardlessOfTotalLength() {
        var readCount = 0
        val stream = object : InputStream() {
            override fun read(): Int {
                check(readCount < 8) { "Header probe consumed payload" }
                return AppBackupCodec.magic[readCount++].toInt() and 255
            }
        }
        assertTrue(AppBackupCodec.hasMagic(stream))
        assertEquals(8, readCount)
    }

    /** Opt-in disk-heavy regression; fixtures, archive and verification stay bounded in memory. */
    @Test fun encryptedGiBRoundTripPreservesRawBytesWithConstantBuffers() {
        assumeTrue(System.getenv("CHATBAR_BACKUP_LARGE_REGRESSION") == "1")
        val source = temporary.newFolder("source")
        val media = File(source, "images/fixture.bin").also { it.parentFile!!.mkdirs() }
        val buffer = ByteArray(64 * 1024)
        val random = Random(147)
        media.outputStream().buffered().use { output ->
            repeat(16384) { random.nextBytes(buffer); output.write(buffer) }
        }
        assertEquals(1024L * 1024 * 1024, media.length())
        val archive = temporary.newFile("large.cbbackup")
        val password = "synthetic-large-password".toCharArray()
        fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
        archive.outputStream().buffered().use {
            engine().create(source, temporary.newFolder("encode"), it, password, 1, "fixture", BackupPreferences())
        }
        assertTrue(archive.length() > 100L * 1024 * 1024)
        val work = temporary.newFolder("decode")
        archive.inputStream().buffered().use {
            engine().extract(it, password, work, temporary.newFolder("target"), 1)
        }
        val restored = File(work, "files/images/fixture.bin")
        assertEquals(media.length(), restored.length())
        assertArrayEquals(hash(media), hash(restored))
        password.fill('\u0000')
    }

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }
}
