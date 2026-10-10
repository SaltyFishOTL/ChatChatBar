package com.example.chatbar.domain.backup

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun engine(check: () -> Unit = {}) = BackupArchive(check, { _, _, _ -> }, { File(it).inputStream() })
    private fun archive(): File {
        val source = temporary.newFolder()
        for (name in listOf("a", "b")) {
            File(source, "images/" + name + ".png").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2, 3)) }
        }
        return temporary.newFile().also { file ->
            file.outputStream().use { engine().create(source, temporary.newFolder(), it, null, 1, "fixture", BackupPreferences()) }
        }
    }

    @Test fun validZipCrcCannotHideMediaShaMismatch() {
        val archive = archive()
        val payload = temporary.newFile()
        archive.inputStream().use { source -> AppBackupCodec.decrypting(source, null).use { plain ->
            payload.outputStream().use { plain.copyTo(it) }
        } }
        val changed = temporary.newFile()
        changed.outputStream().use { out ->
            ZipOutputStream(AppBackupCodec.encrypting(out, null)).use { zip ->
                ZipFile(payload).use { old ->
                    val entries = old.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        zip.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "files/images/a.png") zip.write(byteArrayOf(9, 9, 9))
                        else old.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            changed.inputStream().use { engine().extract(it, null, temporary.newFolder(), temporary.newFolder(), 1) }
        }
    }

    @Test fun duplicateZipNamesAreRejectedBeforeMaterialization() {
        val bytes = archive().readBytes() // tiny synthetic fixture only
        val original = "files/images/b.png".toByteArray(Charsets.US_ASCII)
        val duplicate = "files/images/a.png".toByteArray(Charsets.US_ASCII)
        var replaced = 0
        for (offset in 0..bytes.size - original.size) {
            if (original.indices.all { bytes[offset + it] == original[it] }) {
                duplicate.copyInto(bytes, offset)
                replaced++
            }
        }
        assertEquals(2, replaced)
        assertThrows(IllegalArgumentException::class.java) {
            engine().extract(bytes.inputStream(), null, temporary.newFolder(), temporary.newFolder(), 1)
        }
    }

    @Test fun cancellationAndInsufficientSpaceNeverAlterSourceData() {
        val source = temporary.newFolder()
        val media = File(source, "images/a.png").also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(100000)) }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            temporary.newFile().outputStream().use {
                engine { if (++checks >= 3) throw CancellationException("synthetic cancellation") }
                    .create(source, temporary.newFolder(), it, null, 1, "fixture", BackupPreferences())
            }
        }
        assertEquals(100000L, media.length())
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.ensureSpace(source, Long.MAX_VALUE) }
    }

    @Test fun malformedUtf8IsRejectedInsteadOfReplaced() {
        val source = temporary.newFolder()
        File(source, "entities/model_configs/x.json").also {
            it.parentFile!!.mkdirs()
            it.writeBytes(byteArrayOf(123, 34, -61, 40, 34, 58, 49, 125))
        }
        assertThrows(IllegalStateException::class.java) {
            temporary.newFile().outputStream().use {
                engine().create(source, temporary.newFolder(), it, null, 1, "fixture", BackupPreferences())
            }
        }
    }
}
