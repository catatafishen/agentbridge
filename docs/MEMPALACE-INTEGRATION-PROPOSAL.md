# Semantic Memory for AgentBridge — Native Implementation Plan

> **Attribution**: This feature is inspired by and adapted from
> [MemPalace](https://github.com/milla-jovovich/mempalace) by milla-jovovich,
> licensed under the MIT License. The architecture, chunking strategies, memory
> classification heuristics, 4-layer memory stack, and knowledge graph design are
> translated from MemPalace's Python implementation into native Java for the
> IntelliJ platform. We gratefully acknowledge the original project.

---

## Problem Statement

AgentBridge has **no semantic memory** that persists across sessions. The existing
`search_conversation_history` MCP tool provides plain text search over raw JSONL
session logs, but there is no:

- **Semantic (vector) search** over past conversations
- **Knowledge graph** or entity extraction
- **Cross-session memory** of decisions, preferences, or problems
- **Wake-up context** that gives agents awareness of prior work

Every time an agent starts, it has zero memory of what happened before — unless the
user manually provides context or the client replays raw conversation history (which
is unstructured and token-expensive).

---

## What We're Adapting

MemPalace is a Python-based, local-only AI memory system backed by ChromaDB. We are
translating its core concepts into native Java using tools already available in the
IntelliJ platform ecosystem:

| MemPalace Concept | Our Native Translation |
|---|---|
| ChromaDB vector store | **Apache Lucene** KNN vector search (bundled in IntelliJ) |
| sentence-transformers embeddings | **ONNX Runtime Java** + all-MiniLM-L6-v2 model |
| Palace → Wings → Rooms → Drawers | Same hierarchy, stored in Lucene documents with metadata fields |
| 4-layer memory stack (L0–L3) | Same design: identity file + essential story + on-demand + deep search |
| `convo_miner.py` exchange chunking | Java equivalent: extract Q+A pairs from `EntryData.Prompt` + `EntryData.Text` |
| `general_extractor.py` classification | Java port: regex-based 5-type extraction (decisions, preferences, milestones, problems, emotional) |
| Knowledge graph (SQLite triples) | Same: SQLite knowledge graph with temporal validity (already have `sqlite-jdbc`) |
| MCP tools (19 tools) | Subset as native MCP tools in our existing tool infrastructure |
| Shell hooks for auto-save | Native turn-completion hooks via `PromptOrchestratorCallbacks` |
| Write-ahead log (JSONL audit) | Same pattern: JSONL WAL for all write operations |
| `config.py` (env > file > defaults) | `PersistentStateComponent` settings with IDE UI (opt-in toggle) |

### What We're NOT Implementing (Initially)

| MemPalace Feature | Reason to Skip |
|---|---|
| AAAK dialect (lossy compression) | Experimental in MemPalace (84.2% vs 96.6% R@5). Raw mode is better. |
| People map | Personal assistant feature, not relevant for coding agents |
| Emotional memory type | Coding context doesn't benefit from emotion classification |
| Palace graph traversal / tunnels | Advanced feature — can add later if needed |

---

## Technical Stack (Verified)

### Apache Lucene — Vector Search

IntelliJ 2025.3 bundles Lucene with full KNN vector support. Verified classes present
in `intellij.libraries.lucene.common.jar`:

- `org.apache.lucene.document.KnnFloatVectorField` — store 384-dim float vectors
- `org.apache.lucene.search.KnnFloatVectorQuery` — HNSW-based approximate nearest neighbor
- `org.apache.lucene.index.VectorSimilarityFunction` — cosine, dot product, euclidean
- `org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader` — HNSW graph codec

**No additional Lucene dependency needed** — we use IntelliJ's bundled version.

> **Risk**: IntelliJ could change the bundled Lucene version between releases. If this
> becomes a problem, we can bundle our own Lucene (the full `lucene-core` JAR is ~3MB).

### ONNX Runtime Java — Embedding Inference

| Property | Value |
|---|---|
| Maven artifact | `com.microsoft.onnxruntime:onnxruntime:1.24.3` |
| Size | ~60MB (includes native libs for Linux, macOS, Windows) |
| Java version | 8+ |
| Inference speed | <100ms per sentence on CPU |

The ONNX Runtime JAR includes platform-specific native libraries (`.so`, `.dylib`,
`.dll`) — it self-extracts at runtime. No manual native lib management needed.

### all-MiniLM-L6-v2 — Embedding Model

| Property | Value |
|---|---|
| Source | [sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) |
| Format | ONNX (exported via Hugging Face Optimum) |
| Size | ~90MB |
| Dimensions | 384 |
| License | Apache 2.0 |
| Tokenizer | WordPiece with `vocab.txt` (~232KB) |

**Delivery**: Downloaded on first use to `~/.agentbridge/models/all-MiniLM-L6-v2/`.
The model is shared across all projects. A Java WordPiece tokenizer implementation
handles tokenization using the bundled `vocab.txt`.

### SQLite — Knowledge Graph

Already a dependency (`org.xerial:sqlite-jdbc:3.51.3.0`). The knowledge graph stores
entity→relationship→entity triples with temporal validity, identical to MemPalace's
`knowledge_graph.py` schema.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                     V2 SessionStore                            │
│                  (raw conversation JSONL)                       │
│           Source of truth for replay & client export            │
└───────┬────────────────────────────────────────────────────────┘
        │
        │  turn-complete callback
        │  (PromptOrchestratorCallbacks)
        │
┌───────▼────────────────────────────────────────────────────────┐
│                    TurnMiner Pipeline                           │
│                                                                 │
│  1. Extract Q+A pairs from EntryData.Prompt + EntryData.Text    │
│  2. Quality filter (skip < 200 chars, skip pure tool output)    │
│  3. Classify: decisions / preferences / milestones / problems   │
│  4. Detect room (topic) via keyword scoring                     │
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
│    - kg_add(subject, predicate, object)                         │
│    - kg_query(entity, as_of, direction)                         │
│    - kg_invalidate(subject, predicate, object, ended)           │
│    - kg_timeline(entity)                                        │
│    - kg_stats()                                                 │
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
│    memory_diary_write  — per-agent session diary                 │
│    memory_diary_read   — read agent's diary entries              │
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
      config.json           ← Optional per-project memory config overrides

~/.agentbridge/
  models/
    all-MiniLM-L6-v2/
      model.onnx            ← Downloaded on first use (~90MB)
      vocab.txt             ← WordPiece vocabulary (~232KB)
```

---

## Package Structure

```
plugin-core/src/main/java/com/github/catatafishen/agentbridge/
  memory/
    MemorySettings.java              ← PersistentStateComponent (opt-in toggle, config)
    MemorySettingsConfigurable.java  ← Settings UI panel
    MemoryService.java               ← Project-level service (lifecycle, Disposable)

    store/
      MemoryStore.java               ← Lucene index wrapper (add, search, delete, status)
      DrawerDocument.java            ← POJO for a single memory drawer
      MemoryQuery.java               ← Query builder (semantic + metadata filters)

    embedding/
      EmbeddingService.java          ← ONNX Runtime session management + inference
      WordPieceTokenizer.java        ← Java tokenizer (vocab.txt → token IDs)
      ModelDownloader.java           ← Download model on first use with progress

    mining/
      TurnMiner.java                 ← Entry point: extract memories from a turn's entries
      ExchangeChunker.java           ← Q+A pair chunking (from convo_miner.py)
      MemoryClassifier.java          ← 5-type regex classification (from general_extractor.py)
      RoomDetector.java              ← Topic detection via keyword scoring (from convo_miner.py)
      QualityFilter.java             ← Skip low-value content (short, tool-only, etc.)

    kg/
      KnowledgeGraph.java            ← SQLite triple store (from knowledge_graph.py)
      KgTriple.java                  ← Triple POJO (subject, predicate, object, validity)

    layers/
      MemoryStack.java               ← Unified 4-layer interface (from layers.py)
      IdentityLayer.java             ← L0: read identity.txt
      EssentialStoryLayer.java       ← L1: top drawers from Lucene, grouped by room
      OnDemandLayer.java             ← L2: wing/room filtered retrieval
      DeepSearchLayer.java           ← L3: full semantic search

    wal/
      WriteAheadLog.java             ← JSONL audit log for all write operations

  psi/tools/memory/
    MemorySearchTool.java            ← MCP tool: semantic search
    MemoryStatusTool.java            ← MCP tool: palace overview
    MemoryStoreTool.java             ← MCP tool: file content into wing/room
    MemoryWakeUpTool.java            ← MCP tool: L0+L1 wake-up context
    MemoryRecallTool.java            ← MCP tool: L2 on-demand retrieval
    MemoryKgQueryTool.java           ← MCP tool: knowledge graph query
    MemoryKgAddTool.java             ← MCP tool: add KG triple
    MemoryKgInvalidateTool.java      ← MCP tool: invalidate KG triple
    MemoryKgTimelineTool.java        ← MCP tool: chronological fact history
    MemoryDiaryWriteTool.java        ← MCP tool: per-agent diary
    MemoryDiaryReadTool.java         ← MCP tool: read diary entries
```

---

## Detailed Design

### 1. Settings (Opt-In)

Following the `ChatWebServerSettings` pattern:

```java
@Service(Service.Level.PROJECT)
@State(name = "MemorySettings", storages = @Storage("agentbridgeMemory.xml"))
public final class MemorySettings implements PersistentStateComponent<MemorySettings.State> {

    public static final class State {
        public boolean enabled = false;           // Opt-in — disabled by default
        public boolean autoMineOnTurnComplete = true;
        public boolean autoMineOnSessionArchive = true;
        public int minChunkLength = 200;          // Skip entries shorter than this
        public int maxDrawersPerTurn = 10;        // Safety cap per turn
        public String palaceWing = "";            // Auto-detected from project name if empty
    }
}
```

Registered as `<projectService>` in `plugin.xml`, with a `<projectConfigurable>` UI
under `parentId="com.github.catatafishen.agentbridge.settings"`.

### 2. EmbeddingService

Manages the ONNX Runtime session and produces 384-dim float embeddings.

```java
public final class EmbeddingService implements Disposable {

    private static final String MODEL_DIR = "all-MiniLM-L6-v2";
    private static final int EMBEDDING_DIM = 384;
    private static final int MAX_SEQ_LENGTH = 256;  // all-MiniLM-L6-v2 max tokens

    private OrtEnvironment env;
    private OrtSession session;
    private WordPieceTokenizer tokenizer;

    /** Lazy init — downloads model on first call if needed. */
    public float[] embed(String text) { ... }

    /** Batch embedding for mining pipeline efficiency. */
    public List<float[]> embedBatch(List<String> texts) { ... }
}
```

**Model download**: `ModelDownloader` checks `~/.agentbridge/models/all-MiniLM-L6-v2/`
on first use. If missing, downloads from Hugging Face with a progress indicator in the
IDE status bar. The download is ~90MB (model) + ~232KB (vocab) — one-time cost.

**WordPiece tokenizer**: Pure Java implementation. Loads `vocab.txt`, performs:
1. Basic text cleaning (lowercase, Unicode normalization)
2. WordPiece tokenization (greedy longest-match against vocab)
3. Add `[CLS]` / `[SEP]` special tokens
4. Pad/truncate to `MAX_SEQ_LENGTH`
5. Create attention mask and token type IDs
6. Return as ONNX tensors (`input_ids`, `attention_mask`, `token_type_ids`)

### 3. MemoryStore (Lucene)

Wraps a Lucene index at `.agent-work/memory/lucene-index/`:

```java
public final class MemoryStore implements Disposable {

    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;

    /** Add a drawer with pre-computed embedding. */
    public String addDrawer(String wing, String room, String content,
                            float[] embedding, Map<String, String> metadata) { ... }

    /** Semantic search with optional wing/room/type filters. */
    public List<SearchResult> search(String queryText, float[] queryEmbedding,
                                      String wing, String room, int limit) { ... }

    /** Get drawer counts grouped by wing and room. */
    public Map<String, Map<String, Integer>> getTaxonomy() { ... }

    /** Check if content already exists (duplicate detection). */
    public boolean isDuplicate(float[] embedding, float threshold) { ... }

    /** Get top N drawers by recency for L1 essential story. */
    public List<DrawerDocument> getTopDrawers(String wing, int maxDrawers) { ... }
}
```

**Drawer ID generation** (from MemPalace):
```java
String id = "drawer_" + wing + "_" + room + "_" +
    sha256((wing + room + content.substring(0, Math.min(100, content.length())))
        .getBytes(UTF_8)).substring(0, 24);
```

**Duplicate detection** (from MemPalace `tool_check_duplicate`): Before adding, run a
KNN query with `threshold=0.9`. If any result exceeds the threshold, skip (idempotent).

### 4. TurnMiner Pipeline

Translates MemPalace's `convo_miner.py` and `general_extractor.py` into a pipeline
that runs on turn completion:

```java
public final class TurnMiner {

    private final EmbeddingService embedding;
    private final MemoryStore store;
    private final KnowledgeGraph kg;
    private final WriteAheadLog wal;

    /**
     * Mine a completed turn's entries into the memory store.
     * Called from PromptOrchestratorCallbacks on a pooled thread.
     */
    public CompletableFuture<MineResult> mineTurn(List<EntryData> entries,
                                                   String sessionId,
                                                   String agent) { ... }
}
```

**Pipeline steps** (per turn):

1. **Extract Q+A pairs**: Pair each `EntryData.Prompt` with the following
   `EntryData.Text` entries (concatenated). This maps to MemPalace's
   `chunk_exchanges()` from `convo_miner.py`.

2. **Quality filter** (`QualityFilter`):
   - Skip if combined Q+A text < `minChunkLength` (default 200 chars)
   - Skip if content is purely tool call results (no human-readable insight)
   - Skip if content is a status message or nudge
   - Cap at `maxDrawersPerTurn` drawers per turn

3. **Classify** (`MemoryClassifier`): Port of `general_extractor.py`. Score each
   chunk against regex marker sets for 5 memory types:
   - `decision` — "we went with X because Y", "let's use", "instead of"
   - `preference` — "I prefer", "always use", "never do"
   - `milestone` — "it works", "finally", "breakthrough", "shipped"
   - `problem` — "bug", "broken", "root cause", "workaround"
   - `technical` — (replaces MemPalace's "emotional") code, architecture, patterns

   Includes disambiguation: resolved problems → milestones, etc.

4. **Detect room** (`RoomDetector`): Port of `detect_convo_room()` from
   `convo_miner.py`. Keyword scoring against topic categories (technical,
   architecture, planning, decisions, problems → default "general").

5. **Generate embeddings**: Batch-embed all chunks via `EmbeddingService`.

6. **Store**: Write to Lucene index via `MemoryStore.addDrawer()`. Duplicate
   detection prevents re-filing identical content.

7. **WAL**: Log every write to `write_log.jsonl` before execution.

### 5. MemoryStack (4-Layer)

Direct translation of MemPalace's `layers.py`:

| Layer | Source | Java Class | Budget |
|---|---|---|---|
| L0 — Identity | `identity.txt` | `IdentityLayer` | ~100 tokens |
| L1 — Essential Story | Top drawers from Lucene | `EssentialStoryLayer` | ~500-800 tokens |
| L2 — On-Demand | Wing/room filtered Lucene get | `OnDemandLayer` | ~200-500 per call |
| L3 — Deep Search | Full KNN semantic search | `DeepSearchLayer` | Unlimited |

**Wake-up** (L0 + L1): ~600-900 tokens. Injected into the system prompt on
`session/new` or available via the `memory_wake_up` MCP tool.

**L1 generation** (from `layers.py` `Layer1.generate()`): Fetch all drawers for the
current project wing, sort by recency (filed_at), take top 15, group by room, format
as compact text with 200-char snippets. Hard cap at 3200 characters (~800 tokens).

### 6. Knowledge Graph

Direct translation of MemPalace's SQLite knowledge graph:

```sql
CREATE TABLE triples (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    subject     TEXT NOT NULL,
    predicate   TEXT NOT NULL,
    object      TEXT NOT NULL,
    valid_from  TEXT,          -- ISO 8601 date
    valid_until TEXT,          -- ISO 8601 date (NULL = still valid)
    source_closet TEXT,        -- drawer ID that sourced this fact
    created_at  TEXT NOT NULL  -- ISO 8601 timestamp
);

CREATE INDEX idx_subject ON triples(subject);
CREATE INDEX idx_object ON triples(object);
CREATE INDEX idx_predicate ON triples(predicate);
```

**Input validation** (from `config.py`): `sanitize_name()` for entity names —
max 128 chars, safe characters only, no path traversal. `sanitize_content()` for
drawer content — max 100K chars, no null bytes.

### 7. MCP Tools

Priority-ordered subset of MemPalace's 19 tools, adapted for coding context:

| Priority | Tool | Read/Write | MemPalace Equivalent |
|---|---|---|---|
| P0 | `memory_search` | Read | `mempalace_search` |
| P0 | `memory_store` | Write | `mempalace_add_drawer` |
| P0 | `memory_status` | Read | `mempalace_status` |
| P1 | `memory_wake_up` | Read | Wake-up from `layers.py` |
| P1 | `memory_recall` | Read | L2 from `layers.py` |
| P1 | `memory_diary_write` | Write | `mempalace_diary_write` |
| P1 | `memory_diary_read` | Read | `mempalace_diary_read` |
| P2 | `memory_kg_query` | Read | `mempalace_kg_query` |
| P2 | `memory_kg_add` | Write | `mempalace_kg_add` |
| P2 | `memory_kg_invalidate` | Write | `mempalace_kg_invalidate` |
| P2 | `memory_kg_timeline` | Read | `mempalace_kg_timeline` |

Tools are registered in the standard `psi/tools/` infrastructure, conditional on
`MemorySettings.enabled`. They appear as `agentbridge-memory_search`, etc. to clients.

### 8. Hook Points

| Event | Hook Location | Action |
|---|---|---|
| Turn complete | `PromptOrchestratorCallbacks` | Mine the turn's entries (async, pooled thread) |
| Session archive | `SessionStoreV2.finaliseCurrentSession()` | Mine any un-mined entries from the session |
| Agent switch | `ActiveAgentManager.switchListeners` | Same as session archive |
| Project open | `MemoryService.projectOpened()` | Initialize Lucene index, lazy-load model |
| Project close | `MemoryService.dispose()` | Flush pending writes, close Lucene index |

**Per-turn mining** runs asynchronously on `AppExecutorUtil.getAppExecutorService()`
(same pattern as `SessionStoreV2.appendEntriesAsync()`). The user's IDE is never
blocked. Mining happens in the pause between the agent finishing and the user typing
the next prompt — idle CPU time.

---

## Per-Turn Auto-Mining

### Why Per-Turn Works Well

Mining after each turn is the sweet spot for our use case:

1. **Idle CPU time**: After the agent finishes, the CPU is idle until the user types
   the next prompt. Mining a single turn takes <500ms (embedding + Lucene write).
2. **No batch lag**: If we only mine on session archive, a long session accumulates
   hundreds of turns that all need mining at once — visible latency.
3. **Immediate availability**: Facts from turn N are searchable by turn N+1.
4. **Natural dedup**: Each turn is mined exactly once, no need to track "last mined
   offset" across sessions.

### Quality Filter (Avoiding Noise)

Not every turn is worth mining. The quality filter (adapted from MemPalace's
`MIN_CHUNK_SIZE = 30` with our coding-specific additions):

| Rule | Threshold | Rationale |
|---|---|---|
| Minimum Q+A length | 200 chars | Short exchanges ("fix the typo" → "done") have no recall value |
| Skip pure tool output | Content is >80% tool calls with no text | Tool results are ephemeral; the decision to use a tool matters more |
| Skip status/nudge entries | `EntryData.Status`, `EntryData.Nudge` | Transient UI entries with no knowledge value |
| Max drawers per turn | 10 | Safety cap to prevent runaway mining on very long turns |
| Duplicate threshold | 0.9 cosine similarity | Prevent re-filing near-identical content |

### Cost Analysis

| Operation | Time | Frequency |
|---|---|---|
| Extract Q+A pairs from entries | <1ms | Per turn |
| Classify + detect room (regex) | <5ms | Per chunk |
| Embed one chunk (ONNX) | ~50-100ms | Per chunk |
| Lucene write + commit | ~10ms | Per chunk |
| **Total per turn (avg 2-3 chunks)** | **~200-400ms** | Per turn |

This is imperceptible to the user, especially since it runs on a background thread
during their think time.

---

## Implementation Phases

### Phase N1 — Foundation (~3-5 days)

**Goal**: MemoryStore + EmbeddingService working end-to-end.

Tasks:
- [ ] `MemorySettings` + `MemorySettingsConfigurable` (opt-in toggle, settings UI)
- [ ] `EmbeddingService` with ONNX Runtime + all-MiniLM-L6-v2
- [ ] `WordPieceTokenizer` (pure Java, loads `vocab.txt`)
- [ ] `ModelDownloader` (download model on first use with progress)
- [ ] `MemoryStore` (Lucene index: add drawer, semantic search, taxonomy, dedup)
- [ ] `WriteAheadLog` (JSONL audit logging)
- [ ] `MemoryService` (project-level lifecycle service, Disposable)
- [ ] Add `com.microsoft.onnxruntime:onnxruntime` dependency to `build.gradle.kts`
- [ ] Register services in `plugin.xml`
- [ ] Unit tests for tokenizer, embedding, store, and search

### Phase N2 — Mining Pipeline (~2-3 days)

**Goal**: Automatic turn mining with classification.

Tasks:
- [ ] `ExchangeChunker` (extract Q+A pairs from EntryData list)
- [ ] `MemoryClassifier` (5-type regex classification, ported from `general_extractor.py`)
- [ ] `RoomDetector` (topic detection, ported from `convo_miner.py`)
- [ ] `QualityFilter` (skip low-value content)
- [ ] `TurnMiner` (pipeline orchestrator)
- [ ] Hook into `PromptOrchestratorCallbacks` for per-turn mining
- [ ] Hook into `SessionStoreV2.finaliseCurrentSession()` for archive mining
- [ ] Unit tests for chunking, classification, room detection

### Phase N3 — MCP Tools (~2-3 days)

**Goal**: Agents can search and write to memory.

Tasks:
- [ ] P0 tools: `MemorySearchTool`, `MemoryStoreTool`, `MemoryStatusTool`
- [ ] P1 tools: `MemoryWakeUpTool`, `MemoryRecallTool`, `MemoryDiaryWriteTool`, `MemoryDiaryReadTool`
- [ ] Conditional tool registration (only when memory is enabled)
- [ ] Tool descriptions optimized for agent discovery
- [ ] Integration with existing tool categories and agent definitions

### Phase N4 — Knowledge Graph + Wake-Up (~2-3 days)

**Goal**: Structured facts + automatic context injection.

Tasks:
- [ ] `KnowledgeGraph` (SQLite triple store with temporal validity)
- [ ] `KgTriple` POJO + input validation (ported from `config.py`)
- [ ] P2 tools: `MemoryKgQueryTool`, `MemoryKgAddTool`, `MemoryKgInvalidateTool`, `MemoryKgTimelineTool`
- [ ] `MemoryStack` (4-layer unified interface)
- [ ] `IdentityLayer`, `EssentialStoryLayer`, `OnDemandLayer`, `DeepSearchLayer`
- [ ] Wake-up context injection on `session/new`
- [ ] Unit tests for KG operations and layer rendering

### Phase N5 — Polish + Agent Definitions (~1-2 days)

**Goal**: Agent definitions updated, cross-project support.

Tasks:
- [ ] Update Copilot/OpenCode/Kiro agent definitions with memory tools
- [ ] Add memory tools to Junie startup instructions
- [ ] Cross-project memory support (global `~/.agentbridge/memory/` + per-project)
- [ ] Backfill existing sessions (optional one-time migration)
- [ ] Documentation and ATTRIBUTION.md

---

## Gradle Dependency Addition

```kotlin
// In plugin-core/build.gradle.kts
dependencies {
    // ONNX Runtime for embedding model inference
    implementation("com.microsoft.onnxruntime:onnxruntime:1.24.3")
}
```

No other new dependencies needed — Lucene is bundled with IntelliJ, SQLite JDBC is
already present.

---

## Risks & Mitigations

### 1. ONNX Runtime Size (~60MB)

The ONNX Runtime JAR includes native libraries for all platforms. This significantly
increases the plugin download size.

**Mitigation**: The model file (~90MB) is downloaded separately on first use, not
bundled with the plugin. The ONNX Runtime JAR could potentially be downloaded on
demand as well, or we could use platform-specific classifier JARs to reduce size.

### 2. IntelliJ Lucene Version Changes

We depend on IntelliJ's bundled Lucene. A major version bump could break our code.

**Mitigation**: Our Lucene usage is limited to basic document indexing and KNN queries.
These APIs have been stable since Lucene 9. If needed, we can bundle our own Lucene
JAR (~3MB) with a classloader-isolated dependency.

### 3. First-Use Model Download

Users need to download ~90MB on first enable. Poor network = poor first experience.

**Mitigation**: Show a progress indicator in the IDE status bar. Allow cancellation.
Cache the model user-global (`~/.agentbridge/models/`) so it's downloaded once per
machine, not per project. Consider bundling the model in a separate plugin artifact.

### 4. Embedding Quality for Code

all-MiniLM-L6-v2 is trained on natural language, not code. Code-heavy content may
not embed as well as prose.

**Mitigation**: The mining pipeline extracts *prose* from conversations (decisions,
explanations, problems) — not raw code. The `QualityFilter` and `MemoryClassifier`
ensure we store human-readable content. For code-specific search, the existing
`search_text` and `search_symbols` tools remain available.

### 5. Storage Growth

A heavy user might accumulate thousands of drawers over months.

**Mitigation**: Lucene indexes are compact (~1KB per drawer including embeddings).
10,000 drawers ≈ ~10MB. The `maxDrawersPerTurn` cap prevents runaway growth. We can
add retention policies later (e.g., age-based cleanup, importance-based pruning).

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
| `IdentityLayer` | `layers.py` `Layer0` | Java port, same file format |
| `EssentialStoryLayer` | `layers.py` `Layer1` | Java port, Lucene queries instead of ChromaDB |
| `OnDemandLayer` | `layers.py` `Layer2` | Java port |
| `DeepSearchLayer` | `layers.py` `Layer3` | Java port, Lucene KNN instead of ChromaDB |
| `KnowledgeGraph` | `knowledge_graph.py` | Java port, same SQLite schema |
| `WriteAheadLog` | `mcp_server.py` `_wal_log()` | Java port, same JSONL format |
| MCP tool designs | `mcp_server.py` `TOOLS` dict | Subset, adapted for our tool infrastructure |
| Input validation | `config.py` `sanitize_name/content()` | Java port, same regex + length constraints |
| Drawer ID generation | `mcp_server.py` `tool_add_drawer()` | Java port, same SHA-256 scheme |

Attribution will also be noted in:
- Source file headers for ported classes
- The plugin's NOTICE file
- The settings UI description ("Semantic memory powered by concepts from MemPalace")
