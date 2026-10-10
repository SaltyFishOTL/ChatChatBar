---
name: chatbar-app-backup
description: Maintain ChatBar whole-app .cbbackup migration, streaming archives, optional password encryption, path portability, write exclusion, and boot-time transactional recovery. Use chatbar-save-slot for individual conversation archives.
---

# ChatBar Full App Backup

Source paths below are relative to app/app/src/main/java/com/example/chatbar/.

## Owners

- Entry: ui/manage/GlobalSettingsScreen.kt ninth category 数据迁移; existing save/discard navigation guard applies. AppBackupHost.kt owns secure input, SAF, summary/replace confirmation, progress/cancel, and close/reopen.
- Operation: domain/backup/AppBackupService.kt application-owned job/StateFlow. Flush latest Studio draft before snapshot; pending restore holds maintenance lease until process exit. Password stays in volatile operation memory only.
- Scope: BackupDataRegistry.kt registers persistent roots, portable preferences, and resource fields. New stores also need BackupEntityValidator.kt registration; unknown stores fail export.
- Format: AppBackupCodec.kt, BackupArchive.kt, BackupJsonStream.kt, BackupTextIO.kt. Fixed CBBACKUP head + bounded v1 envelope + ZIP: files/, preferences.json, inventory.jsonl, manifest.json. Original media copied; inventory streams size/SHA-256 records. CRC/duplicates/path containment/version/space checks run before accepting staging.
- Crypto: Tink Android 1.23.0 AES256_GCM_HKDF_1MB, random keyset wrapped using PBKDF2-HMAC-SHA256/600000 + AES-GCM. Envelope authenticates payload. NovelAI/Fish readForBackup distinguishes absent credentials from undecryptable ciphertext; destination Keystore reencrypts on restore.
- Consistency: LocalDataMaintenance.kt counts access across coroutines/threads, waits for a quiet window, then excludes new writers. Protect complete multi-store operations and queued writes. AI lease owns admission; local transfers use LocalDataTransferForegroundService.runProtected independently of network checks.
- Recovery: BackupPreparedSeal.kt, BackupRestoreTransaction.kt, AppBackupBootstrap.kt. noBackupFilesDir/app-backup owns prepared/, rollback/, restore.json, and interrupted-operation progress. Seal mapped bytes and sync files/directories before PREPARED.
- Startup: ChatBarApp runs bootstrap on IO before constructing repositories/preferences. MainActivity waits for backupStartupReady, blocks on errors, and defers shared intents. Alarm/accessibility/QQ components respect readiness. Commit follows successful persistent initialization.
- Sharing: domain/card/SharedImportCoordinator.kt probes eight bytes before full reads; only .cbbackup bypasses 100 MB and uses offline foreground staging. ui/shared/SharedImportHost.kt routes Backup into explicit full-restore confirmation.

## Data semantics

- Include IDs/times, alternatives/read positions, memory histories/journals/checkpoints, RAG, moments/tasks, cards/models/settings, editor/Studio drafts/undo/guidance, design conversations, generation recipes, tag DB and preset state.
- Preserve complete .cbsave and legacy inline archives, intentionally omitted-image tokens, and historical missing associations.
- Map only resource fields/legacy map keys to chatbar-backup-file tokens then destination paths. Preserve conversation/prompt text. Copy external files/content URIs into private media; keep HTTP URLs.
- API secrets/community session included by default; plaintext export warns. Destination expiration follows normal login handling. OS authorizations are rechecked; momentsAutoStartConfirmed resets.
- Exclude derived dictionary/index/translation caches, diagnostics, update APKs, partial downloads, hidden staging, and live process state.
- Forward/rollback intent is durable before renames; per-root rollback progress is restartable. Keep original preference ciphertext until commit. Missing rollback assets block startup.

## Regression map

JVM domain/backup/: path/text preservation, encrypted/plain round trips, integrity/truncation/duplicates/cancel/space/UTF-8, admission, seals and injected transaction interruptions.
Android domain/backup/: synthetic legacy/v8 packages and isolated credential preferences/key aliases.
BackupLargeStreamTest opt-in encrypted 1 GiB regression uses CHATBAR_BACKUP_LARGE_REGRESSION=1 with constant buffers.
Manual acceptance: doc/app_backup_manual_acceptance.md.
