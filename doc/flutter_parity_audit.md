# FlutterReborn Parity Audit

Last updated: 2026-06-23

Purpose: decision aid for Kotlin-to-Flutter parity. Keep this as current coverage and gaps, not a chronological log.

Legend:

- Done: current evidence proves parity for this scope.
- Partial: useful subset exists, but gaps remain.
- Unknown: not audited enough.
- Risk: likely mismatch or weak evidence.

## Current Verdict

FlutterReborn is not complete. Domain/data coverage is strong and early P0 crashes/UX failures are largely fixed, but Kotlin UI/UX parity remains partial. Manage and Chat are the active product-risk surfaces.

## Parity Matrix

| Area | Status | Evidence | Remaining Gap |
| --- | --- | --- | --- |
| Build/toolchain | Partial | debug APK build passes | release + all desktop/iOS targets unverified |
| Android install/launch | Partial | current debug APK installed/launched on `emulator-5554`; Home and Chat screenshots captured; fresh launch log had no fatal AndroidRuntime/Flutter lines | broader manual navigation/crash pass still needed |
| Data compatibility | Partial/strong | entity/controller/domain tests pass | broad real user data migration not exhaustive |
| Character media edit | Partial/strong | user manually confirmed avatar/background/appearance persistence | missing-file/conflict edge cases |
| Character import/export | Partial | user confirmed import/export create/overwrite works | large packages/performance/conflict UX |
| System back | Partial | app-shell fix, Home double-back exit path, Home/Manage/Chat back-contract tests, prior Chat Android pop-route integration smoke | physical Android key/gesture still manual |
| Icon system | Partial | lucide icons used across common controls | remaining screens need icon/spacing polish |
| UI kit | Partial | semantic buttons/icon buttons/confirm/loading/field/divider/select/tabs exist | sheets/dialogs/forms/switch/slider incomplete |
| Home | Partial/strong | component split and widget coverage for sessions/actions/start dialog | exact device visual behavior unverified |
| Manage | Partial | media fixes, tabs, Kotlin-like rows/action panels, scoped presets, FULL_CUSTOM-only model tab, no duplicate Settings tab, compact character-people rows, standalone character edit rendering, edit-mode switch confirmation, character dirty cancel/back confirmation, format create/edit dirty back confirmation, character document/RAG header actions/progress/clear confirmation/pasted batch import/native file picker/folder picker, model/embedding forms, model package import/export, independent retrieval-model persistence, embedding list/default/package flow; full widget test/analyze pass; user confirmed folder picker imports direct-child `.txt/.md/.json` files | real indexing device UX, advanced model form parity, real-provider retrieval behavior, pagination/animation, device density |
| Chat composer | Partial | thumbnail strip, full-width input, keyboard/nav inset behavior, fullscreen layout, system-bar lifecycle tests | device keyboard/nav/fullscreen manual check |
| Chat message list | Partial | action/edit/delete/streaming/alternatives, compact bubbles, status blocks, markdown/html/list/table coverage, background fallback/candidate tests, bundled preset media repair test, visible background-load failure fallback, Kotlin-like floating jump-to-bottom control, compact provider HTTP error bubble test, streaming viewport-anchor test, save-slot restore explicit bottom jump, no production bottom jump except down-arrow/restore source scan | provider/device streaming feel, exact Markwon parity, manual background visibility/path validation |
| Chat overlays | Partial | settings/save slots/debug/action/edit/preview overlays have widget and prior Android pop-route evidence; save-slot rows now have narrow-width wrapping action coverage; session settings unavailable-model warning has widget coverage; chat settings removed Session title/pin controls, autosaves fields, and exposes Defaults reset | exact spacing/typography, autosave/defaults device check, physical back/gesture |
| Image preview/save | Partial | fullscreen viewer, image-tap preview, per-image delete affordance removed in favor of normal message actions/edit removal, save confirmation/status, Android gallery channel prior smoke | manual pinch/pan/native Toast verification |
| Settings | Partial | bottom Settings is the single global settings entry; model-mode gating is tested; customAPI API key save and test-connection UI are tested; default format card select/clear is tested; fullCustom routes to Manage model tab; AppShell reload runtime error fixed; settings reload keeps old snapshot and owns a persistent scroll controller | exact Kotlin layout and advanced state flows; manual no-flash/live-provider retest needed |
| Tutorial | Partial | page flow exists; top back/system back contract now matches Kotlin previous-page-then-exit behavior with widget coverage | exact copy, visual density, and first-launch/manual device behavior unverified |
| Accessibility | Risk | some semantics exist | TalkBack roles/focus order not audited |
| Performance | Risk | large JSON risk reduced earlier | large sessions/images/debug logs need device test |
| Cross-platform | Unknown | Flutter project builds Android debug | iOS/macOS/Windows/Linux not verified |

## Screen Inventory

| Product Surface | Kotlin Reference | Flutter Reference | Current Risk | Next Parity Work |
| --- | --- | --- | --- | --- |
| App shell / bottom nav | `BottomNavBar.kt`, routes | `app_shell.dart`, `shell/app_frame.dart`, `shell/app_tab.dart` | Partial | Chat is a hidden route, hides bottom nav, and hides the global mobile top bar on Chat screen; broader device navigation/back check |
| Home | `home/HomeScreen.kt` | `home/home_view.dart`, `home_session_components.dart` | Partial/strong | device visual check |
| Start session dialog | `home/HomeScreen.kt` | `home/home_view.dart` | Partial | device create-session check |
| Manage tabs/list | `manage/ManageScreen.kt` | `manage_view.dart`, `manage_components.dart`, `model_editor.dart`, `character_editor.dart`, `format_card_views.dart` | Partial | advanced editor/model flow parity, device density |
| Character editor | `character/CharacterEditScreen.kt` | `character_editor.dart`, `character_*_editor.dart` | Partial/strong media, standalone editor rendering, compact people rows, edit-mode confirmation, dirty cancel/back confirmation, document header actions/progress/clear confirmation, pasted batch document import, native file/folder picker | real indexing/device feedback |
| Format editor | `format/FormatCardEditScreen.kt` | `format_card_views.dart` | Partial | exact field polish/package conflict UX |
| Model editor | `model/ModelEditScreen.kt` | `model_editor.dart`, model settings cards | Partial | provider/template/API key/test connection/advanced modes |
| Global settings | `manage/ManageScreen.kt` settings sections | `settings_view.dart`, `settings_panels.dart` | Partial | reachable only from bottom Settings; SiliconFlow save/test connection and default format card exist; exact Kotlin layout/defaults/NovelAI/RAG/theme flows still need audit |
| Chat main | `chat/ChatScreen.kt` | `chat_view.dart`, `chat_session_detail.dart`, `chat_message_area.dart`, `chat_header.dart` | Partial | route-local header/back exists; device streaming, keyboard, overlays, manual jump-to-bottom and visual density checks |
| Chat composer | `chat/ChatScreen.kt` | `chat_composer.dart`, `chat_composer_section.dart`, `chat_fullscreen_composer.dart` | Partial | physical keyboard/nav bars and image strip |
| Chat bubbles/list | `components/ChatBubble.kt` | `message_bubble.dart`, `message_bottom_bar.dart`, `chat_message_list.dart` | Partial | exact Markwon spans and alternative controls |
| Chat panels | `ChatSettingsDialog.kt`, dialogs | `session_settings_panel.dart`, `save_slots_panel.dart`, `chat_panel_overlay.dart` | Partial | save-slot creation/item rows are responsive on narrow width; unavailable session model warning exists; remaining spacing/hierarchy/manual physical back checks |
| Image preview/save | `chat/ChatScreen.kt` | `message_image_preview.dart`, `local_message_image_*` | Partial | pinch/pan/save toast/back |
| Tutorial | `tutorial/TutorialScreen.kt` | `tutorial_view.dart` | Partial | exact copy/visual density/manual first-launch behavior |

## Active Slice: Manage Parity

Kotlin reference: `app/app/src/main/java/com/example/chatbar/ui/manage/ManageScreen.kt`

Current Flutter facts:

- Manage uses `CbTabs` for character, format, and conditional FULL_CUSTOM model sections, with transient state cleared on tab switch.
- Manage uses explicit `_ManageSection` routing, so hiding the model tab in non-FULL_CUSTOM mode does not redirect index 2 to model controls.
- Bottom Settings is the only global settings entry; `SettingsContent` is no longer rendered inside Manage.
- Settings model mode is gated: customAPI exposes SiliconFlow API key plus test connection, fullCustom routes to Manage model tab, and custom chat/embedding forms stay in Manage.
- Bottom Settings now exposes global default format-card selection and clear, matching the Kotlin setting that writes `defaultFormatCardId`.
- Structured character people in edit mode now show compact rows by default and expand only for editing, closer to Kotlin `CharacterRow` plus edit dialog flow.
- Character edit-mode switching now uses pending confirmation and clears inactive draft data only after confirm, matching Kotlin destructive switch semantics.
- Character edit cancel/back now compares a draft snapshot and requires explicit discard confirmation before leaving dirty edits; parent Manage back delegates into the active character editor.
- Format card create/edit dirty drafts now require discard confirmation on Cancel/back; `ManageView` owns `editingFormatId` so row edit state participates in page-level back handling.
- Character documents editor now keeps document lifecycle in `CharacterDocumentController`, renders Kotlin-like header actions/progress/document rows, collapses the new-document form by default, exposes clear-all with destructive confirmation, supports pasted multi-document batch import, and has a native document file picker channel.
- Character document folder picker uses Android `ACTION_OPEN_DOCUMENT_TREE` and imports supported direct children through the same owned-file/RAG-dirty controller path.
- Character create/edit now renders as a standalone editor view instead of inline among list rows.
- Character and format rows follow Kotlin row direction: tap edit, long-press/more action panel, primary action in row, two-step delete.
- Character duplicate and format/model package import/export use controller/domain services, not page-local ad hoc JSON.
- Character/format preset panels are scoped by `PresetType`.
- FULL_CUSTOM model tab includes custom chat rows, all embedding rows, current/default embedding selection, independent retrieval-model selection/clear, default clear/set, export, edit, delete.
- `AppSettings.defaultRetrievalModelId` is nullable/backward-compatible; older JSON without the field loads as null.
- Home, Chat, and Manage pass selected custom retrieval model into `EffectiveModelResolver`.
- Deleting a model clears `defaultModelId` and/or `defaultRetrievalModelId` when they point at that model.
- Model and embedding forms live in `model_editor.dart`; settings card composition lives in `settings_view.dart`; `manage_view.dart` remains state owner/orchestrator.
- `ModelConfigPackage` and `EmbeddingConfigPackage` own portable package schema; package import/export avoids local ids and centralizes conflict handling in controllers.

Current gaps:

- Character document/RAG still needs real indexing/device feedback and visual density validation.
- Model/settings advanced forms need parity beyond being reachable; customAPI connection test now exists but needs real provider proof.
- Device visual density for tabs/action panels/list rows is unverified.
- Real provider/device verification needed for selected retrieval model in live RAG.

## Active Slice: Chat Visual Parity

Kotlin reference: `app/app/src/main/java/com/example/chatbar/ui/chat/ChatScreen.kt`

Current Flutter facts:

- Chat tab is hidden from app navigation and Chat route hides bottom navigation on the Chat screen.
- Mobile Chat hides the global shell top bar and uses route-local `ChatHeader` for title/back/settings/debug actions, closer to Kotlin `CbTopBar`.
- Chat settings save-slot panel uses a full-screen overlay with tabs; save-slot creation stacks on narrow widths and save-slot row actions wrap below details during delete confirmation.
- Chat settings autosaves session-level prompt/model/background overrides with a short debounce, removed Session title/pin controls, and uses `Defaults` to clear overrides instead of an Apply action.
- Save-slot Restore closes the panel and explicitly invokes the jump-to-bottom path as success feedback.
- Chat settings shows a Kotlin-like notice when the session's saved model is unavailable under the current model configuration mode.
- Chat message list now uses a floating bottom-right circular chevron jump control, shown only when scrolled away from bottom, matching the Kotlin direction better than the previous top `Bottom` text button.
- Chat message list preserves long-press actions, edit callbacks, typing/streaming state, NovelAI action gating, and bottom-jump callback tests.
- Chat message refresh keeps the existing `ChatMessageList` mounted while the next `messagesFuture` is pending, preventing send/image-send/generation refreshes from resetting scroll to top.
- Streaming/message updates preserve the current viewport anchor; source scan shows only explicit down-arrow bottom jump calls `scrollToBottom`.
- Initial load, send, stream completion, regenerate, NovelAI image completion, and clear-history no longer call bottom jump; save-slot restore is the intentional exception for success feedback.
- Chat background keeps session and character path candidates; IO rendering uses the first readable path, so stale session preset media can fall back to repaired character preset media.
- Chat background still falls back from blank session override to character background instead of treating blank string as an override.
- Chat background image load failures show a visible fallback instead of silently rendering an empty layer.
- Installed bundled character presets now self-repair missing packaged avatar/background/appearance resources during preset initialization.
- Message image attachments preview by tapping the image tile; explicit `Preview image` text button is removed and per-image delete controls are removed so deletion follows normal message action/edit flows.
- Image turns route through a resolved `visionModelId` helper model when the selected chat model is not itself multimodal, preserving the selected text model request body for text-only turns.
- NovelAI image-generation phases render visible status text immediately after tapping the compact generation icon.
- NovelAI prompt-design (`Designing`) phase streams partial prompt draft text through the chat completion stream sink before the full prompt JSON is parsed.
- Preset SiliconFlow chat requests preserve the original designed body: `enable_thinking`, `thinking_budget`, `max_tokens`, and `max_completion_tokens`.
- Chat completion requests normalize integer-valued custom params to JSON integers, matching Kotlin request-body behavior and avoiding provider rejection of values such as `1024.0`.
- Provider HTTP failures save compact assistant-visible errors while full URL/body details remain in debug logs.

Current gaps:

- Manual scroll position behavior on emulator/device needs user confirmation with a long real chat after preserving list mounting during refreshes and removing non-down-arrow bottom jumps except explicit save-slot restore feedback.
- Manual background visibility retest is needed after session/character candidate fallback and bundled preset-media repair.
- Manual SiliconFlow text send and image send retest is needed after preserving the designed preset request body, adding vision-helper routing, and preserving scroll during message refresh.
- Manual NovelAI image-generation tap needs confirmation that visible `Designing` draft text streams, then `Generating`/error status appears.
- Manual Chat settings autosave/defaults and save-slot restore bottom-jump feedback need user confirmation.
- Manual Chat header visual/back retest is needed on device: no duplicate global top bar, header back returns Home when no overlay, and overlay back still closes overlays first.
- Provider streaming scroll feel remains unverified.
- Chat header/composer/message density still needs screenshot/device tuning against Kotlin.

## High-Priority Requirements

1. No crashes: system back, import/export, large debug/log views.
2. Chat must feel Kotlin-like: composer, message list, overlays, fullscreen editor, image preview/save, scroll/keyboard/insets.
3. Manage must remain reliable: media pick/clear/save, import new/overwrite, defaults, packages.
4. Architecture must stay maintainable: no giant UI files, state owners separate from callback-only components, reusable UI kit.

## Active Slice: Tutorial Parity

Kotlin reference: `app/app/src/main/java/com/example/chatbar/ui/tutorial/TutorialScreen.kt`

Current Flutter facts:

- Tutorial has the same broad page-flow shape: top bar, skip, paged content, previous/next/start controls.
- `TutorialViewState.handleBack()` now mirrors Kotlin `BackHandler`: go to previous page when possible, otherwise exit.
- AppShell delegates Android/system back to Tutorial state before root fallback.
- Widget tests cover state back, top back, completion on first-page exit, and no completion when returning from later pages.

Current gaps:

- Exact copy differs from Kotlin and remains unaudited.
- Device first-launch tutorial routing/back behavior still needs manual confirmation.
- Visual density/spacing against Kotlin has not been screenshot-compared.

## Evidence Rules

Strong evidence:

- User manual result on installed APK.
- Emulator/device foreground activity + crash log.
- Focused widget/domain test covering exact callback/state.
- Full `flutter test`.
- `flutter analyze`.
- Debug APK build/install/launch.
- Source comparison to Kotlin reference.

Weak evidence:

- Visual intent.
- Compile only.
- Screenshot without interaction.
- Handoff claim without command output.

## Next Audit Work

1. Continue Manage parity:
   - real indexing/device feedback.
   - advanced Settings/model form parity and no-flash/live-provider manual retest.
2. Keep Chat manual/device verification explicit:
   - physical Android back/gesture.
   - provider streaming scroll behavior.
   - full-screen settings/save-slot hierarchy.
   - remaining Markwon exact-span parity.
3. Keep Home device visual check explicit; widget tests alone do not prove full Home parity.
4. Tutorial remaining work:
   - manual first-launch/back/skip/start pass.
   - compare copy and spacing with Kotlin once higher-risk Chat/Manage gaps settle.
5. Keep this file current by replacing rows/status, not appending logs.
