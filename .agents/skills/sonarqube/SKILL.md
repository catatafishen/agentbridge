---
name: sonarqube
description: >-
  Analyze and fix SonarCloud quality gate issues. Use when the user mentions SonarCloud,
  SonarQube, quality gate failures, bugs, vulnerabilities, code smells, hotspots, duplications,
  or coverage. Goal: get Reliability and Security to grade "A". Scripts below eliminate API
  guesswork — run them first to gather facts, then fix code or accept via API as appropriate.
---

# SonarQube Skill

Project key: `catatafishen_agentbridge`  
Base URL: `https://sonarcloud.io`  
Token env var: `SONAR_TOKEN` (or pass directly in Authorization header)

---

## Quick-start scripts

### Status overview (quality gate + rating summary)

```bash
PROJECT=catatafishen_agentbridge
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/qualitygates/project_status?projectKey=$PROJECT" \
  | jq '{status: .projectStatus.status, failed: [.projectStatus.conditions[] | select(.status!="OK")]}'
```

For a PR specifically:
```bash
PR=628
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/qualitygates/project_status?projectKey=$PROJECT&pullRequest=$PR" \
  | jq '{status: .projectStatus.status, failed: [.projectStatus.conditions[] | select(.status!="OK")]}'
```

### List all open issues (bugs + vulns + code smells)

```bash
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/issues/search?componentKeys=$PROJECT&issueStatuses=OPEN,CONFIRMED&ps=50&s=SEVERITY&asc=false" \
  | jq '.issues[] | {key:.key, rule:.rule, severity:.severity, component:.component, line:.line, message:.message}'
```

Filter by type:
```bash
# Bugs only
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/issues/search?componentKeys=$PROJECT&types=BUG&issueStatuses=OPEN,CONFIRMED&ps=50" \
  | jq '.issues[] | {key,rule,component,line,message}'

# Vulnerabilities only
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/issues/search?componentKeys=$PROJECT&types=VULNERABILITY&issueStatuses=OPEN,CONFIRMED&ps=50" \
  | jq '.issues[] | {key,rule,component,line,message}'

# Critical/Blocker code smells only
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/issues/search?componentKeys=$PROJECT&types=CODE_SMELL&severities=CRITICAL,BLOCKER&issueStatuses=OPEN,CONFIRMED&ps=50" \
  | jq '.issues[] | {key,rule,component,line,message}'
```

### List hotspots (TO_REVIEW)

```bash
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/hotspots/search?projectKey=$PROJECT&status=TO_REVIEW&ps=50" \
  | jq '.hotspots[] | {key:.key, rule:.ruleKey, component:.component, line:.line, message:.message}'
```

### Accept a hotspot as SAFE

```bash
HOTSPOT_KEY=AZ30mdW4PxzZiM1AQxBj
COMMENT="Safe: uses PreparedStatement with parameterized queries — no user input reaches SQL directly."
curl -s -u "$SONAR_TOKEN:" -X POST \
  "https://sonarcloud.io/api/hotspots/change_status" \
  -d "hotspot=$HOTSPOT_KEY&status=REVIEWED&resolution=SAFE&comment=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$COMMENT")"
```

Valid resolutions: `SAFE`, `FIXED`, `ACKNOWLEDGED`.

### Accept an issue as WONTFIX

```bash
ISSUE_KEY=AZ30mdW4PxzZiM1AQxBj
curl -s -u "$SONAR_TOKEN:" -X POST \
  "https://sonarcloud.io/api/issues/do_transition" \
  -d "issue=$ISSUE_KEY&transition=wontfix"
```

Valid transitions: `wontfix`, `falsepositive`, `reopen`.

### Top duplication offenders

```bash
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/measures/component_tree?component=$PROJECT&metricKeys=duplicated_lines_density,duplicated_blocks&qualifiers=FIL&s=metric&metricSort=duplicated_lines_density&asc=false&ps=15" \
  | jq '.components[] | select(.measures | length > 0) | {
      path: .path,
      duplication: (.measures[] | select(.metric=="duplicated_lines_density") | .value),
      blocks: (.measures[] | select(.metric=="duplicated_blocks") | .value // "0")
    }'
```

Get the exact duplicate blocks for one file:
```bash
FILE_KEY="catatafishen_agentbridge:plugin-core/src/main/java/com/github/catatafishen/agentbridge/settings/KiroClientConfigurable.kt"
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/duplications/show?key=$FILE_KEY" \
  | jq '{duplications: [.duplications[].blocks], files: .files}'
```

### Test coverage — worst offenders

```bash
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/measures/component_tree?component=$PROJECT&metricKeys=line_coverage,uncovered_lines,lines_to_cover&qualifiers=FIL&s=metric&metricSort=uncovered_lines&asc=false&ps=15&metricSortFilter=withMeasuresOnly" \
  | jq '.components[] | {
      path: .path,
      coverage: (.measures[] | select(.metric=="line_coverage") | .value // "0%"),
      uncovered: (.measures[] | select(.metric=="uncovered_lines") | .value // "0"),
      total: (.measures[] | select(.metric=="lines_to_cover") | .value // "0")
    }'
```

### Cognitive complexity offenders

```bash
curl -s -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/issues/search?componentKeys=$PROJECT&rules=kotlin:S3776,java:S3776&issueStatuses=OPEN,CONFIRMED&ps=50" \
  | jq '.issues[] | {component,line,message}'
```

---

## Workflow: Getting to A

### Reliability A

Requires: 0 open bugs.

1. Run bugs script above to list all open bugs.
2. For each: read the file at the flagged line. Fix the root cause (not the symptom).
3. Push and let CI re-analyze. Re-run the bugs script to verify 0 remain.

Common patterns:
- `S3077` volatile non-atomic → replace with `AtomicReference<T>`
- `S2095` resource not closed → use try-with-resources
- `S2259` null dereference → add defensive null check or restructure

### Security A

Requires: 0 open vulnerabilities + all hotspots reviewed.

1. Run vulnerabilities + hotspots scripts above.
2. For each hotspot: read the code, decide SAFE or FIXED, then call `accept-hotspot` script.
   - **SAFE**: code is fine as-is (e.g., PreparedStatement, local JCEF context).
   - **FIXED**: you've changed the code to eliminate the concern.
   - **ACKNOWLEDGED**: known risk, deferred — use sparingly.
3. For vulnerabilities: either fix the code or accept as WONTFIX with a justification comment.

Common patterns:
- `S8264` GitHub Actions workflow-level permissions → `permissions: {}` at workflow level, explicit per job
- `S8569` Gradle/Maven lockfile missing → accept WONTFIX if fast-moving project
- `S4790` weak hash → replace MD5/SHA1 with SHA-256

### Maintainability A (code smells)

Already at A. Ongoing cleanup:
- `S3776` cognitive complexity → extract sub-methods
- `S1192` duplicate string literals → extract to `private static final` constant
- `S1144` unused private method → remove or check if used via `::` reference (IDE sometimes misses these)

---

## Duplication strategy

SonarCloud's duplication metric flags exact copy-paste blocks. But when a match fires, also
consider whether the surrounding feature is a partial duplicate of nearby code:

1. Identify the flagged file and the "other" file it duplicates with (from duplications/show API).
2. Read both files fully. Ask: are these two classes doing the same conceptual job?
3. If yes → extract a shared base class or utility. Don't just deduplicate the flagged lines; fold
   the whole feature.
4. If they're structurally similar POJOs (getters/setters) → leave them; the duplication is noise.

---

## Coverage strategy

Sonar's coverage metric only counts what the test runner exercises. For files with 0% coverage:

1. Check if tests are structurally possible (no IntelliJ runtime dependency in the class).
2. Pure utility classes (formatters, serializers, parsers, builders) → always add unit tests.
3. UI/swing-dependent classes → skip or use a mock/headless approach.
4. Services with complex logic → write unit tests mocking the IntelliJ APIs.

---

## Rule reference

For any rule ID:
```
https://rules.sonarsource.com/java/RSPEC-3077/
https://rules.sonarsource.com/kotlin/RSPEC-3776/
https://rules.sonarsource.com/typescript/RSPEC-4790/
```
Pattern: `https://rules.sonarsource.com/{LANGUAGE}/RSPEC-{NUMBER}/`
