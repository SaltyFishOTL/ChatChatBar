---
name: chatbar-moments
description: Maintain ChatBar's 朋友圈/Moments module. Use when changing the Moments root tab, moment generation policy, scheduler/background alarms, moment prompts, NovelAI handoff for moment images, moment storage, likes/deletion, unread red dots, debug generation, or moment image preview actions.
---

# ChatBar Moments

## Scope

Use this skill before editing 朋友圈 code or docs:

- `app/app/src/main/java/com/example/chatbar/data/local/entity/MomentEntities.kt`
- `app/app/src/main/java/com/example/chatbar/data/repository/MomentRepository.kt`
- `app/app/src/main/java/com/example/chatbar/domain/moment/`
- `app/app/src/main/java/com/example/chatbar/ui/moments/`
- 朋友圈 settings, root Tab wiring, unread badges, debug UI, or background alarm code
- 朋友圈 prompt builders in `PromptTemplates.kt`
- 朋友圈 image preview/save/share/set-card-image behavior

Also read `chatbar-novelai-prompt` before changing NovelAI prompt construction, and `chatbar-shadcn-compose` before changing Compose UI.

## Product Rules

- Global `momentsEnabled` defaults false. When off, hide root 朋友圈 Tab and do not generate.
- Character `momentsEnabled` defaults true. Existing persisted characters with explicit false stay disabled.
- AI judges only current timing/progress suitability. Do not judge whether character owns a phone or can post.
- Active gate: session has user/AI exchange within 48 hours.
- Progress gate: if previous moment exists, judge whether latest state has enough new progress.
- Judge phase sends only long-term memory, previous moment, and latest message.
- Generation phase sends role/card info excluding opening greetings, long-term memory, previous moment, and recent 3 full messages. Do not truncate message bodies.
- Multi-character card: moment AI chooses sender; no comment/reply simulation for other roles in MCP version.
- Moments are front-end immersion only. Do not enter chat mainline, write long-term memory, add comments, or add transfer-to-chat entry.
- Private moments show lock/`仅你可见`; base like count fixed 0.

## Scheduler Rules

- Candidate frequency: per enabled card defaults to every 2-13 hours and is user-adjustable in settings.
- When frequency settings change, delete only future pending schedule tasks and regenerate them with the new range; do not delete due/running/completed/failed history.
- Limits: per-card max 4 posts/day; global max 18 posts/day.
- Catch-up generation is not separately capped; process due scheduled items one by one.
- Count pending, completed, and failed tasks when enforcing daily schedule limits.
- Do not double-count a generated post and its linked task for daily limits; count orphan posts only for legacy/debug data without a task.
- Moments do not generate while app is not running. Do not use boot/package/alarm receivers to start generation in background.
- `MomentScheduler.runOnce()` is foreground/startup catch-up path: cancel legacy alarms, ensure schedules, process due tasks, then ensure schedules again.
- `ensureFutureSchedules()` creates real pending schedule records for preview/debug and scheduler preparation. It must not process due tasks.
- `ensureFutureSchedules()` must not schedule background alarms.
- Debug timeline may display next 12 hours, but do not silently change underlying schedule horizon without checking scheduler behavior.

## Fast Investigation Patterns

- Empty future preview: check global `momentsEnabled`, per-card `momentsEnabled`, active-session gate, pending task rows, preview window length, and daily limit counts before changing generation.
- Default enable changes require all layers: entity default, edit/create ViewModel initial value, JSON missing-field compatibility, and explicit persisted false preservation.
- Settings exposure changes require persisted `AppSettings`, domain normalization, scheduler/repository propagation, UI controls, and tests for old settings JSON.
- Frequency changes should invalidate future pending schedules only; keep due/running/completed/failed records as audit and limit history.
- Schedule limit bugs often come from counting linked task plus generated post twice; count linked task once and count orphan posts only for legacy/debug data.
- Dependent UI should gate on global feature enable while keeping the global switch always reachable.

## Prompt Rules

- Use current default chat model and params for 朋友圈 AI. Do not set `thinkingBudget` to 0.
- Debug generation must expose full AI inputs and outputs.
- Moment copy: 0-60 Chinese characters, short, private, suggestive, like an accidental life fragment. Do not recap chat logs.

## Image Rules

- Moment AI outputs image intent/brief only.
- If NovelAI token is not configured, save text-only moment instead of failing image generation.
- NovelAI prompt design uses shared `NOVELAI_IMAGE_PROMPT_SYSTEM` via `PromptTemplates.novelAiImagePromptSystem(...)`.
- Do not add `NOVELAI_IMAGE_PROMPT_MOMENT_TEMPLATE` or feature-specific NovelAI system prompts.
- If moments need visual guidance, add only small modifiers: photo style, private/life-slice feeling, composition, candid/low-angle/door-gap/mirror/distant/phone snapshot when suitable.
- Text/image generation failure should create a visible placeholder moment with failure reason and retry action; do not hide primary failure with a success-looking fallback.

## UI Rules

- Root tabs when enabled: `聊天 / 朋友圈 / 社区 / 管理`.
- Management settings must keep the global enable switch visible; detailed 朋友圈 settings and debug generation sections show only when global `momentsEnabled` is on.
- Timeline should resemble WeChat/QQ Moments: white background, avatar, nickname, copy, single image, time, like button, like count.
- No comment input, no comment list, no reply-to-chat, no long-term-memory entry.
- Like toggles local state. Public moment display count changes with local like; private moment base remains 0 and may show only local-liked state if product explicitly asks.
- Placeholder failed moments show retry action and stream retry progress; they should not look like successful posts.
- Bottom 朋友圈 and chat tabs show red dot when unread items exist.
- Deletion stays unobtrusive; current product uses long-press delete for single post.
- Moment image interactions should reuse shared chat image preview module where possible: open large image, zoom, save/share, set as card avatar/background.

## Persistence

- JSON-file persistence goes through repository/storage patterns already in project.
- Keep entity migrations backward compatible for existing moment JSON.
- Deleting a moment should remove repository record and clean owned image files when safe.
- Do not delete user-owned unrelated images.

## Verification

- From `app/`, run `.\gradlew.bat :app:compileDebugKotlin` for Kotlin changes.
- Run `.\gradlew.bat test` for policy, prompt assembly, repository, scheduler, or parsing changes.
- Run `powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble` for UI, navigation, background alarm, Android API, or shared behavior changes.
- Add/update JVM tests around `PromptTemplates`, `MomentPolicy`, scheduler limits, repository likes/delete, and debug input assembly when touched.
