---
name: chatbar-shadcn-compose
description: Build or migrate ChatBar Android UI using shadcn/ui design principles implemented with Jetpack Compose Foundation primitives. Use for ChatBar themes, UI kit components, responsive or compact-height layouts, screens, forms, dialogs, sheets, tabs, list items, buttons, inputs, visual consistency, or removal of Material 3 UI dependencies.
---

# ChatBar shadcn Compose

Implement shadcn's open-code, semantic-token, variant-driven component model in native Compose. Preserve Android behavior and accessibility; do not imitate web markup or depend on Material 3 visuals.

## Required Reading

Read [references/shadcn-compose.md](references/shadcn-compose.md) before creating or changing UI kit components. Read relevant existing screen and ViewModel before migration.

## ChatBar Screen Quick Path

1. If feature location is unclear, use `chatbar-feature-map` first.
2. Read target `*Screen.kt` and paired `*ViewModel.kt` before broad search.
3. For cross-layer features, read relevant domain service/repository after screen state flow is clear.
4. Use focused `rg -n "term" path` searches. On Windows, avoid regex alternation, pipes, and command chaining.

## Workflow

1. Inventory screen behavior, states, controls, callbacks, insets, keyboard handling, scrolling, and accessibility semantics.
2. Map every control to an existing ChatBar UI Kit primitive. Add missing primitive before editing page code.
3. Define semantic tokens and component variants centrally. Never embed page-specific colors or duplicate component styling.
4. Build primitives from Compose Foundation/UI/runtime. Material icons may remain temporarily; Material 3 components, theme, defaults, and color scheme may not enter new code.
5. Compose screen from explicit anatomy: header, content groups, items/fields, actions, overlays. Keep state ownership unchanged.
6. Budget compact-height space before styling. Bound fixed chrome, reserve flexible height for primary detail/editor content, and move conditional maintenance/status clusters into a bounded scrollable dialog or sheet.
7. Preserve enabled, pressed, focused, selected, invalid, loading, destructive, and disabled states.
8. Compile after each migrated component or screen. Search for remaining Material 3 imports after each migration batch.
9. Remove Material 3 dependency only after imports and fully qualified usages reach zero.

## Fullscreen and IME Insets

- Settings use `SettingsBrowser.kt`: nine global categories (including 数据迁移), six session parameter categories, metadata-only search, explicit per-category scroll state, and bounded `SettingsDetails` editors. Keep stable entry IDs and `searchItems` for fields inside a detail group. `SettingsSwitch` provides a labeled 48dp target; credentials use non-saveable secure input state. Drafts remain outside category composition and rebase only untouched fields with `rememberSettingDraft`.

- Every screen must keep actionable content above gesture/navigation controls and IME. Apply navigation-bar and IME insets to scrolling or bottom-action region, including Android three-button navigation.
- `SettingsSwitch` owns input and accessibility on its 48dp wrapper. Its `CbSwitch` uses a null callback for visual-only rendering without a nested pointer handler; `enabled = false` alone still installs a handler and can block the wrapper's taps.
- `CbDialog` owns safe-drawing/IME padding, available-window height, and fixed title/action regions. Do not duplicate those insets in ordinary dialog content. Large dialog bodies must still provide their own bounded `LazyColumn` or `verticalScroll` container so the flexible body can scroll on 360×640dp screens.
- Fullscreen custom `Dialog` pages do not inherit `CbDialog` behavior; keep status-bar handling in `CbTopBar` and apply navigation-bar padding to the full-page content.
- Every multi-line or otherwise large text input must expose standard `CbField(onFullscreenEdit = ...)` entry and reuse `FullscreenTextEditor`; keep screen state source of truth.
- `CbInput` uses the state-based `TextFieldState` pipeline. Compatibility overloads must preserve text, selection, and IME composition; constrained fields filter through `InputTransformation`, not lossy `onValueChange` rewriting.
- `CbInput` and fullscreen overlays retain the `onTextLayout` result provider and read it in composition so snapshot changes refresh long-text annotations; do not freeze its result inside the callback.
- Numeric fields use `NumberInput.kt`: `CbNumberInput` for string-owned forms with existing submit validation, `CbNumberValueInput` for numeric models. Both allow empty intermediate drafts and numeric keyboards; value-backed fields publish only validated numbers, preserve numeric echoes without rewriting decimal/leading-zero text, and restore the last valid model value on blur if unfinished. Set decimal/signed and range validation per field; key repeated rows by entity identity.
- Compact fixed-width actions can opt into `CbButton(autoSizeText = true)`: primary/supporting text each stays on one line, shrinks to a 10sp floor, then ellipsizes. Keep full explanations in the associated options surface.
- `FullscreenTextEditor` owns an internal transient text draft. Dismiss/× discards it; confirm/√ commits once. Custom confirm callbacks receive the final `String` or `TextFieldValue`; use `canConfirm` when validity depends on the transient draft.
- Fullscreen entry/exit must preserve exact `TextFieldValue` selection and cursor, request focus, show the IME for immediate typing, keep status/navigation bars available, and apply status/navigation/IME insets without hiding phone controls.
- `FullscreenTextEditor` draws into its caller's Compose tree. A fullscreen settings `Dialog` should overlay it in the same root `Box`, keeping underlying tool drafts alive and disabling the covered browser's back handler. When an editor is instead hosted outside an ordinary `CbDialog`, stop composing that dialog while the editor is visible so its separate window does not cover the editor.
- Chat keeps its composer above IME and uses explicit bottom anchoring so an already-bottomed timeline rises with the keyboard; historical reading positions must not be forced to the bottom. Do not attach `imeNestedScroll` to the timeline because reaching its end must never open the keyboard.

## API Rules

- Character editor resource bindings use `CharacterResourcePicker` in `CharacterEditScreen.kt`: compact summary buttons open searchable, height-bounded lazy lists; format cards are single-select and world books multi-select. `CharacterEditViewModel` subscribes to both repository flows for live create/edit/delete updates. Missing bindings remain visible for explicit removal rather than silently rewriting the draft.

- `SegmentActionScope` supplies redundant scope cues in the floating menu: 本段 uses an Article icon and muted surface; 整条 uses Layers, accent surface and primary foreground. Delete stays destructive in both rows; scope styling must not alter callbacks or target IDs.
- `RoleplayMarkdownText` in `ChatBubble.kt` uses a separate Compose `Box.clearAndSetSemantics` to expose rendered Markdown text without color markers; native `AndroidView` is visual-only and excluded from accessibility. Keep the semantics owner separate from the interop layout node so clickable bubble parents can merge its text. Generic message `contentDescription` labels must not mask body text. `RoleplayStatusPanel` has no `SelectionContainer`: its parent owns tap-to-expand and long-press menus; collapsed body text is absent.
- `ChatScreen.kt` segment actions use `CbAnchoredActionPopup`, anchored by `ChatBubbleSegmentAction.anchorBounds` from the text surface's `boundsInWindow()`. It has no dim/title/footer, flips above/below the fragment, clamps to safe bounds, and closes on outside tap/back. Whole-message actions retain `CbDialog`. `SegmentActionRow` renders regeneration/format repair, 本段, 整条 with dividers, borderless icon/caption buttons, 48dp minimum targets, 4dp gaps and destructive foreground for deletion. Narrow rows scroll horizontally. `CbButton(icon = ...)` uses `text` for accessibility and `supportingText` for a visible caption; `contentColor` overrides foreground only.

- Root-tab swipes in `Navigation.kt` must pass a lambda capturing the current `rootRoutes` to `swipeToAdjacentTab`. A local callable reference can compare equal across recompositions and leave its `rememberUpdatedState` callback holding the startup route list before Moments/community settings load.

- Prefer `variant` and `size` enums over Boolean style flags.
- Prefer slot-based composition: `leading`, `trailing`, `content`, `actions`.
- Keep primitives small and open code. Pages may compose primitives, not restyle internals.
- Use paired semantic colors: surface/foreground, primary/onPrimary, muted/mutedForeground, destructive/onDestructive.
- Use one radius scale, spacing scale, control-height scale, and typography scale.
- Use visible focus treatment for keyboard/D-pad navigation.
- Use Android minimum touch target of 48dp even when visual control is smaller.
- Icon-only fixed actions require an unambiguous standard icon, `contentDescription`, enabled/loading semantics, and a 48dp touch target. Keep text when icon meaning is unclear.
- Use dialogs for focused decisions; sheets for mobile action lists and dense settings.
- Use `Field` anatomy for forms: label, control, description, error.

## Verification

For UI-visible changes, run from `app/`:

```powershell
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble
```

If an Android device is connected, use `chatbar-emulator-test` data-preserving install flow.

## Migration Gate

A screen is migrated only when:

- No `androidx.compose.material3` import or qualified use remains.
- Existing user actions and state transitions remain available.
- Primary detail/editor content retains usable height when every conditional warning, error, progress, or maintenance state is present.
- UI uses semantic tokens and UI Kit components.
- Loading, empty, error, disabled, and destructive states remain represented.
- Kotlin compile passes.

Full migration completes only when Material 3 dependency is removed and CI-equivalent verification passes.
