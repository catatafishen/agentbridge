# Semantic Memory for AgentBridge — Architecture & Implementation

> **Attribution**: This feature is inspired by and adapted from
> [MemPalace](https://github.com/milla-jovovich/mempalace) by milla-jovovich,
> licensed under the MIT License. The architecture, chunking strategies, memory
> classification heuristics, 4-layer memory stack, and knowledge graph design are
> translated from MemPalace's Python implementation into native Java for the
> IntelliJ platform. We gratefully acknowledge the original project.

---

## Overview

AgentBridge includes a **semantic memory system** that persists across sessions,
giving agents awareness of prior decisions, preferences, milestones, and problems.
The system is **opt-in** (disabled by default), **local-only** (no data leaves the
machine), and runs entirely within the IntelliJ process using bundled and downloaded
dependencies.

### Key Capabilities

- **Semantic (vector) search** over past conversations using cosine similarity
- **Knowledge graph** with temporal validity for structured facts
- **4-layer memory stack** providing tiered context from identity to deep search
- **Automatic mining** of conversation turns into classified memory entries
- **History backfill** to mine all existing sessions when first enabled
- **9 MCP tools** for agents to search, store, and query memory
- **Per-project isolation** with separate indexes per project directory

---

## What Was Adapted from MemPalace

| MemPalace Concept | Our Implementation |
|---|---|
| ChromaDB vector store | **Apache Lucene** KNN vector search (bundled in IntelliJ) |
| sentence-transformers embeddings | **ONNX Runtime Java** + all-MiniLM-L6-v2 model |
| Palace → Wings → Rooms → Drawers | Same hierarchy, stored in Lucene documents with metadata fields |
| 4-layer memory stack (L0–L3) | Same design: identity file + essential story + on-demand + deep search |
| `convo_miner.py` exchange chunking | `ExchangeChunker` — extracts Q+A pairs from `EntryData.Prompt` + `EntryData.Text` |
| `general_extractor.py` classification | `MemoryClassifier` — 5-type regex extraction (decisions, preferences, milestones, problems, technical) |
| Knowledge graph (SQLite triples) | `KnowledgeGraph` — SQLite with temporal validity (`valid_from`, `valid_until`) |
| MCP tools (19 tools) | 9 MCP tools in our existing tool infrastructure |
| Write-ahead log (JSONL audit) | `WriteAheadLog` — JSONL WAL for all write operations |
| `config.py` (env > file > defaults) | `MemorySettings` — `PersistentStateComponent` with IDE settings UI |

### Not Implemented

| MemPalace Feature | Reason |
|---|---|
| AAAK dialect (lossy compression) | Experimental in MemPalace (84.2% vs 96.6% R@5). Raw mode is better. |
| People map | Personal assistant feature, not relevant for coding agents |
| Emotional memory type | Replaced with "technical" — more useful for coding context |
| Palace graph traversal / tunnels | Advanced feature — can add later if needed |
| Shell hooks for auto-save | Replaced with MCP-native approach (see [Future Work](#future-work)) |
| Diary tools | Per-agent diary write/read — deferred (see [Future Work](#future-work)) |

---

## Technical Stack

### Apache Lucene — Vector Search

IntelliJ bundles Lucene with full KNN vector support. No additional dependency needed.

- `KnnFloatVectorField` — stores 384-dimensional float vectors
- `KnnFloatVectorQuery` — HNSW-based approximate nearest neighbor search
- `VectorSimilarityFunction.COSINE` — cosine similarity scoring
- Combined queries: pre-filter by metadata (wing/room/type), then KNN

### ONNX Runtime Java — Embedding Inference

| Property | Value |
|---|---|
| Maven artifact | `com.microsoft.onnxruntime:onnxruntime:1.24.3` |
| Size | ~60MB (includes native libs for Linux, macOS, Windows) |
| Inference speed | <100ms per sentence on CPU |

### all-MiniLM-L6-v2 — Embedding Model

| Property | Value |
|---|---|
| Source | [sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) |
| Format | ONNX (exported via Hugging Face Optimum) |
| Size | ~90MB (downloaded on first use) |
| Dimensions | 384 |
| License | Apache 2.0 |
| Tokenizer | WordPiece with `vocab.txt` (~232KB) |
| Storage | `~/.agentbridge/models/all-MiniLM-L6-v2/` (shared across projects) |

### SQLite — Knowledge Graph

Already available via `sqlite-jdbc` dependency. Used for the knowledge graph triple
store with temporal validity tracking.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                     V2 SessionStore                            │
│                  (raw conversation JSONL)                       │
│           Source of truth for replay & client export            │
└───────┬────────────────────────────────────────────────────────┘
        │
        │  TurnMiner.mineTurn() — called per-turn or via backfill
        │
┌───────▼────────────────────────────────────────────────────────┐
│                    TurnMiner Pipeline                           │
│                                                                 │
│  1. Extract Q+A pairs (ExchangeChunker)                         │
│  2. Quality filter (skip < 200 chars, skip pure tool output)    │
│  3. Classify: decisions / preferences / milestones / problems   │
│  4. Detect room (topic) via keyword scoring (RoomDetector)      │
│  5. Generate embeddings (ONNX Runtime + all-MiniLM-L6-v2)      │
│  6. Store in Lucene index + update knowledge graph              │
└───────┬────────────────────────────────────────────────────────┘
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│                    MemoryStore (Lucene)                         │
│              .agent-work/memory/lucene-index/                   │
│                                                                 │
│  Document fields:                                               │
│    - id (StringField, stored)                                   │
│    - content (TextField, stored)                                │
│    - embedding (KnnFloatVectorField, 384-dim, cosine)           │
│    - wing (StringField, stored+indexed)                         │
│    - room (StringField, stored+indexed)                         │
│    - memory_type (StringField: decision/preference/milestone/   │
│                   problem/technical/general)                    │
│    - source_session (StringField)                               │
│    - source_file (StringField)                                  │
│    - agent (StringField)                                        │
│    - filed_at (StringField, ISO 8601)                           │
│    - added_by (StringField: "miner" or "mcp")                  │
│                                                                 │
│  Queries:                                                       │
│    - KnnFloatVectorQuery (semantic search)                      │
│    - BooleanQuery + TermQuery (wing/room/type filters)          │
│    - Combined: pre-filter by metadata, then KNN                 │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│                 KnowledgeGraph (SQLite)                         │
│              .agent-work/memory/knowledge.sqlite3               │
│                                                                 │
│  Tables:                                                        │
│    triples: id, subject, predicate, object, valid_from,         │
│             valid_until, source_closet, created_at              │
│                                                                 │
│  Operations:                                                    │
│    - add(subject, predicate, object)                            │
│    - query(entity, as_of, direction)                            │
│    - invalidate(subject, predicate, object, ended)              │
│    - timeline(entity)                                           │
│    - stats()                                                    │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│                   MCP Tools (exposed to agents)                 │
│                                                                 │
│  Read tools:                                                    │
│    memory_search       — semantic search with optional filters  │
│    memory_status       — drawer count, wing/room breakdown      │
│    memory_wake_up      — L0+L1 context (~600-900 tokens)        │
│    memory_recall       — L2 on-demand (wing/room filtered)      │
│    memory_kg_query     — knowledge graph entity lookup           │
│    memory_kg_timeline  — chronological fact history              │
│                                                                 │
│  Write tools:                                                   │
│    memory_store        — file content into a wing/room          │
│    memory_kg_add       — add knowledge graph triple              │
│    memory_kg_invalidate — mark a fact as no longer true          │
└────────────────────────────────────────────────────────────────┘
```

---

## Storage Layout

```
<project>/
  .agent-work/
    memory/
      lucene-index/         ← Lucene vector index (drawers + embeddings)
      knowledge.sqlite3     ← Knowledge graph (entity triples)
      wal/
        write_log.jsonl     ← Write-ahead log (audit trail)
      identity.txt          ← Optional L0 identity file (user-written)

~/.agentbridge/
  models/
    all-MiniLM-L6-v2/
      model.onnx            ← Downloaded on first use (~90MB)
      vocab.txt             ← WordPiece vocabulary (~232KB)
```

---

## Package Structure

### Production Code

```
plugin-core/src/main/java/com/github/catatafishen/agentbridge/
  memory/
    MemorySettings.java              ← PersistentStateComponent (opt-in toggle, wing, limits)
    MemorySettingsConfigurable.java  ← Settings UI panel with backfill button
    MemoryService.java               ← Project-level service (lifecycle, lazy init, Disposable)

    store/
      MemoryStore.java               ← Lucene index wrapper (add, search, delete, status, dedup)
      DrawerDocument.java            ← POJO for a single memory drawer
      MemoryQuery.java               ← Query builder (semantic + metadata filters)

    embedding/
      Embedder.java                  ← Functional interface for embedding injection
      EmbeddingService.java          ← ONNX Runtime session management + inference
      WordPieceTokenizer.java        ← Java tokenizer (vocab.txt → token IDs)
      ModelDownloader.java           ← Download model on first use with progress

    mining/
      TurnMiner.java                 ← Pipeline orchestrator: chunk → filter → classify → embed → store
      ExchangeChunker.java           ← Q+A pair chunking (from convo_miner.py)
      MemoryClassifier.java          ← 5-type regex classification (from general_extractor.py)
      RoomDetector.java              ← Topic detection via keyword scoring (from convo_miner.py)
      QualityFilter.java             ← Skip low-value content (short, tool-only, status patterns)
      BackfillMiner.java             ← Iterate all historical sessions through TurnMiner pipeline

    kg/
      KnowledgeGraph.java            ← SQLite triple store (temporal validity, entity queries)
      KgTriple.java                  ← Triple POJO (subject, predicate, object, validity window)

    layers/
      MemoryStack.java               ← Unified 4-layer interface
      IdentityLayer.java             ← L0: read identity.txt
      EssentialStoryLayer.java       ← L1: top drawers from Lucene, grouped by room
      OnDemandLayer.java             ← L2: wing/room filtered retrieval
      DeepSearchLayer.java           ← L3: full semantic search

    wal/
      WriteAheadLog.java             ← JSONL audit log for all write operations

  psi/tools/memory/
    MemoryToolFactory.java           ← Conditional tool registration (only when enabled)
    MemorySearchTool.java            ← MCP tool: semantic search
    MemoryStatusTool.java            ← MCP tool: palace overview
    MemoryStoreTool.java             ← MCP tool: file content into wing/room
    MemoryWakeUpTool.java            ← MCP tool: L0+L1 wake-up context
    MemoryRecallTool.java            ← MCP tool: L2 on-demand retrieval
    MemoryKgQueryTool.java           ← MCP tool: knowledge graph query
    MemoryKgAddTool.java             ← MCP tool: add KG triple
    MemoryKgInvalidateTool.java      ← MCP tool: invalidate KG triple
    MemoryKgTimelineTool.java        ← MCP tool: chronological fact history
```

### Test Code

```
plugin-core/src/test/java/com/github/catatafishen/agentbridge/memory/
  MemoryPlatformTestCase.java            ← Base class for platform tests (BasePlatformTestCase)
  ServiceRegistrationPlatformTest.java   ← Service container wiring (5 tests)
  MemorySettingsPlatformTest.java        ← Settings defaults, set/get, state round-trip (12 tests)
  MemoryServicePlatformTest.java         ← Disabled/enabled paths, dispose lifecycle (9 tests)
  TurnMinerPlatformTest.java             ← Full doMine() path with replaced services (8 tests)
  BackfillMinerPlatformTest.java         ← Full doBackfill() path with replaced services (7 tests)

  embedding/
    TestEmbeddingFactory.java            ← Test EmbeddingService factory (bypasses ONNX Runtime)
    EmbeddingServiceTest.java            ← meanPool, l2Normalize, embed, embedBatch (24 tests)
    WordPieceTokenizerTest.java          ← Tokenization, sub-words, truncation, padding (13 tests)

  kg/
    KgTripleTest.java                    ← Triple validation, equality, toString (12 tests)
    KnowledgeGraphTest.java              ← CRUD, temporal queries, timeline (15 tests)

  layers/
    IdentityLayerTest.java               ← File I/O, null handling, edge cases (9 tests)
    MemoryLayersTest.java                ← All 4 layers: rendering, filtering, limits (28 tests)

  mining/
    TurnMinerTest.java                   ← Pipeline orchestration via executePipeline() (10 tests)
    ExchangeChunkerTest.java             ← Chunking logic, edge cases (12 tests)
    MemoryClassifierTest.java            ← 5-type classification patterns (10 tests)
    QualityFilterTest.java               ← Quality filtering, status patterns, tool output (20 tests)
    RoomDetectorTest.java                ← Topic detection keywords (8 tests)
    BackfillMinerTest.java               ← executeBackfill(), progress, errors (16 tests)

  store/
    DrawerDocumentTest.java              ← POJO fields, builder, toString (10 tests)
    MemoryQueryTest.java                 ← Query builder, filters (8 tests)
    MemoryStoreTest.java                 ← Lucene index operations end-to-end (18 tests)

  wal/
    WriteAheadLogTest.java               ← WAL append, read, rotation (9 tests)
```

**Total: ~250 tests** across 23 test classes (17 JUnit 5, 6 platform tests).

---

## Component Design

### Settings (Opt-In)

`MemorySettings` is a `PersistentStateComponent<State>` registered as a project service.

Key settings:
- `enabled` (default: `false`) — master toggle
- `wing` (default: auto-detected from project name) — the "wing" label for this project
- `maxDrawersPerTurn` (default: `10`) — cap on memories stored per conversation turn
- `minChunkLength` (default: `200`) — minimum character length for quality filtering
- `backfillCompleted` — tracks whether historical session mining has been done

The settings UI (`MemorySettingsConfigurable`) includes a "Mine Existing History"
button that shows session count, warns about processing time, and provides progress
feedback. When memory is first enabled (toggled from disabled → enabled), the UI
automatically offers to run a backfill.

### MemoryService (Lifecycle)

`MemoryService` is the central project-level service managing all memory components.
It uses **lazy double-checked-locking initialization** — components are created on
first access, not at project open.

Initialization order (in `ensureInitialized()`):
1. `WriteAheadLog` → `.agent-work/memory/wal/`
2. `MemoryStore` (Lucene) → `.agent-work/memory/lucene-index/`
3. `EmbeddingService` → loads ONNX model (downloads on first use)
4. `KnowledgeGraph` (SQLite) → `.agent-work/memory/knowledge.sqlite3`

Implements `Disposable` — closes Lucene index, ONNX session, and SQLite connection
on project close.

### EmbeddingService

Manages ONNX Runtime inference for text → 384-dim float vector conversion.

Pipeline: text → `WordPieceTokenizer` → token IDs → ONNX Runtime → raw output →
`meanPool()` → `l2Normalize()` → embedding vector.

`ModelDownloader` handles first-use download of the ONNX model and vocabulary file
from Hugging Face, with progress reporting and cancellation support.

### TurnMiner Pipeline

Orchestrates per-turn memory extraction:

1. **ExchangeChunker**: Extracts Q+A pairs from conversation entries
2. **QualityFilter**: Skips short content (<200 chars), pure tool output, status patterns
3. **MemoryClassifier**: Classifies each chunk as decision/preference/milestone/problem/technical/general
4. **RoomDetector**: Detects topic via keyword scoring (build, testing, database, ui, etc.)
5. **EmbeddingService**: Generates 384-dim vector embeddings
6. **MemoryStore**: Stores classified, embedded drawers with deduplication

Returns `MineResult(stored, filtered, duplicates, total)` for progress tracking.

### BackfillMiner

Iterates all historical sessions from `SessionStoreV2`, feeding each through the
`TurnMiner` pipeline. Tracks progress via callback, aggregates results across sessions,
and marks `backfillCompleted` in settings when done. Handles per-session errors
gracefully (logs and continues).

### Memory Stack (4 Layers)

| Layer | Class | Purpose | Token Budget |
|---|---|---|---|
| L0 | `IdentityLayer` | Read `identity.txt` (user-written project description) | ~100-200 |
| L1 | `EssentialStoryLayer` | Top drawers from Lucene, grouped by room | ~400-700 |
| L2 | `OnDemandLayer` | Wing/room filtered retrieval on demand | Variable |
| L3 | `DeepSearchLayer` | Full semantic search across all drawers | Variable |

L0+L1 are used for wake-up context (`memory_wake_up` tool). L2 is for targeted
recall (`memory_recall`). L3 is for comprehensive search (`memory_search`).

### Knowledge Graph

SQLite-backed triple store with temporal validity:

- **Add**: `(subject, predicate, object)` with `valid_from = now`
- **Query**: Find all facts about an entity, optionally filtered by `as_of` date
- **Invalidate**: Set `valid_until` on a triple (fact no longer true)
- **Timeline**: Chronological history of all facts about an entity

### MCP Tools

9 tools registered conditionally via `MemoryToolFactory` (only when memory is enabled):

| Tool | Type | Description |
|---|---|---|
| `memory_search` | Read | Semantic search with optional wing/room/type filters |
| `memory_status` | Read | Drawer count, wing/room breakdown, storage stats |
| `memory_wake_up` | Read | L0+L1 context for session start (~600-900 tokens) |
| `memory_recall` | Read | L2 on-demand retrieval with wing/room filters |
| `memory_store` | Write | File content into a specific wing/room |
| `memory_kg_query` | Read | Knowledge graph entity lookup with temporal filter |
| `memory_kg_timeline` | Read | Chronological fact history for an entity |
| `memory_kg_add` | Write | Add a knowledge graph triple |
| `memory_kg_invalidate` | Write | Mark a fact as no longer true |

---

## Test Coverage

Coverage is measured via JaCoCo against instrumented classes (IntelliJ's `instrumentCode`
task applies `@NotNull` bytecode checks that change class hashes).

| Package | Line Coverage | Notes |
|---|---|---|
| store | 96.2% | Full Lucene index lifecycle, search, dedup |
| wal | 90.5% | Append, read, rotation |
| kg | 90.1% | CRUD, temporal queries, timeline, stats |
| layers | 90.1% | All 4 layers including rendering and filtering |
| mining | 86.3% | Remaining gaps are project-service-dependent private methods |
| embedding | 56.1% | ONNX Runtime inference and model download are untestable without model |

### Testing Strategy

**JUnit 5 tests** cover pure logic: chunking, classification, room detection, quality
filtering, Lucene operations, SQLite operations, tokenization, vector math. These use
package-private no-arg constructors and extracted execution methods with explicit
dependency parameters.

**Platform tests** (`BasePlatformTestCase`) cover project-service-dependent code paths:
service registration, settings persistence, `TurnMiner.doMine()` and `BackfillMiner.doBackfill()`
with real IntelliJ project instances and replaced services via `ServiceContainerUtil.replaceService()`.

Testability is achieved without mocking frameworks through:
- `Embedder` functional interface for embedding injection
- `InferenceFunction` functional interface for ONNX inference injection
- `EntryLoader` / `MineFunction` functional interfaces for backfill dependency injection
- Package-private test constructors on `MemoryService` and `EmbeddingService`
- Extracted execution methods (`executePipeline()`, `executeBackfill()`) with explicit dependencies

---

## Risks & Mitigations

### 1. ONNX Runtime Size (~60MB)

The ONNX Runtime JAR includes native libraries for all platforms, increasing plugin
download size.

**Mitigation**: The model file (~90MB) is downloaded separately on first use, not
bundled with the plugin. Platform-specific classifier JARs could reduce the JAR size
further if needed.

### 2. IntelliJ Lucene Version Changes

We use IntelliJ's bundled Lucene. A major version bump could break our code.

**Mitigation**: Our Lucene usage is limited to basic document indexing and KNN queries.
These APIs have been stable since Lucene 9. If needed, we can bundle our own Lucene
JAR (~3MB) with classloader isolation.

### 3. First-Use Model Download

Users need to download ~90MB on first enable. Poor network = poor first experience.

**Mitigation**: Progress indicator in status bar. Cancellation support. Model cached
user-global (`~/.agentbridge/models/`) — downloaded once per machine, not per project.

### 4. Embedding Quality for Code

all-MiniLM-L6-v2 is trained on natural language, not code.

**Mitigation**: The mining pipeline extracts *prose* from conversations (decisions,
explanations, problems) — not raw code. The `QualityFilter` and `MemoryClassifier`
ensure we store human-readable content. For code-specific search, the existing
`search_text` and `search_symbols` tools remain available.

### 5. Storage Growth

A heavy user might accumulate thousands of drawers over months.

**Mitigation**: Lucene indexes are compact (~1KB per drawer including embeddings).
10,000 drawers ≈ ~10MB. The `maxDrawersPerTurn` cap prevents runaway growth. Retention
policies (age-based cleanup, importance-based pruning) can be added later.

---

## Future Work

### Per-Turn Auto-Mining Hook

Currently, `TurnMiner.mineTurn()` is available as an API but is not automatically
invoked on each conversation turn. The original proposal planned to hook into
`PromptOrchestratorCallbacks` for automatic per-turn mining. This would make memory
accumulation fully transparent — agents would benefit from memory without needing to
call `memory_store` explicitly.

### Wake-Up Context Injection

Automatic injection of L0+L1 context on `session/new` so agents start with awareness
of prior work. Currently agents must call `memory_wake_up` explicitly.

### Diary Tools

Per-agent diary write/read tools (`memory_diary_write`, `memory_diary_read`) for
agents to maintain structured session notes. This was in the original proposal but
deferred.

### Cross-Project Memory

Global `~/.agentbridge/memory/` store in addition to per-project memory, allowing
agents to recall decisions and preferences across different projects.

### Retention Policies

Age-based cleanup, importance-based pruning, and configurable drawer limits to
manage long-term storage growth.

### Advanced Knowledge Graph

- Palace graph traversal (connecting related entities across rooms)
- Tunnel detection (cross-wing entity relationships)
- Automatic KG triple extraction from mined conversations

---

## Attribution

This feature is adapted from [MemPalace](https://github.com/milla-jovovich/mempalace)
by milla-jovovich, licensed under the [MIT License](https://opensource.org/licenses/MIT).

The following components are translated from MemPalace's Python implementation:

| Our Component | MemPalace Source | Adaptation |
|---|---|---|
| `ExchangeChunker` | `convo_miner.py` `chunk_exchanges()` | Java port, adapted for `EntryData` model |
| `MemoryClassifier` | `general_extractor.py` | Java port, "emotional" → "technical" for coding context |
| `RoomDetector` | `convo_miner.py` `detect_convo_room()` | Java port, same keyword sets |
| `MemoryStack` | `layers.py` `MemoryStack` | Java port, Lucene instead of ChromaDB |
| `KnowledgeGraph` | `knowledge_graph.py` | Java port, same SQLite schema |
| `QualityFilter` | `convo_miner.py` quality checks | Java port, extended with tool output detection |
| `WriteAheadLog` | MemPalace audit trail pattern | Java port, JSONL format |
