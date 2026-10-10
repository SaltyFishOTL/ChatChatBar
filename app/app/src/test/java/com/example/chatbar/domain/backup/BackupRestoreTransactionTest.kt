package com.example.chatbar.domain.backup

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRestoreTransactionTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Fixture(val files: File, val preferences: File, val work: File) {
        fun transaction(sync: (File) -> Unit = {}) = BackupRestoreTransaction(files, preferences, work, sync)
        fun setPreferences() { File(preferences, "image_mask_preferences.xml").writeText("new-preferences") }
    }

    private fun fixture(name: String): Fixture {
        val parent = temporary.newFolder(name)
        val files = File(parent, "files").also(File::mkdirs)
        val preferences = File(parent, "prefs").also(File::mkdirs)
        val work = File(parent, "work").also(File::mkdirs)
        File(files, "entities/value.json").also { it.parentFile!!.mkdirs(); it.writeText("old-entities") }
        File(files, "images/value.png").also { it.parentFile!!.mkdirs(); it.writeText("old-images") }
        File(files, "diagnostics/kept.txt").also { it.parentFile!!.mkdirs(); it.writeText("diagnostic") }
        File(preferences, "image_mask_preferences.xml").writeText("old-preferences")
        File(work, "prepared/files/entities/value.json").also { it.parentFile!!.mkdirs(); it.writeText("new-entities") }
        File(work, "prepared/files/images/value.png").also { it.parentFile!!.mkdirs(); it.writeText("new-images") }
        File(work, "prepared/manifest.json").writeText("{}")
        File(work, "prepared/preferences.json").writeText("{}")
        return Fixture(files, preferences, work)
    }

    @Test fun initializesThenCommitsAndKeepsExcludedData() {
        val fixture = fixture("fixture")
        val transaction = fixture.transaction()
        transaction.schedule()
        assertTrue(transaction.bootstrap({ fixture.setPreferences() }))
        assertEquals("new-entities", File(fixture.files, "entities/value.json").readText())
        assertTrue(File(fixture.work, "rollback/files/entities/value.json").isFile)
        transaction.commit()
        transaction.cleanupCommitted()
        assertFalse(transaction.pending())
        assertFalse(File(fixture.work, "rollback").exists())
        assertEquals("diagnostic", File(fixture.files, "diagnostics/kept.txt").readText())
    }

    @Test fun interruptedInitializationRollsBackRootsAndDeviceCiphertext() {
        val fixture = fixture("fixture")
        fixture.transaction().apply {
            schedule()
            assertTrue(bootstrap({ fixture.setPreferences() }))
        }
        val restart = fixture.transaction()
        assertFalse(restart.bootstrap({ error("must not apply preferences again") }))
        assertTrue(restart.recoveredRollback)
        assertEquals("old-entities", File(fixture.files, "entities/value.json").readText())
        assertEquals("old-images", File(fixture.files, "images/value.png").readText())
        assertEquals("old-preferences", File(fixture.preferences, "image_mask_preferences.xml").readText())
    }

    @Test fun validationFailureNeverMovesCurrentDataAndAllowsNewImport() {
        val fixture = fixture("fixture")
        val transaction = fixture.transaction()
        transaction.schedule()
        assertThrows(IllegalStateException::class.java) {
            transaction.bootstrap({}, { error("synthetic invalid staged bytes") })
        }
        assertEquals("old-entities", File(fixture.files, "entities/value.json").readText())
        assertFalse(transaction.pending())
    }

    @Test fun directoryMoveInterruptionsRecoverOneWholeState() {
        for (cut in 1..35) {
            val fixture = fixture("cut-" + cut)
            var calls = 0
            var faultEnabled = false
            val transaction = fixture.transaction {
                if (faultEnabled && ++calls >= cut) throw java.io.IOException("synthetic process interruption")
            }
            transaction.schedule()
            faultEnabled = true
            runCatching { transaction.bootstrap({ fixture.setPreferences() }) }
            val resumed = fixture.transaction()
            val applied = resumed.bootstrap({ fixture.setPreferences() })
            val state = if (applied) "new" else "old"
            assertEquals(state + "-entities", File(fixture.files, "entities/value.json").readText())
            assertEquals(state + "-images", File(fixture.files, "images/value.png").readText())
            assertEquals(state + "-preferences", File(fixture.preferences, "image_mask_preferences.xml").readText())
        }
    }

    @Test fun corruptedMappedStageFailsSealBeforeRestore() {
        val fixture = fixture("fixture")
        val prepared = File(fixture.work, "prepared")
        BackupPreparedSeal.write(prepared, {})
        File(prepared, "files/images/value.png").writeText("damaged")
        fixture.transaction().apply {
            schedule()
            assertThrows(IllegalArgumentException::class.java) { bootstrap({}, { BackupPreparedSeal.validate(it) }) }
        }
        assertEquals("old-images", File(fixture.files, "images/value.png").readText())
    }
}
