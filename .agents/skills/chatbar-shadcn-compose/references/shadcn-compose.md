# shadcn/ui to Compose Foundation Reference

## Contents

1. Source Model
2. Visual Grammar
3. Semantic Tokens
4. Component API
5. Compose Mapping
6. Mobile Adaptation
7. ChatBar Component Set
8. Verification

## 1. Source Model

shadcn/ui is not an installed component library. It distributes editable component source. Core principles:

- Open code: application owns top-layer component code.
- Composition: components share predictable slot-based APIs.
- Beautiful defaults: defaults form one coherent system.
- Semantic theming: components consume role tokens, not literal colors.
- Accessible primitives: behavior comes from Radix/Base UI; styling remains local.
- Variant-driven APIs: components expose named variants and sizes.

Official sources:

- Introduction: https://ui.shadcn.com/docs
- Theming: https://ui.shadcn.com/docs/theming
- Components index: https://ui.shadcn.com/llms.txt
- Source repository: https://github.com/shadcn-ui/ui
- Button source: `apps/v4/registry/new-york-v4/ui/button.tsx`
- Input source: `apps/v4/registry/new-york-v4/ui/input.tsx`
- Card source: `apps/v4/registry/new-york-v4/ui/card.tsx`
- Dialog source: `apps/v4/registry/new-york-v4/ui/dialog.tsx`

## 2. Visual Grammar

Do not reduce shadcn to monochrome colors. Reproduce system relationships:

- Low-chroma surfaces; hierarchy comes from spacing, borders, type weight, and restrained fills.
- Thin borders separate cards, inputs, menus, and overlays.
- Shadows are small and local, mainly floating overlays. Persistent page cards need little or none.
- Radius is consistent and moderate. Default web radius is about 10px; Android base target is 10dp.
- Controls are compact. Default button is 36px high; Android visual height may be 36-40dp inside 48dp touch target.
- Typography is restrained: common control/body size 14sp; headings use weight and spacing before large size.
- Primary color is reserved for high-emphasis actions and active state.
- Accent is interactive state, not decorative branding.
- Destructive color appears only where consequence is destructive or invalid.
- Icon size defaults near 16dp; avoid oversized generic icons.

## 3. Semantic Tokens

Mirror shadcn token pairs with Compose `CompositionLocal` values:

| Token | Purpose |
|---|---|
| `background` / `foreground` | App shell and default content |
| `card` / `cardForeground` | Cards and grouped panels |
| `popover` / `popoverForeground` | Dialog, menu, popover, sheet |
| `primary` / `primaryForeground` | Main action and selected emphasis |
| `secondary` / `secondaryForeground` | Lower-emphasis filled action |
| `muted` / `mutedForeground` | Helper text and subdued surfaces |
| `accent` / `accentForeground` | Pressed, selected, hover/focus-equivalent surface |
| `destructive` / `destructiveForeground` | Delete, invalid, irreversible action |
| `border` | Cards, separators, overlays |
| `input` | Input boundary and field surface |
| `ring` | Focus indicator |

Use radius scale from one base:

- `sm = 6dp`
- `md = 8dp`
- `lg = 10dp`
- `xl = 14dp`
- `2xl = 18dp`

Use spacing scale: `4, 8, 12, 16, 20, 24, 32dp`.

## 4. Component API

### Button

Variants: `Default`, `Destructive`, `Outline`, `Secondary`, `Ghost`, `Link`.

Sizes: `Xs`, `Sm`, `Default`, `Lg`, `IconXs`, `IconSm`, `Icon`, `IconLg`.

Default behavior:

- Inline content with 8dp gap.
- 14sp medium text.
- Disabled opacity and blocked input.
- Pressed state adjusts fill/alpha.
- Focus state uses border plus ring.
- Icons default to 16dp unless explicitly sized.

### Field

Anatomy:

```text
FieldSet
  FieldLegend
  FieldDescription
  FieldGroup
    Field
      FieldLabel
      Input / TextArea / Select / Switch
      FieldDescription
      FieldError
```

Never encode label, helper text, and error into a single floating-label control. Keep anatomy explicit.

### Card

Expose `Card`, `CardHeader`, `CardTitle`, `CardDescription`, `CardAction`, `CardContent`, `CardFooter`. Avoid mandatory padding in base container when anatomy needs edge-to-edge list content.

### Dialog and Alert Dialog

Dialog anatomy: overlay, content, header, title, description, body, footer, optional close action. Alert dialog requires explicit cancel and action; destructive action uses destructive variant.

### Item

Generic list/menu row: optional media/leading slot, content with title and description, trailing actions. Support default, outline, and muted variants. Entire row may be interactive; nested actions need separate semantics.

## 5. Compose Mapping

| shadcn / web primitive | Compose Foundation/UI |
|---|---|
| CSS variables | immutable token data + `CompositionLocal` |
| CVA variants | enum + centralized style resolver |
| button | `Box`/`Row` + `combinedClickable` + interaction state |
| input | `BasicTextField` + decoration container |
| textarea | `BasicTextField`, multiline |
| card | clipped/background/border `Column` or `Box` |
| dialog | `androidx.compose.ui.window.Dialog` |
| sheet | `Dialog` or popup layer with anchored bottom panel and animation |
| tabs | row of custom selectable triggers + content slot |
| switch | custom track/thumb using toggleable semantics |
| slider | Foundation gesture/semantics implementation |
| separator | 1dp layout element; use alpha when physical pixel softness needed |
| focus-visible ring | `focusable`, `onFocusChanged`, border/ring drawing |
| disabled/invalid attributes | explicit state parameters + semantics |

Do not recreate browser-only hover behavior. Translate it to pressed, focused, selected, and disabled Android states.

## 6. Mobile Adaptation

- Preserve 48dp minimum touch targets even for shadcn's compact visual controls.
- Prefer bottom sheets over centered dialogs for action menus and dense mobile choices.
- Respect status/navigation bars and IME insets.
- Avoid desktop sidebars and data-table patterns on phone width.
- Keep critical actions reachable near bottom; avoid floating action buttons unless action is truly page-primary.
- Long forms use sections and progressive disclosure, not one card per field.
- Chat composer remains anchored and IME-aware; it is a product component, not a generic `Input`.

## 7. ChatBar Component Set

Build in dependency order:

1. `ChatBarTheme`, semantic color/type/radius/spacing tokens.
2. `Text`, `Icon`, `Surface`, `Separator`, focus ring.
3. `Button`, `IconButton`, `Badge`.
4. `Input`, `TextArea`, `Field` family.
5. `Card` family and `Item` family.
6. `Dialog`, `AlertDialog`, `Sheet`.
7. `Tabs`, `Switch`, `Checkbox`, `RadioGroup`, `Slider`, `Select`.
8. `TopBar`, `BottomNavigation`, `Scaffold`, `Empty`, `Spinner`, `Progress`.
9. Product components: avatar, chat bubble, composer, reasoning panel, model selector, settings row.

Use stable public names. Keep component implementation files independently readable. Do not put entire kit in one file.

## 8. Verification

For every migrated screen:

- Search file for `androidx.compose.material3` and `MaterialTheme`.
- Check enabled, disabled, pressed, focused, selected, invalid, loading, and destructive states where applicable.
- Check talkback labels for icon-only actions.
- Check 48dp touch targets.
- Check status/navigation/IME insets.
- Compile Kotlin.

For full migration:

- Search entire main source tree for Material 3 imports and qualified names.
- Remove Material 3 dependency.
- Run unit tests, Android test compile, and debug assembly.
- Install and launch on device; inspect main navigation, chat, forms, dialogs, and keyboard behavior.
