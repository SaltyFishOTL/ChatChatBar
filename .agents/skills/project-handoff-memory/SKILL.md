---
name: project-handoff-memory
description: Maintain compact living handoff memory for long-running project work. Use when Codex must read, create, clean, or update handoff/audit/plan/decision files before planning, committing, pausing, resuming after context compaction, asking for manual testing, or handing work to another agent/thread.
---

# Project Handoff Memory

## Purpose

Use handoff files as durable working memory, not diaries. Keep project memory short, current, evidence-based, and useful for deciding the next safe action.

Future Codex should answer in under two minutes:

- What is true now?
- What slice is active?
- What exact next action is safest?

## Core Rule

Read memory before planning. Update memory before stopping.

Treat memory as a map, not proof. Verify stale or risky facts from source files before relying on them.

## Workflow

### 1. Locate Memory

Prefer paths named by the user. Otherwise search with `rg --files` for:

- `*handoff*`
- `*audit*`
- `*parity*`
- `*plan*`
- `*decision*`
- `doc/*`
- `docs/*`

Classify files by job:

- Handoff: current operational state, active slice, blockers, verification baseline, next steps.
- Audit/parity: requirement matrix, coverage, gaps, evidence quality.
- Plan: roadmap, milestone order, sequencing.
- Decision record: durable decisions future agents must preserve.

Keep these jobs separate unless project is tiny.

### 2. Read Before Acting

Before planning, editing, testing, or committing:

- Read handoff first.
- Read audit/parity when task involves migration, parity, QA, UX equivalence, or requirement closure.
- Read plan when choosing the next slice.
- Read decision records when changing architecture, storage, APIs, or UX principles.

### 3. Maintain Living State

Update by replacing stale bullets. Do not append chronological logs.

Every retained entry should be one of:

- Current fact with evidence.
- Active work item.
- Known gap.
- Failed attempt worth avoiding.
- Rejected option with reason.
- Untested flow.
- Unconfirmed fact or product decision.
- Blocker.
- Recommended next action.
- Architecture guardrail.
- Verification baseline.

Delete or compress old entries once they no longer affect decisions.

### 4. Evidence Labels

Use explicit evidence strength:

- Strong: automated test, build, static check, emulator/device run, source inspection with exact file.
- Medium: manual user test with date/scope, screenshot, reproducible command result.
- Weak: inference, memory, expected behavior, partial smoke check.
- None: mark untested or unconfirmed.

Never mark a broad area complete from one narrow check.

### 5. Stop-Point Update

Update memory before:

- Ending a long turn.
- Committing a stable point.
- Asking user to test.
- Changing direction.
- Starting risky refactor.
- Handing work to another agent/thread.
- Pausing due to blocker.
- Resuming after context compaction once state is re-established.

If time is short, update only `In Progress`, `Blockers`, `Untested`, and `Recommended Next Steps`.

### 6. Architecture Memory

When architecture changes, record guardrails only:

- Ownership boundaries.
- Dependency direction.
- Screen/module responsibility.
- Files that must stay small.
- State/data flow.
- Anti-patterns to avoid.

Do not copy full file maps unless they guide future decisions.

## Templates

Read [references/templates.md](references/templates.md) when creating a new handoff, audit, or decision file.

## Anti-Patterns

- Timestamped diary entries.
- Huge pasted command logs.
- Duplicate state across handoff and audit.
- `Done` without evidence.
- Old blockers left after resolution.
- Multiple unrelated active slices.
- Architecture notes that describe everything instead of guardrails.
- Planning from memory without source verification.
