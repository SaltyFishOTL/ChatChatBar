# RAG Recall Diff Fixture

更新时间：2026-06-21

目标：为 P0-01 建立 Android vs Flutter RAG recall diff 的固定输入和固定报告格式。当前已完成 Flutter 与 Android 双端自动化 fixture，二者断言同一份 expected report。

## Fixture

Fixture ID：`rag-parity-lock-ras-v1`

输入：

```text
characterCard.id = card-1
characterCard.name = LOCK
session.id = session-1
session.contextWindowSize = 1
query = 她在 RAS 里负责什么？
planner topics = [RAS band role]
planner queries = [LOCK RAS guitarist stage rhythm]
planner entities = [LOCK, RAS]
query embedding = [1, 0]
docRagTopK = 2
memoryRagTopK = 2
docRagSimilarityThreshold = 0.5
memoryRagSimilarityThreshold = 0.5
```

Document chunks:

```text
doc-lock-guitar
sourceType = document
sourceId = card-1
embedding = [1, 0]
sourceLabel = world.md > band
content = 【来源】world.md > band
LOCK 是 RAS 的吉他手，也负责稳定舞台推进。

doc-layer-vocal
sourceType = document
sourceId = card-1
embedding = [0.82, 0.1]
sourceLabel = music.md > band
content = 【来源】music.md > band
LAYER 是 RAS 的主唱和贝斯手。

doc-noise
sourceType = document
sourceId = card-1
embedding = [0, 1]
sourceLabel = food.md > cafe
content = 【来源】food.md > cafe
咖啡店今日推荐芝士蛋糕。
```

Chat memory chunks:

```text
memory-old-lock
sourceType = chatMemory
sourceId = session-1
messageId = old-user
embedding = [0.96, 0]
metadata.messageIds = old-user
content = 用户之前确认过：LOCK 负责 RAS 吉他与舞台节奏。

memory-current-excluded
sourceType = chatMemory
sourceId = session-1
messageId = current-user
embedding = [1, 0]
metadata.messageIds = current-user
content = 当前上下文里的 LOCK 记忆，不应重复进入 RAG。
```

Context messages:

```text
old-user: 之前说 LOCK 很可靠。
current-user: 她在 RAS 里负责什么？
```

Because `contextWindowSize = 1`, `current-user` is active context and `memory-current-excluded` must not enter recall.

## Expected Report

Flutter expected output:

```json
{
  "fixtureId": "rag-parity-lock-ras-v1",
  "query": "她在 RAS 里负责什么？",
  "ragQuery": "Topic: RAS band role\nEntities: LOCK, RAS\nQueries:\nLOCK RAS guitarist stage rhythm",
  "cards": [
    {
      "type": "DOCUMENT",
      "source": "world.md > band",
      "content": "【来源】world.md > band\nLOCK 是 RAS 的吉他手，也负责稳定舞台推进。"
    },
    {
      "type": "DOCUMENT",
      "source": "music.md > band",
      "content": "【来源】music.md > band\nLAYER 是 RAS 的主唱和贝斯手。"
    },
    {
      "type": "CHAT_MEMORY",
      "source": "old-user",
      "content": "用户之前确认过：LOCK 负责 RAS 吉他与舞台节奏。"
    }
  ],
  "debugMustContain": [
    "eligible chat_memory after context filter=1",
    "Retrieval Planner",
    "Document multi-route top scores",
    "Chat memory multi-route top scores"
  ]
}
```

## Flutter Evidence

Automated test:

```powershell
cd D:\Projects\ChatBar\flutter_app
D:\Tools\flutter\bin\flutter.bat test test\rag_recall_parity_fixture_test.dart
```

Test file:

```text
flutter_app/test/rag_recall_parity_fixture_test.dart
```

Current status：Flutter fixture passes.

## Android Evidence

Automated test:

```powershell
cd D:\Projects\ChatBar\app
.\gradlew.bat :app:testDebugUnitTest --tests *RagRecallParityFixtureTest
```

Test file:

```text
app/app/src/test/java/com/example/chatbar/domain/rag/RagRecallParityFixtureTest.kt
```

Current status：Android fixture passes.

Implementation note:

- Android fixture is a JVM parity runner that seeds the same chunks/messages/planner/query embedding and mirrors current `ChatViewModel` retrieval ranking rules.
- This intentionally avoids refactoring old Android production RAG in the same step. Future cleanup should extract Android private RAG ranking helpers into a domain controller before wider behavior changes.

## Diff Procedure

Diff command shape after Android fixture exists:

```powershell
cd D:\Projects\ChatBar\app
.\gradlew.bat :app:testDebugUnitTest --tests *RagRecallParityFixtureTest

cd D:\Projects\ChatBar\flutter_app
D:\Tools\flutter\bin\flutter.bat test test\rag_recall_parity_fixture_test.dart
```

Acceptance:

- Same `ragQuery`.
- Same final cards, order, type, source, and content.
- Same exclusion of active-context memory.
- Debug contains source counts and rank diagnostics.

Known caveat:

- Android may use ObjectBox/HNSW in production while Flutter fixture uses in-memory JSON/vector scan. This fixture fixes embeddings and uses tiny data, so it validates retrieval semantics and ordering, not large-scale performance.
- Initial command `.\gradlew.bat test --tests *RagRecallParityFixtureTest` failed because Android Gradle aggregate `test` task does not accept `--tests`; use `:app:testDebugUnitTest`.
