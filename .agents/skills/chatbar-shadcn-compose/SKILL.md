---
name: chatbar-shadcn-compose
description: Build or migrate ChatBar Android UI using shadcn/ui design principles implemented with Jetpack Compose Foundation primitives. Use for ChatBar themes, UI kit components, screens, forms, dialogs, sheets, tabs, list items, buttons, inputs, visual consistency, or removal of Material 3 UI dependencies.
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
6. Preserve enabled, pressed, focused, selected, invalid, loading, destructive, and disabled states.
7. Compile after each migrated component or screen. Search for remaining Material 3 imports after each migration batch.
8. Remove Material 3 dependency only after imports and fully qualified usages reach zero.

## Fullscreen and IME Insets

- Every screen must keep actionable content above gesture/navigation controls and IME. Apply navigation-bar and IME insets to scrolling or bottom-action region, including Android three-button navigation.
- Every multi-line or otherwise large text input must expose standard `CbField(onFullscreenEdit = ...)` entry and reuse `FullscreenTextEditor`; keep screen state source of truth.
- `FullscreenTextEditor` owns an internal transient text draft. Dismiss/× discards it; confirm/√ commits once. Custom confirm callbacks receive the final `String` or `TextFieldValue`; use `canConfirm` when validity depends on the transient draft.
- `FullscreenTextEditor` is activity-hosted. When launching it from `CbDialog`, stop composing the dialog while the editor is visible, then restore it on close; the dialog's separate window otherwise covers the editor.

## API Rules

- Prefer `variant` and `size` enums over Boolean style flags.
- Prefer slot-based composition: `leading`, `trailing`, `content`, `actions`.
- Keep primitives small and open code. Pages may compose primitives, not restyle internals.
- Use paired semantic colors: surface/foreground, primary/onPrimary, muted/mutedForeground, destructive/onDestructive.
- Use one radius scale, spacing scale, control-height scale, and typography scale.
- Use visible focus treatment for keyboard/D-pad navigation.
- Use Android minimum touch target of 48dp even when visual control is smaller.
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
- UI uses semantic tokens and UI Kit components.
- Loading, empty, error, disabled, and destructive states remain represented.
- Kotlin compile passes.

Full migration completes only when Material 3 dependency is removed and CI-equivalent verification passes.
