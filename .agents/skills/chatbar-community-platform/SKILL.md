---
name: chatbar-community-platform
description: Maintain and evolve ChatBar's community sharing platform. Use when changing the Community root tab, Supabase/Discord auth, community_items schema, Storage buckets, Edge Function submit-community-item, upload/download/import flows, community UI, or build-time Supabase configuration.
---

# ChatBar Community Platform

## Purpose

Use this skill for Community feature work. Preserve the MVP contract: public browsing/download, Discord login for upload, no manual review, format-correct packages publish, and uploads only from existing in-app entities.

## Current Architecture

- Root route: `CommunityRoute` in `app/app/src/main/java/com/example/chatbar/NavigationKeys.kt`.
- Navigation wiring: `app/app/src/main/java/com/example/chatbar/Navigation.kt`.
- Bottom tab order: `聊天 / 社区 / 管理` in `ui/components/BottomNavBar.kt`.
- UI: `app/app/src/main/java/com/example/chatbar/ui/community/CommunityScreen.kt`.
- State/import/upload logic: `ui/community/CommunityViewModel.kt`.
- Network/auth/storage service: `domain/community/CommunityService.kt`.
- Models and local package validation: `domain/community/CommunityModels.kt`.
- App wiring: `ChatBarApp.communityService`.
- OAuth deep link: `MainActivity.handleCommunityAuthCallback`, manifest scheme `chatbar://auth/callback`.
- Supabase SQL: `supabase/community_schema.sql`.
- Edge Function: `supabase/functions/submit-community-item/index.ts`.
- Package tests: `app/app/src/test/java/com/example/chatbar/domain/community/CommunityPackagePolicyTest.kt`.

## Product Contract

- Community is root-level, same level as Chat and Manage.
- Anonymous users can browse, search, filter, download, and import.
- Community list loads paged metadata only from `community_items`; do not prefetch package JSON in list rendering. App startup warms the first metadata page and its preview images. The Community list prefetches the next metadata page and preview images when the user nears the end of the visible list. Card details are loaded on item click with `CommunityService.readPackage`, which must not increment download count.
- Upload requires Discord OAuth through Supabase Auth.
- Upload source must be visible, non-deleted app-local `CharacterCard`, `FormatCard`, or `WorldBook`. Never add local file picker upload.
- Downloaded community `CharacterCard`s carry `communityItemId`, `communityItemUpdatedAt`, `communityItemSha256`, and `communityItemTitle`. Treat them as read-only: no edit, no local import overwrite, no upload. Copy/duplicate creates a local original with cleared community source metadata.
- Upload is a snapshot. Later local edits do not auto-sync. The owner can manually overwrite a community entry from a same-type, same-name local `CharacterCard`, `FormatCard`, or `WorldBook`.
- Logged-in users can use the Community `我的` view to manage their own uploaded character cards, format cards, and world books. Owner actions are overwrite-from-local and delete.
- Community-downloaded character cards auto-detect updates by comparing stored community `updated_at`/`sha256` against the current community item and offer one-click overwrite while preserving the local card ID/history.
- New community character uploads must not duplicate any existing community character title or source local name. Enforce this in both client service and Edge Function.
- Max package size is 20 MB.
- Initial release has no rating, comments, favorites, reports, manual review, or moderation queue.
- Download import must preserve existing conflict behavior: new copy / overwrite / cancel.

## Backend Contract

Supabase project currently used for testing:

- Project ref: `rrlxofrrgfnhjfyrqvry`
- Project URL: `https://rrlxofrrgfnhjfyrqvry.supabase.co`
- Android build uses the Supabase publishable/anon key.

`community_items` fields:

- `id`, `type`, `title`, `description`, `tags`, `author_user_id`, `author_name`
- `source_local_name`, `file_path`, `preview_path`, `sha256`, `size_bytes`
- `schema_version`, `download_count`, `created_at`, `updated_at`

Storage buckets:

- `community-packages`: public JSON package objects, max 20 MB.
- `community-previews`: public optional preview images, max 3 MB bucket limit. Android client uploads low-resolution JPEG previews only, currently 160px max side and about 80 KB max.

Edge Function:

- Name: `submit-community-item`.
- JWT verification stays enabled.
- `POST` without `item_id` inserts a community item.
- `POST` with `item_id` updates an owned community item after verifying auth, owner row, ownership path, file size, sha256, schema version, and package structure.
- For character inserts/updates, rejects duplicate normalized title/source-local-name conflicts with other community character items and cleans up rejected uploaded objects.
- `POST` with `{"action":"delete","item_id":"..."}` deletes an owned community item and cleans up Storage objects. `DELETE?id=<community_item_id>` is kept as a compatibility path only.
- If request/body/storage validation changes, update both `CommunityService.submitDraft` and Edge Function together.

## Build Configuration

Gradle reads Supabase values from Gradle properties or environment variables:

```powershell
-PCHATBAR_SUPABASE_URL=https://rrlxofrrgfnhjfyrqvry.supabase.co
-PCHATBAR_SUPABASE_ANON_KEY=<publishable-key>
```

Optional:

```powershell
-PCHATBAR_SUPABASE_REDIRECT_URI=chatbar://auth/callback
```

Discord `Client Secret` lives in Supabase Auth provider settings, not this repo.

## Workflows

### UI Changes

- Also use `chatbar-shadcn-compose`.
- Use existing `ui/kit` primitives and `AppIcons`.
- Keep icon-only actions readable through `contentDescription`.
- Preserve states: logged out, logged in, loading, busy, empty, error, conflict dialog.
- Current top bar behavior: logged out shows login + refresh; logged in shows logout + upload + refresh.

### Upload Changes

- Build package through existing transfer services:
  - `CharacterCardTransferService.exportJson`
  - `FormatCardTransferService.exportJson`
  - `WorldBookTransferService.exportJson`
- Validate locally with `CommunityPackagePolicy`.
- Compute sha256 from UTF-8 package bytes.
- Upload package to Storage first.
- Call Edge Function to publish or overwrite metadata only after upload succeeds.
- Owner overwrite must use the same item type and a same-name local candidate.
- Character upload candidates must exclude downloaded community cards.
- Character preview upload must stay low-resolution and compressed; never upload the original avatar image as the community preview.
- Character community preview currently comes from the card avatar, not the chat background. Render it as a small square avatar preview in lists, with download action below the preview and item-click opening detail.
- Preview images use `CommunityPreviewCache` so app-start/page-load prefetch and `AsyncImage` share the same Coil memory/disk cache keys.
- Treat fallback paths as failures. Do not hide failed upload/storage/function steps.

### Download/Import Changes

- Download package from `community-packages`.
- Detail preview reads package content from `community-packages` without incrementing download count.
- Decode with existing transfer services.
- Detect conflicts with `NamePolicy.isSame`.
- Import through existing `importNew` / `overwrite` transfer APIs.
- Community character imports/overwrites must save community source metadata after materialization. Normal file imports and duplicate/copy flows must leave that metadata empty.
- Character imports with documents must either start RAG indexing or clearly leave documents pending; never mark a failed primary path as success.

### Backend Changes

- Update `supabase/community_schema.sql` for table, bucket, policy, or RPC changes.
- Update Edge Function when the published payload or validation changes.
- Re-deploy:

```powershell
npx.cmd --yes supabase functions deploy submit-community-item --project-ref rrlxofrrgfnhjfyrqvry --use-api
```

## Verification

Run from `app/`:

```powershell
.\gradlew.bat :app:compileDebugKotlin -PCHATBAR_SUPABASE_URL=https://rrlxofrrgfnhjfyrqvry.supabase.co -PCHATBAR_SUPABASE_ANON_KEY=<publishable-key>
.\gradlew.bat test
.\gradlew.bat assembleDebug -PCHATBAR_SUPABASE_URL=https://rrlxofrrgfnhjfyrqvry.supabase.co -PCHATBAR_SUPABASE_ANON_KEY=<publishable-key>
```

Anonymous list smoke check:

```powershell
curl.exe -s -i -H "apikey: <publishable-key>" -H "Authorization: Bearer <publishable-key>" "https://rrlxofrrgfnhjfyrqvry.supabase.co/rest/v1/community_items?select=id"
```

Expected empty-new-project result: `HTTP 200` and `[]`.

Manual checks:

- Community tab opens without `Supabase 未配置`.
- Logged-out user can refresh/list/download.
- Community list shows readable long titles and opens a detail dialog on item click. Detail dialog shows decoded character/format/world-book content without increasing download count.
- Logged-out user cannot see upload action.
- Discord login returns through `chatbar://auth/callback`.
- Logged-in user can upload each type once.
- Download conflict dialog offers new copy / overwrite / cancel.
- Downloaded character cards show as community/read-only in Manage, can be copied, and show/update via one-click update when remote `sha256` or `updated_at` changes.
- Uploading a character with a duplicate community name/source name returns a clear conflict and leaves no published row.

## Maintenance Rule

After any community-related change, update this skill when future agents would otherwise miss a changed fact. Update especially when changing:

- Routes, tab behavior, or back behavior.
- Supabase project/config/env var names.
- `community_items` schema, bucket names, policies, or RPCs.
- Edge Function request/response contract.
- Upload/download/import conflict logic.
- Package schema support or size limits.
- Discord OAuth redirect/auth behavior.

Keep this skill compact. Replace stale facts instead of appending history.
