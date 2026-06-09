# Knowledge Graph UI — Wireframe & Design Plan

## Status Quo

The current Knowledge Graph panel (`KnowledgeGraphPanel.java`) is a single Swing panel with:

- Feature toggle checkbox
- Auto-refresh checkbox
- Monospaced text area showing raw stats (nodes/edges/files/commits/timestamp)
- Rebuild + Export JSON buttons
- A status label

**Problems**: no visual exploration, stats feel like a debug dump, no way to understand
the graph's content without exporting JSON and using an external tool.

---

## Proposed Architecture

### Technology Choice: JCEF + Cytoscape.js

The plugin already uses JCEF (Chromium Embedded Framework) for the chat panel. We'll
reuse the same pattern for an interactive graph visualization:

- **Cytoscape.js** — purpose-built graph visualization library with layouts, pan/zoom,
  node selection, search, and edge bundling
- **Swing toolbar** — native IntelliJ toolbar above the JCEF panel for settings & actions
- **Java↔JS bridge** — same `JBCefJSQuery` pattern used by ChatConsolePanel

> **Why not IntelliJ Diagram API?** The `com.intellij.diagram` module is only available
> in Ultimate editions that include the UML plugin. It's not in our SDK classpath. JCEF
> works everywhere and gives us more control over the visualization.

---

## Tool Window Layout

```
┌─────────────────────────────────────────────────────────────┐
│ Knowledge Graph                                    [⚙][↻][⤓]│
├─────────────────────────────────────────────────────────────┤
│ [Dashboard] [Graph] [Explorer]                     ← tabs   │
╞═════════════════════════════════════════════════════════════╡
│                                                             │
│                    (tab content area)                        │
│                                                             │
└─────────────────────────────────────────────────────────────┘

⚙ = Settings gear (opens settings popover)
↻ = Rebuild action  
⤓ = Export dropdown (JSON, SVG, PNG)
```

---

## Tab 1: Dashboard

Overview cards + activity feed. Pure Swing (no JCEF needed).

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐      │
│  │  1,247   │ │  3,891   │ │   156    │ │   842    │      │
│  │  Nodes   │ │  Edges   │ │  Files   │ │ Commits  │      │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘      │
│                                                             │
│  Status: ● Active — last indexed 2 min ago                  │
│  Tool: query_knowledge_graph ✓ advertised to agents         │
│                                                             │
│  ┌─ Top Hotspots ──────────────────────────────────────┐   │
│  │  ████████████ AuthService.java         (23 deps)    │   │
│  │  █████████   UserController.java       (18 deps)    │   │
│  │  ███████     DatabaseConfig.java       (14 deps)    │   │
│  │  ██████      SessionManager.java       (12 deps)    │   │
│  │  ████        ErrorHandler.java         ( 8 deps)    │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─ Recent Activity ───────────────────────────────────┐   │
│  │  ● 2m ago  Agent edited AuthService.java            │   │
│  │  ● 5m ago  Agent read UserController.java           │   │
│  │  ● 12m ago Commit "fix: auth token refresh"         │   │
│  │  ● 15m ago Agent edited SessionManager.java         │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Data source**: `CodeGraphStore.getStats()` + SQL queries for hotspots and recent activity.

---

## Tab 2: Graph (JCEF + Cytoscape.js)

Interactive dependency visualization. This is the main new feature.

```
┌─────────────────────────────────────────────────────────────┐
│ ┌─ Toolbar ────────────────────────────────────────────────┐│
│ │ View: [Files ▾] Layout: [Force ▾] Depth: [1▾]  🔍 Search││
│ └──────────────────────────────────────────────────────────┘│
│                                                             │
│           ┌─────────┐                                       │
│           │  Auth    │←──────┐                              │
│           │ Service  │       │                              │
│           └────┬────┘       │                              │
│                │             │                              │
│       ┌────────┼────────┐   │                              │
│       ▼        ▼        ▼   │                              │
│  ┌────────┐┌────────┐┌─────┴──┐                            │
│  │ User   ││ Token  ││Session │                            │
│  │ Repo   ││ Store  ││Manager │                            │
│  └────────┘└────────┘└────────┘                            │
│       │                   │                                 │
│       ▼                   ▼                                 │
│  ┌────────┐         ┌────────┐                              │
│  │Database│         │ Redis  │                              │
│  │ Config │         │ Client │                              │
│  └────────┘         └────────┘                              │
│                                                             │
│ ┌─ Legend ──────────────────────────────────────────────────┐│
│ │ 🟦 Java  🟩 Kotlin  🟨 Config  ── uses  ╌╌ contains     ││
│ └──────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### Graph Features

| Feature              | Description                                                                      |
|----------------------|----------------------------------------------------------------------------------|
| **View modes**       | Files (default), Packages (aggregate), Symbols (class/method level)              |
| **Layouts**          | Force-directed (default), Hierarchical (top-down), Concentric (centrality), Grid |
| **Filtering**        | By package path, language, edit recency, dependency count                        |
| **Depth control**    | How many hops from selected node to show (1-5)                                   |
| **Search**           | Fuzzy file/class name search, highlights matching nodes                          |
| **Click → navigate** | Double-click node opens file in editor (via JS→Java bridge)                      |
| **Hover tooltip**    | Shows: kind, FQN, language, #dependents, #dependencies, last commit              |
| **Color coding**     | By language (default), by edit frequency (heat), by author                       |
| **Edge types**       | Solid = `uses`, dashed = `contains`, thickness = call frequency                  |
| **Right-click menu** | "Show dependents", "Show dependencies", "File history", "Open file"              |

### Interaction Flows

**Focus on a file:**

1. User searches "AuthService" in the search box
2. Graph centers on AuthService node, highlights it
3. Shows 1 hop of dependencies/dependents by default
4. User increases depth slider → graph expands outward

**Impact analysis:**

1. User right-clicks a node → "Show impact blast radius"
2. Graph shows all transitive dependents, colored by distance
3. Sidebar shows: "18 files depend on this (2 transitively)"

---

## Tab 3: Explorer

Table/tree view for searching and browsing graph data. Pure Swing.

```
┌─────────────────────────────────────────────────────────────┐
│ ┌─ Filter bar ─────────────────────────────────────────────┐│
│ │ [🔍 Search files...        ] [Kind ▾] [Language ▾]       ││
│ └──────────────────────────────────────────────────────────┘│
│                                                             │
│ ┌─ Results ────────────────────────────────────────────────┐│
│ │ File                    │ Deps │ Dependents │ Commits    ││
│ │─────────────────────────│──────│────────────│────────────││
│ │ AuthService.java        │   5  │     23     │  47        ││
│ │ UserController.java     │   8  │     18     │  31        ││
│ │ DatabaseConfig.java     │   2  │     14     │  12        ││
│ │ SessionManager.java     │   4  │     12     │  28        ││
│ │ TokenStore.java         │   3  │      9     │  15        ││
│ │ ...                     │      │            │            ││
│ └──────────────────────────────────────────────────────────┘│
│                                                             │
│ ┌─ Detail panel (on row select) ───────────────────────────┐│
│ │ AuthService.java                                         ││
│ │ Kind: file  Language: Java  FQN: com.example.auth        ││
│ │                                                          ││
│ │ Dependencies (5):          Dependents (23):              ││
│ │   → UserRepository.java     ← UserController.java       ││
│ │   → TokenStore.java         ← AdminController.java      ││
│ │   → CryptoUtils.java        ← ApiGateway.java           ││
│ │   → RedisClient.java        ← ...                       ││
│ │   → AppConfig.java                                       ││
│ │                                                          ││
│ │ Recent commits:                                          ││
│ │   abc123d  2h ago  "fix: token refresh logic" (alice)    ││
│ │   def456a  1d ago  "feat: add MFA support" (bob)         ││
│ └──────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

---

## Settings Popover (⚙ button)

Replaces the current inline checkboxes. Opens as a popup panel.

```
┌─ Knowledge Graph Settings ─────────────────┐
│                                            │
│  ☑ Enable Knowledge Graph                  │
│  ☑ Auto-refresh after agent edits          │
│  ☐ Include test files in graph             │
│  ☐ Show cross-module edges                 │
│                                            │
│  Index scope: [Source roots ▾]             │
│  Max depth for visualization: [3 ▾]        │
│                                            │
│  Database size: 2.4 MB                     │
│  [Clear graph data]                        │
│                                            │
└────────────────────────────────────────────┘
```

---

## Data Flow: Java ↔ JCEF Bridge

```
┌──────────────────┐         ┌──────────────────────────┐
│   Java/Swing     │         │   JCEF (Cytoscape.js)    │
│                  │         │                          │
│ KnowledgeGraph   │──JSON──▶│ window._bridge.loadGraph │
│ DiagramPanel     │         │   (nodes, edges, layout) │
│                  │         │                          │
│ handleNavigation │◀──msg───│ _bridge.navigateToFile   │
│   (file, line)   │         │   (path, line)           │
│                  │         │                          │
│ handleQuery      │◀──msg───│ _bridge.expandNode       │
│   (nodeId,depth) │         │   (nodeId, depth)        │
└──────────────────┘         └──────────────────────────┘
```

Bridge messages (JS → Java):

- `navigateToFile(path, line)` — open file in editor
- `expandNode(nodeId, depth)` — fetch more neighbors from SQLite
- `getNodeInfo(nodeId)` — fetch tooltip data
- `searchNodes(query)` — fuzzy search

Bridge messages (Java → JS):

- `loadGraph(nodes, edges)` — initial data load
- `addNodes(nodes, edges)` — incremental expansion
- `highlightNodes(ids)` — mark search results
- `setLayout(type)` — change layout algorithm
- `setColorScheme(scheme)` — change node coloring

---

## What Data Is Relevant to Show

### In the Dashboard

- **Aggregate stats** — node/edge/file/commit counts (already have these)
- **Health indicator** — is the graph up to date? How stale?
- **Hotspots** — top 5-10 most structurally central files (most dependents)
- **Recent activity** — last N agent actions and commits (from `tool_call_events` + `graph_commits`)

### In the Graph Visualization

- **File-level dependencies** — most useful for understanding architecture
- **Package-level aggregation** — when file-level is too noisy
- **Edge direction** — who depends on whom (crucial for impact analysis)
- **Node metadata** — language, kind, edit frequency, author
- **Temporal data** — recently changed files glow / have thicker borders

### In the Explorer

- **Searchable file list** — sorted by dependency count, commit count, or recency
- **Per-file detail** — its deps, dependents, recent commits, agent activity
- **Sortable columns** — helps find architectural risk quickly

---

## Implementation Phases

### Phase 1: Dashboard Redesign (Swing only)

- Replace text area with stat cards
- Add hotspots bar chart
- Add recent activity feed
- Move settings to gear popover
- Add toolbar actions (rebuild, export)

### Phase 2: Explorer Tab (Swing only)

- Sortable JBTable with file data
- Detail split panel
- Search/filter bar
- Click → navigate to file

### Phase 3: Graph Visualization (JCEF)

- JCEF panel with Cytoscape.js
- Load graph data from SQLite via bridge
- Implement layouts (force, hierarchical)
- Node click → navigate
- Search + highlight

### Phase 4: Interactive Features

- Right-click context menus
- Expand/collapse nodes
- Impact analysis mode
- Color scheme switching
- Export graph as SVG/PNG

---

## Open Questions

1. **Package aggregation**: Should we pre-compute package-level summaries in SQLite,
   or aggregate on-the-fly in JS? (SQLite is faster for large graphs)

2. **Graph size limits**: With 1000+ files, force-directed layout gets slow. Should we
   default to showing only the top N most-connected files and expand on demand?

3. **Live updates**: When the graph re-indexes after an agent edit, should the
   visualization update in real-time? (nice but complex)

4. **Test file visibility**: Should test files appear in the graph by default? They add
   noise but are relevant for "affected tests" queries.

---

## File Structure (Proposed)

```
ui/graph/
├── KnowledgeGraphToolWindowFactory.java   (existing — add tab support)
├── KnowledgeGraphPanel.java               (existing — refactor to Dashboard tab)
├── KnowledgeGraphDiagramPanel.java        (NEW — JCEF graph tab)  
├── KnowledgeGraphExplorerPanel.java       (NEW — table/tree explorer tab)
├── KnowledgeGraphSettings.java            (NEW — settings popover)
└── diagram/
    ├── GraphDataProvider.java             (queries SQLite, builds JSON for JS)
    ├── GraphBridgeHandler.java            (handles JS→Java messages)
    └── resources/
        ├── graph.html                     (JCEF entry point)
        ├── graph.js                       (Cytoscape.js setup + bridge)
        └── graph.css                      (styling)
```
