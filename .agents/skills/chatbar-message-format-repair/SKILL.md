---
name: chatbar-message-format-repair
description: Maintain ChatBar message AI format repair across persisted settings and notices, auxiliary model resolution, repair prompts, streaming overlays, candidate replacement, undo behavior, automatic/manual orchestration, UI, and derived-data synchronization. Use when changing automatic format checks, AI repair, restore-original behavior, repair progress/errors, format-repair model selection, or related tests.
---

# ChatBar Message Format Repair

Treat repair as a persisted reversible message mutation, not a transient text completion.

## First Read

- Persisted fields: data/local/entity/AppSettings.kt and ChatMessage.kt
- Model selection: domain/model/EffectiveModelResolver.kt
- Prompt builders: domain/prompt/PromptTemplates.kt
- Streaming and pure rules: domain/chat/MessageFormatRepairService.kt, MessageFormatRepairPolicy.kt, and StreamingChatService.kt
- Orchestration: ui/chat/ChatViewModel.kt
- Message and settings UI: ui/chat/ChatScreen.kt and ui/manage/GlobalSettingsScreen.kt (回复与记忆)
- Help entry: ui/tutorial/TutorialScreen.kt
- Tests: MessageFormatRepairPolicyTest.kt, MessageFormatRepairServiceTest.kt, MessageFormatRepairPromptTest.kt, StreamingChatServiceThinkingTest.kt, and ChatMessageSerializationTest.kt

Use chatbar-model-request-runtime for provider/request failures and chatbar-long-term-memory when a repaired historical message changes source hashes.

## Model and Prompt Rules

- MessageFormatRepairService declares FORMAT_REPAIR/REPAIR. Shared transport adds neutral confirmation before the existing repair input; the exact CHATBAR_FORMAT_OK protocol remains unchanged. Refusal/error output cannot become an applicable replacement.

- Let an unset format-repair model follow the default chat model.
- Treat an explicitly selected missing model ID as invalid configuration; do not silently replace it.
- Offer all configured auxiliary text models, including models hidden from the normal chat selector.
- Preserve the selected repair model thinking/effort parameters; reasoning deltas never enter the replacement text.
- Send only applicable format rules and the full assistant message. Do not inherit ordinary chat context.
- Treat an exact trimmed `[[CHATBAR_FORMAT_OK]]` response as unchanged and keep any possible streamed marker prefix out of the message preview.
- Skip automatic repair when no format rule exists. Make manual unavailability visible.

## Mutation Invariants

- Repair the owning full assistant message even when invoked from one segmented bubble.
- Persist normalized explicit alternative IDs before attaching a repair notice, preserving narrativeMode/alternativeNarrativeModes exactly; notices keep that stable ID across backup copies. BackupMessageVersionIdentity resolves legacy implicit IDs before message-ID remapping without changing notice.versionId.
- Candidate switching uses repository expected-snapshot writes and reloads persisted state. Terminal model success, unchanged, stop and failure results also check the expected message and its timeline revision under the message write lock. Legacy and streaming save-slot replacement invalidate earlier repair requests even when a restored row matches byte for byte.
- Replace only the current displayed candidate. Preserve other alternatives, images, reasoning, timestamps/order identity, and source-turn identity unless the owning operation explicitly changes them.
- Use Unicode code-point boundaries for progressive overlay so surrogate-pair emoji are not split.
- Keep streamed overlay in UI memory and bind it to the selected message version and source text that started repair. Persist only terminal content or an explicit stopped result.
- Experimental repair streams display the first tool call while receiving the response. An explicit Stop can include additional validated contiguous `game_content` calls already received, using the decoder's same prefix reader and part boundaries as main chat. A collected terminal Done owns the completed repair or unchanged result.
- Use validated tool separators to remove an edge call containing the exact dispatched repair system block, including any terminal CR/LF owned by that call. Preserve line breaks owned by an adjacent prose call. In ordinary text or within one call, keep a trailing block after a single line break when no separate boundary proves it is an echo. Keep quoted blocks and match authored evidence at the same granularity as the dispatch candidate: an authored short sentence can preserve that sentence without reserving the full block.
- Record the exact assembled repair user payload when at least two generated section boundaries are proved. A matching payload directly before a separate story paragraph or alone in a validated tool call can be removed. Keep literal quotations, individual labels protected by source content, and altered payloads. An unfinished system rule is eligible in Preview and Stop only after the exact introduction, header and first complete rule, with the fragment matching the very next rule and no authored witness.
- A reply consisting solely of the exact witnessed multi-section user payload leaves no repair body. Stop keeps the original and its existing notice unchanged when the cleaned overlay makes no display change; Done follows the empty-result error path. An isolated dispatched repair label at a validated tool edge removes only the tool join and the label. Match whole instructions before cutting isolated labels.
- A protected standalone close in the original or current user text can coexist with cleanup of a fresh outer pair from a prohibited format-card example. Require a matched outer pair and the same source token classified as literal by the normal wrapper visibility rules within its body. Keep authored source pairs and opener protection, and keep source tokens out of orphan cleanup.
- When the prohibited format-card example independently identifies a removable outer frame, validated tool joins preserve the body-owned whitespace at each proved frame edge even without a stored history projection.
- Check validated tool calls separately for eligible orphan closes in the prohibited family. Use the joined context so captions, fences, quotations and otherwise authored markers retain their meaning across calls.
- When the source close and the retained reply close are each independently classified as a single literal occurrence, their presence does not reserve a separate eligible bare terminal close. An absent witness or repeated source/reply occurrence keeps the conservative behavior.
- Whenever repair changes content, persist an APPLIED or recoverable terminal notice containing the exact pre-repair text.
- Show a notice only for the matching stable version and expected display text (originalContent when restored, targetContent otherwise). A legacy notice without a version ID applies only if its checkpoint matches one version. Drop ambiguous notices when versions change.
- Restore only the current candidate from notice.originalContent; persist restored=true while retaining the exact repaired targetContent and versionId. Redo switches to that target locally without a model request. Another version or a manual edit must not accept a stale recovery pair.
- A successful marker/original-text no-change clears the active version’s failure without discarding a usable recovery pair. Stopping before the display text changes preserves the existing notices. A changed terminal result replaces the active version’s pair and retains applicable sibling pairs by stable version ID. FormatRepairRetryRuntimeTest exercises loopback failures, unchanged results, Stop before output and local redo against a cold storage read.
- Keep legacy notice enum values readable. Do not revive the removed length-anomaly gate without a new product decision.
- Editing, regeneration, candidate switching, or another mutation must clear or hide stale notices according to target-content applicability.
- Process death during an unfinished stream leaves the pre-repair persisted message intact.

## Orchestration Invariants

- Automatic repair runs before downstream work consumes the final assistant text.
- After a terminal automatic result, let long-term memory, Moments, and RAG read the retained final content.
- Manual repair or restore of historical content must refresh RAG and let long-term-memory source-mutation detection mark affected summaries stale.
- Keep one repair job's cancellation, progress, error, and target message identity isolated from unrelated generation work.
- Preserve concrete upstream failure text; retry the primary request instead of presenting a fallback as success.

## Workflow

1. Classify change as persistence, pure replacement policy, model request, orchestration, or UI.
2. Write state transitions for unchanged, applied, stopped, failed, restored, edited, and candidate-switched paths.
3. Patch pure policy and serialization first.
4. Update ViewModel orchestration and UI against the same transition table.
5. Verify downstream RAG and long-term-memory effects for historical mutations.
6. Add focused tests before broad UI verification.

## Regression Matrix

- Old JSON without repair settings or notice.
- APPLIED, ERROR, STOPPED, and legacy LENGTH_ANOMALY round-trip.
- Emoji and repaired prefix shorter/equal/longer than original.
- Message with and without alternatives; non-current candidates unchanged.
- Existing recovery notice followed by a no-change check.
- Explicit stale model ID versus unset model.
- Automatic disabled with manual repair still available.
- Failure, user stop, process death, restore, edit, and candidate switch.

## Stop Conditions

- Do not overwrite text without a durable recovery path.
- Do not persist partial stream output accidentally.
- Do not treat character-card rewrite behavior as message format repair behavior.

- failedAttemptNotice attaches errorMessage to the current applicable original/target/version/restored recovery pair instead of overwriting it. Without a usable pair it creates a version-bound ERROR. MessageFormatRepairNoticeRow shows request errors independently of snapshot kind/restored, with the undo/redo button still available. Successful local undo/redo clears attempt error metadata. FormatRepairRetryRuntimeTest covers actual undo → HTTP failure → local redo with exactly one model request; ReplyMediaVersionControlsTest covers simultaneous error and recovery controls.
