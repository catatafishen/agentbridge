# `openFileSilently()` TransactionGuard Warning — Accepted Limitation

**Status**: Accepted — non-fatal, no stable-API fix exists. Not tracked as a GitHub issue because there is nothing
actionable on our side; revisit only if JetBrains stabilises a relevant API (see
[Feature request](#jetbrains-feature-request-candidate) below).

**Call site**: `PsiBridgeService.openFileSilently()` (`PsiBridgeService.java`)

---

## Symptom

After a write tool edits a file that isn't already open, `openFileSilently()` calls
`FileEditorManager.openFile(vf, false)` from `invokeLater` so the daemon can re-analyze it and auto-highlights can be
appended to the tool result. Occasionally this logs a **non-fatal**
`LOG.error` (shows up as an IDE "Internal error" balloon, does not throw or break the tool call):

```
java.lang.Throwable: Write-unsafe context! Model changes are allowed from write-safe contexts only.
Please ensure you're using invokeLater/invokeAndWait with a correct modality state (not "any").
See TransactionGuard documentation for details.
	at com.intellij.psi.impl.PsiDocumentManagerBase.commitDocument(PsiDocumentManagerBase.java:503)
	at com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorProvider.setStateImpl(...)
	...
	at com.intellij.openapi.fileEditor.impl.FileEditorManagerImplKt.blockingWaitForCompositeFileOpen(...)
	at com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl.openFileImpl(...)
	at com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl.openFile(...)
	at com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.openFile(...)
	at ...PsiBridgeService.lambda$openFileSilently$12(PsiBridgeService.java:1148)
```

## Root Cause

`FileEditorManager.openFile(vf, focusEditor)` — the only **stable** overload — internally bridges to async composite
loading via a blocking nested event pump (`FileEditorManagerImplKt.blockingWaitForCompositeFileOpen`). During that
nested pump, the platform's own editor-state-restoration coroutine calls `PsiDocumentManagerBase.commitDocument()`,
which unconditionally asserts a "write-safe" `TransactionGuard` context (the assertion fires before any
"already committed" short-circuit). The reentrant nested-pump call doesn't carry that context, so the assertion logs an
error even though nothing is actually broken.

## Why We Don't Fix It

The only APIs that avoid the blocking pump are:

- `FileEditorManager.requestOpenFile(VirtualFile)` — `@ApiStatus.Experimental`, and its entire implementation is
  `this.openFile(file, true)` — i.e. it does **not** set
  `waitForCompositeOpen=false` and would not fix anything even if adopted.
- `FileEditorOpenOptions(waitForCompositeOpen = false)` — the actual knob that skips the blocking pump, but
  `FileEditorOpenOptions` itself is `@ApiStatus.Internal` (stricter than Experimental; see project rule against internal
  APIs in `DEVELOPMENT.md` § IntelliJ Platform Rules).

Pre-committing the document before calling `openFile` does not help either — `commitDocument()`'s write-safety assertion
is unconditional and fires before any "already committed" check.

There is no stable-or-experimental path that eliminates this race. We accept the occasional non-fatal log entry rather
than depend on an Internal-tier API that JetBrains can change or remove without notice.

## JetBrains Feature Request Candidate

**Not yet filed.** If we decide to file one, the generic motivating use case (broader than our specific tool) is:

> Plugins that programmatically open a file to trigger a side effect (re-run daemon analysis,
> warm a cache, prefetch content) from a background/service context — without stealing focus and
> without blocking the calling thread on composite-editor creation — currently have no **stable**
> API to do so. The only non-blocking overload (`FileEditorOpenOptions.waitForCompositeOpen`) is
> `@ApiStatus.Internal`, and the seemingly-relevant `@ApiStatus.Experimental` convenience method
> (`requestOpenFile`) is a pure passthrough to the blocking overload, so it doesn't actually solve
> the problem despite its name suggesting otherwise.
>
> Request: stabilize (or promote to `@ApiStatus.Experimental` with real distinct behavior) a
> non-blocking "open file without focus, don't wait for composite creation" API, e.g. a public
> `FileEditorManager.openFile(VirtualFile, FileEditorOpenOptions)` overload with
> `waitForCompositeOpen` exposed, or document a stable alternative for background/automation
> plugins (indexers, code-generation tools, AI coding agents) that need this today.

File at https://youtrack.jetbrains.com/issues/IJPL if this becomes a recurring pain point (e.g. if the log noise starts
confusing users or triggering support requests).

## Revisit When

- JetBrains stabilizes `FileEditorOpenOptions` or a non-blocking `openFile` overload, **or**
- The `LOG.error` starts causing actual failures (not just log noise) in a supported IDE version.
