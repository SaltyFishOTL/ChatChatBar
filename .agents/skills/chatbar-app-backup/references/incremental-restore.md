# Prepared .cbbackup restore

Source paths are relative to app/app/src/main/java/com/example/chatbar/domain/backup/.

## Admission and transaction

- AppBackupService.previewRestore acquires LocalDataMaintenance after flushing Studio drafts; builds and validates a complete private prepared/files tree, saves preferences and seals bytes. Keep admission until confirm, mode change or dismissal so preview cannot become stale. Never modify live records during preview.
- inspectImport seals incoming. AppBackupSummary.requiresRestorePreview returns true for scoped replacement and both incremental modes: require a matching preview/lease and schedule prepared. Only legacy full archives with no scope schedule incoming directly. BackupRestoreTransaction.stageDirectory is persisted before switching registered roots; boot retains existing rollback/commit rules.
- Incremental preparation needs room for local plus imported files and validation/staging overhead. Fixed-size streaming copies preserve original bytes; cancellation removes staging, never local data. No model calls.

## Matching

- Scoped REPLACE_ALL matches cards/sessions/dependencies by stable ID only, retaining unselected local identities. Selected whole collections replace their local contents, including explicitly selected empty groups; dependencies referenced by retained local rows and owners of omitted images remain with a visible retention notice. Preview includes removed counts. Unselected dependency collections keep existing config contents, adding only absent required IDs.
- UPDATE_EXISTING: stable ID first, indexed per entity namespace. Cards/models/format cards/world books then use unique trim/NFKC/Locale.ROOT name. Session name lookup is scoped to its mapped character card; different cards never match by session name.
- An imported and local name must each be unambiguous; each local target can be claimed only once. Otherwise create an independent copy and disclose the ambiguity. Name matches with differing IDs are also disclosed. Warnings are capped; counts remain complete.
- Moments/history/tasks with no ID match first compare SHA-256 of streamed JSON metadata (formatting whitespace, root id/updatedAt and machine-dependent media paths excluded). Only candidate buckets then compare full fingerprints with original media SHA-256; cache file hashes within the operation. Preserve text, timestamps, recipe and other fields. JSON property reordering can produce an extra safe addition; never use partial hashes or perceptual image hashes to overwrite.
- ADD_COPIES assigns fresh global IDs to every imported root. Existing ID/name/content matches count as copies; absent roots count as additions. Named duplicates receive a unique 副本 suffix. Retain every local record.

## Ownership and overlay

| Owner | Update behavior |
| --- | --- |
| Matched session | Replace message/order/draft/read-position/memory-history/RAG families as one unit; preserve unmatched local sessions |
| Voice/SaveSlots | Replace only when that session choice includes the family; an explicitly included empty family clears old rows |
| Matched card | Replace card and embedded characters/documents; replace card/document RAG, retain unrelated local sessions and historical posts |
| Studio settings | Replace draft/undo/guidance/design families only when studioSettings is selected; imported history is matched separately |
| Global settings | Overlay only BackupSettingOptions fields selected in scope, including defaults if a selected singleton/field is absent; normalize each side's legacy search semantics independently before overlay; related read-seen markers follow imported owners, reset momentsAutoStartConfirmed only for imported Moment content/preferences |
| Preferences/tag catalog | Selected families replace; unselected families stay local. retainedPreferences skips XML restore without reading local secrets |
| Presets/cleanup | Union maximum seenVersions and model migration version; remove local pending deletions targeting restored owners; do not import old deletion jobs |

Scope.sessionDefaultChoice plus sparse sessionChoices governs voice/archive updates. Early v2/v1 without these fields conservatively infers inclusion from present family records; absent families are retained because omission versus empty selection is unknowable. Legacy v1 with no scope treats settings/Studio options as selected.

For scoped replacement, BackupRetainedMedia streams the old root images/generatedImageMetadata (chat), imagePath/generatedImageMetadata (Moments), or images (Studio history with actual originals) into private field files, then injects them without remapping local paths. Metadata-only local Studio rows still update incoming seeds/image metadata; there are no original bytes to retain. Unselected media on local-only messages/posts/history keeps its owner. New incoming records have no local media to borrow. Early v2 mixed session image flags infer inclusion from present incoming images and conservatively retain empty counterparts. These full-replacement safeguards do not change incremental modes' existing image projection policy.

## Identity, media and proofs

- BackupMergeIdentity maps declared entity-reference fields and typed map keys only; preserve chat/prompt text, extension data and remote Fish voice/model IDs. Source-turn, alternative-version and voice-anchor IDs stay unchanged within their new session/message namespaces.
- Remap embedded character/document/world-entry IDs, editor draft target/key, RAG source/message links, memory nodes/revisions/commits/journals/deletion references and staleSourcesByNodeId/timedWorldInfo keys. Reserve archived-only memory IDs against untouched local nodes.
- BackupMergeMemory spools each node and recomputes parent sourceHash/coverageHash after child-ID rebinding; preserve leaf source-turn coverage/fingerprints and historical revision links. Parent coverage-unit text that encodes child proofs follows the new child proof; natural-language text remains unchanged.
- Chat-message filenames use mapped session+message IDs. Voice anchor filenames use mapped message ID plus unchanged version key. Generated media stays beneath owned images/generated or audio/generated roots, with a per-import migrated_backup namespace; retained local files keep their original paths.
- Each selected SaveSlot gets its own mapped ID/package name. Stream-rewrite legacy JSON or v8 manifest/messages/RAG/voice identities and proofs; copy original ZIP media bytes without decoding. Recompute packageRef.byteLength. Ordinary SaveSlot import/load semantics remain unchanged.
- Vibe immutable payload paths may deduplicate only after complete SHA-256 equality. Preserve local history fold preferences.
