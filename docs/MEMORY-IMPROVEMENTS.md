# Memory System Improvement Plan

Improvement plan for the semantic memory tools (drawers, knowledge graph, recall).
Based on analysis of the mining pipeline, content quality, and tool outputs after
the first full backfill run.

## Current Architecture

```
Raw conversation (EntryData list)
  → ExchangeChunker     (pair prompts with responses, extract tool evidence)
  → QualityFilter        (reject short/status/tool-heavy exchanges)
  → MemoryClassifier     (classify type: decision/problem/solution/context)
  → RoomDetector         (classify room: codebase/debugging/workflow/decisions/preferences)
  → EvidenceExtractor    (extract file paths, commit SHAs from tool results)
  → EmbeddingService     (all-MiniLM-L6-v2, 384-dim, 256-token limit)
  → MemoryStore          (Lucene KNN index, 0.9 cosine dedup)
  → TripleExtractor      (10 regex rules → subject/predicate/object)
  → KnowledgeGraph       (SQLite with temporal validity)
```

## What Works Well

- **Thinking entries excluded** — `EntryData.Thinking` is skipped by `ExchangeChunker`
- **Nudges excluded** — `EntryData.Nudge` is properly filtered out
- **Semantic dedup** — 0.9 cosine threshold catches near-duplicate drawers
- **Evidence propagation** — triples carry file/commit evidence from source drawers
- **Temporal KG model** — `valid_from`/`valid_until` enables fact evolution tracking
- **Good embedding model** — all-MiniLM-L6-v2 is well-suited for semantic similarity
- **Room/type orthogonality** — separating "where to look" from "what kind" is sound
- **Memory search** — KNN vector search with rich context in results

---

## P0 — Agent Narration Noise

**Problem**: Agent operational text ("I'll use the read_file tool...", "Let me search
for the implementation...") is stored as drawer content and fed to triple extraction.
This produces bogus triples like `(project, uses, the read_file tool)` and pollutes
search results with navigational noise rather than substantive knowledge.

**Root cause**: `ExchangeChunker` includes the full assistant response without
distinguishing operational narration from substantive content.

### Fix: Pre-filter agent narration in ExchangeChunker

Add a `NarrationFilter` that strips common agent operational patterns from
response text before it enters the pipeline:

```
Patterns to strip (line-level):
- "I'll/Let me/I need to [verb] the [tool/file]..."
- "Now I'll/Next I'll/First I'll..."
- "Looking at/Checking/Reading/Searching..."
- "The output shows/I can see that..."
- "Here's what I found/Let me explain..."
- Lines that are pure tool-call narration (match known MCP tool names)
```

**Implementation**: New class `NarrationFilter` in `memory/mining/`. Applied in
`ExchangeChunker.appendResponseText()` or as a pipeline step between chunking
and quality filtering. Strip matching lines, then collapse consecutive newlines.

**Scope**: ~100 lines. Regex-based, same approach as `QualityFilter`.

**Risk**: Over-filtering could remove legitimate content. Use conservative patterns
that specifically target agent MCP tool narration, not general conversational text.

---

## P0 — Tool Evidence Noise in Embeddings and Triples

**Problem**: Tool result fragments (up to 500 chars) are appended to response text
by `ExchangeChunker.appendToolResultEvidence()`. These raw search outputs, file
listings, and outlines flow into embeddings and triple extraction, adding noise.

**Root cause**: Evidence extraction (file paths, commit SHAs) is entangled with
content enrichment. The evidence metadata is correctly extracted by `EvidenceExtractor`,
but the raw tool output text is *also* concatenated into the response.

### Fix: Separate evidence metadata from content text

1. **Stop appending tool result text to response**. In `ExchangeChunker`, extract
   file paths and commit SHAs into the `Exchange` metadata fields only — don't
   concat raw output into `responseBuilder`.

2. **Keep tool call *names* for classification signal** but not their output.
   E.g., knowing the exchange involved `search_text` and `edit_file` helps
   `RoomDetector` (→ codebase), but the actual search results are noise.

**Implementation**: Modify `ExchangeChunker.appendToolResultEvidence()`:
- Extract evidence metadata (paths, SHAs) as today → store in `Exchange` fields
- Remove the `responseBuilder.append("[").append(toolName)...` block
- Optionally add a `toolNames` field to `Exchange` for classification signal

**Scope**: ~20 lines changed in `ExchangeChunker`.

---

## P1 — Triple Extraction Quality

### Negation handling

**Problem**: "Never use eval()" → `(project, uses, eval())`. Regex patterns don't
detect negation words before the trigger.

**Fix**: Add negation guard to each extraction rule. Before extracting, check if the
sentence contains a negation modifier within N words of the trigger:

```java
private static final Pattern NEGATION = Pattern.compile(
    "\\b(not|never|don't|doesn't|shouldn't|avoid|stop|no longer|removed)\\b",
    Pattern.CASE_INSENSITIVE);
```

If negation is detected, either skip the triple or invert the predicate
(e.g., `uses` → `avoids`). Start with skip — inversion is harder to get right.

### Object over-capture

**Problem**: Object capture groups grab the rest of the sentence. "We implemented a
fix for the failing test which broke CI" → object is the entire tail.

**Fix**: Tighten `MAX_OBJECT_WORDS` from 10 to 6, and add a stop-word boundary:
objects ending with prepositions ("for", "with", "in", "to", "from", "by", "on")
should be trimmed at that preposition.

### Cross-run dedup

**Problem**: Same triple extracted repeatedly from different mining runs. No
uniqueness constraint in SQLite.

**Fix**: Add `UNIQUE(subject, predicate, object)` constraint (ignoring case) on the
`triples` table. On conflict, update `evidence` to merge evidence arrays rather
than inserting a duplicate row. Migration:

```sql
-- Deduplicate existing triples, keeping the one with the most evidence
DELETE FROM triples WHERE id NOT IN (
    SELECT MIN(id) FROM triples
    WHERE valid_until IS NULL
    GROUP BY LOWER(subject), LOWER(predicate), LOWER(object)
);
ALTER TABLE triples ADD CONSTRAINT ... -- SQLite doesn't support ALTER ADD CONSTRAINT;
-- Recreate table with constraint instead
```

---

## P1 — Embedding Quality

### Markdown stripping before embedding

**Problem**: Raw markdown (code blocks, headers, bold markers, URLs) goes into the
embedding model. The `stripMarkdown()` preprocessing used for triple extraction
is NOT applied before embedding.

**Fix**: Apply `TripleExtractor.stripMarkdown()` (or a shared utility version) to
the combined text before passing it to `EmbeddingService.embed()`. Extract the
method to a shared `TextPreprocessor` utility class.

### Token budget allocation

**Problem**: 256-token limit means the prompt often consumes the entire token budget,
and the response (where the actual insight lives) is truncated.

**Fix**: Two options (not mutually exclusive):
1. **Response-first embedding**: Feed `response + "\n" + prompt` instead of
   `prompt + "\n\n" + response`. The response typically contains the insight;
   the prompt is context.
2. **Increase token limit to 512**: The model supports it; inference time roughly
   doubles but is still fast enough for background mining. Benchmark before
   committing.
3. **Separate embeddings**: Embed prompt and response independently, store both
   vectors. Search can match against either. More complex but higher recall.

Recommended: Start with option 1 (response-first), measure quality improvement.
If insufficient, add option 2.

---

## P1 — Wake-up Quality

### Room diversity

**Problem**: Wake-up uses pure recency — if the last 15 drawers are all from one
debugging session, the wake-up context is entirely debugging with no
codebase/preference/decision coverage.

**Fix**: Select top N drawers *per room* rather than globally. E.g., top 3 from
each of {codebase, workflow, debugging, decisions, preferences}, capped at 15
total. Rooms with fewer drawers contribute less.

```java
Map<String, List<DrawerDocument>> byRoom = allDrawers.stream()
    .collect(Collectors.groupingBy(DrawerDocument::room));
List<DrawerDocument> diverse = byRoom.values().stream()
    .flatMap(drawers -> drawers.stream().limit(MAX_PER_ROOM))
    .sorted(Comparator.comparing(DrawerDocument::filedAt).reversed())
    .limit(MAX_DRAWERS)
    .toList();
```

### Smarter snippets

**Problem**: 200-char truncation often shows the user's question, not the answer.

**Fix**: Extract the first substantive sentence from the *response* portion (not
the combined text). If the drawer content starts with the prompt, skip to after
the first `\n\n` (the response boundary) before taking the snippet.

---

## P1 — Classification Improvements

### Weighted keywords

**Problem**: Simple keyword counting with equal weights. "Interface" scores the
same as "refactored" for the codebase room, but "refactored" is a much stronger
signal.

**Fix**: Assign weights to keywords. Core vocabulary (e.g., "refactored", "debugger",
"deployment") gets weight 2; generic terms (e.g., "code", "file", "data") get
weight 1 or 0.5. Use weighted sum instead of count.

### Confidence threshold

**Problem**: A single keyword match is enough to classify. An exchange mentioning
"build" once gets classified as "workflow" even if it's really about architecture.

**Fix**: Add a minimum score threshold (e.g., 3 weighted points). Below threshold,
classify as `general` rather than the highest-scoring room.

---

## P2 — KG Schema and Naming

### Rename `source_closet` → `source_drawer`

The column is named `source_closet` (from the original MemPalace "closet"
terminology) but the Java code uses `sourceDrawer`. Rename for consistency.

### Add compound index

```sql
CREATE INDEX idx_triples_subj_pred_valid
ON triples(subject, predicate, valid_until);
```

This covers the most common query pattern (filter by subject+predicate, exclude
invalidated).

---

## P2 — Dedup Tuning

### Content-length-aware threshold

**Problem**: Fixed 0.9 cosine threshold behaves differently for short vs. long
content. Short texts cluster more tightly in embedding space.

**Fix**: Adjust threshold based on content length:
- Content < 100 chars: threshold 0.95 (stricter — short texts need higher similarity)
- Content 100–500 chars: threshold 0.90 (current default)
- Content > 500 chars: threshold 0.85 (more lenient — long texts diverge more)

---

## Implementation Order

| Phase | Items | Estimated Scope |
|-------|-------|-----------------|
| 1 | Agent narration filter + tool evidence separation | ~150 lines new code |
| 2 | Triple negation guard + object trimming + cross-run dedup | ~80 lines changed |
| 3 | Embedding: markdown strip + response-first ordering | ~30 lines changed |
| 4 | Wake-up room diversity + smarter snippets | ~50 lines changed |
| 5 | Classification weights + confidence threshold | ~40 lines changed |
| 6 | KG schema fixes (rename, index, unique constraint) | ~30 lines migration |

Phase 1 and 2 address the biggest quality issues (noisy content and bogus triples).
Phase 3 and 4 improve recall and context quality. Phase 5 and 6 are refinements.

After each phase, re-mine history and compare drawer/triple quality against a
baseline sample to validate improvement.
