---
name: chatbar-shadcn-compose
description: Build or migrate ChatBar Android UI using shadcn/ui design principles implemented with Jetpack Compose Foundation primitives. Use for ChatBar themes, UI kit components, screens, forms, dialogs, sheets, tabs, list items, buttons, inputs, visual consistency, or removal of Material 3 UI dependencies.
---

# ChatBar shadcn Compose

Implement shadcn's open-code, semantic-token, variant-driven component model in native Compose. Preserve Android behavior and accessibility; do not imitate web markup or depend on Material 3 visuals.

## Required Reading

Read [references/shadcn-compose.md](references/shadcn-compose.md) before creating or changing UI kit components. Read relevant existing screen and ViewModel before migration.

## Workflow

1. Inventory screen behavior, states, controls, callbacks, insets, keyboard handling, scrolling, and accessibility semantics.
2. Map every control to an existing ChatBar UI Kit primitive. Add missing primitive before editing page code.
3. Define semantic tokens and component variants centrally. Never embed page-specific colors or duplicate component styling.
4. Build primitives from Compose Foundation/UI/runtime. Material icons may remain temporarily; Material 3 components, theme, defaults, and color scheme may not enter new code.
5. Compose screen from explicit anatomy: header, content groups, items/fields, actions, overlays. Keep state ownership unchanged.
6. Preserve enabled, pressed, focused, selected, invalid, loading, destructive, and disabled states.
7. Compile after each migrated component or screen. Search for remaining Material 3 imports after each migration batch.
8. Remove Material 3 dependency only after imports and fully qualified usages reach zero.

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

## Migration Gate

A screen is migrated only when:

- No `androidx.compose.material3` import or qualified use remains.
- Existing user actions and state transitions remain available.
- UI uses semantic tokens and UI Kit components.
- Loading, empty, error, disabled, and destructive states remain represented.
- Kotlin compile passes.

Full migration completes only when Material 3 dependency is removed and CI-equivalent verification passes.
