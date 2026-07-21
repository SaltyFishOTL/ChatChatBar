---
name: chatbar-background-work-runtime
description: Maintain ChatBar shared AI background-work protection across AiBackgroundWorkManager, StreamingForegroundService, streaming notifications, network guards, wake/Wi-Fi locks, and foreground lease generations. Use when changing or diagnosing ForegroundServiceDidNotStartInTimeException, foreground-service startup/stop races, background generation cancellation, no-network aborts, notification stop behavior, or shared long-running AI work used by Chat, Moments, cards, memory, community, and image flows.
---

# ChatBar Background Work Runtime

Keep Android foreground-service lifecycle, shared work leases, network protection, and feature orchestration as separate owners.

## First Read

- Lease/reference counting and network guard: app/app/src/main/java/com/example/chatbar/domain/service/AiBackgroundWorkManager.kt
- Android service and wake/Wi-Fi locks: app/app/src/main/java/com/example/chatbar/domain/service/StreamingForegroundService.kt
- Notification channels, updates, and stop action: app/app/src/main/java/com/example/chatbar/domain/service/StreamingNotificationManager.kt
- Service declaration and permissions: app/app/src/main/AndroidManifest.xml
- App initialization and shared stop signal: app/app/src/main/java/com/example/chatbar/ChatBarApp.kt
- Regression tests: app/app/src/test/java/com/example/chatbar/domain/service/AiBackgroundWorkManagerTest.kt

Find current callers by searching for `AiBackgroundWorkManager.` before assuming only Chat uses it.

Use chatbar-model-request-runtime for HTTP/SSE behavior, chatbar-image-generation-runtime for NovelAI work, chatbar-moments for scheduling/product policy, and chatbar-emulator-test for device verification.

## Ownership

- `AiBackgroundWorkManager` owns active-work counting, foreground lease generations, readiness, protection-loss propagation, network monitoring, and final service release.
- `StreamingForegroundService` owns immediate foreground promotion, locks, stop-action handling, and destruction signals.
- `StreamingNotificationManager` owns notification construction and channels; it does not own work lifetime.
- Feature callers own result persistence, UI state, and feature-specific cancellation semantics.

## Foreground-Service Contract

- After every successful `startForegroundService()`, allow `startForeground()` to complete before any `stopService()`, `stopSelf()`, or `stopForeground()`. Android 12+ can otherwise throw `ForegroundServiceDidNotStartInTimeException` even when stopping was intentional.
- Let the service publish the first actionable notification. A pre-published stop action can race initial foreground promotion.
- Mark a lease ready only after foreground promotion succeeds.
- If work finishes, times out, loses network, or is cancelled before readiness, retain its generation and defer service stop until its readiness callback. Do not let a newer generation hide the releasing lease.
- Recheck shared `activeCount` when a deferred release fires. New active work must keep the shared service alive.
- On synchronous start failure, complete readiness exceptionally and clear stale notification state; do not stop a service that never promoted.
- Handle notification `ACTION_STOP` only after foreground promotion, then signal shared cancellation and stop the service.
- Release wake/Wi-Fi locks in `onDestroy()` and propagate unexpected service loss for the active generation.

## Network and Cancellation Rules

- Keep network preflight before model work, but treat it as an early work failure—not permission to break the foreground-service contract.
- Preserve the five-second network-loss grace window unless product behavior explicitly changes.
- Use `requireValidatedInternet = false` only for callers whose transport intentionally permits unvalidated/local connectivity.
- Keep user stop, network loss, foreground-service loss, and model transport failure distinguishable to callers.
- Preserve shared reference counting when multiple AI tasks overlap; one caller finishing must not stop protection for another.

## Diagnosis Workflow

1. Match crash class and service name; inspect timestamps around the last recorded operation.
2. Trace `acquireForegroundLease()` through service readiness and every timeout/cancellation/finally path.
3. Inspect all direct service stop paths plus notification `ACTION_STOP`.
4. Check generation handoff when old work releases before new work starts.
5. Confirm the feature uses shared protection, then keep feature policy in its owning skill.
6. Add regression coverage at the lease/readiness boundary before device reproduction.

Useful log tags: `AiBackgroundWork` and `StreamingForeground`.

## Regression Matrix

- Foreground ready before finish; finish before ready; readiness after caller timeout.
- Synchronous service-start failure and foreground-promotion failure.
- No network before readiness; network loss during work.
- Notification stop before/after readiness; stale stop notification.
- New generation starts while an old generation waits for release.
- Two overlapping callers finish in either order.
- Service destruction during active work; normal destruction after final release.
- Android 12 device/emulator reproduction for foreground-service timeout crashes.

## Stop Conditions

- Do not remove foreground protection, network validation, or reference counting to silence a lifecycle crash.
- Do not duplicate shared service lifecycle logic in feature ViewModels or schedulers.
- Do not move model request, image persistence, or Moments scheduling policy into this runtime.
