package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles editor-related tool calls: open_in_editor, show_diff,
 * create_scratch_file, and list_scratch_files.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
class EditorTools extends AbstractToolHandler {

    private static final Logger LOG = Logger.getInstance(EditorTools.class);

    private static final String PARAM_CONTENT = "content";
    private static final String FORMAT_CHARS_SUFFIX = " chars)";
    private static final String DIFF_LABEL_CURRENT = "Current";
    private static final String JSON_TITLE = "title";

    EditorTools(Project project) {
        super(project);
        register("open_in_editor", this::openInEditor);
        register("show_diff", this::showDiff);
        register("create_scratch_file", this::createScratchFile);
        register("list_scratch_files", this::listScratchFiles);
        register("run_scratch_file", this::runScratchFile);
        register("get_chat_html", this::getChatHtml);
        register("get_active_file", this::getActiveFile);
        register("get_open_editors", this::getOpenEditors);
        register("list_themes", this::listThemes);
        register("set_theme", this::setTheme);
        register("search_conversation_history", this::searchConversationHistory);
    }

    private String openInEditor(JsonObject args) throws Exception {
        if (!args.has("file")) {
            return "Error: 'file' parameter is required";
        }
        String pathStr = args.get("file").getAsString();
        int line = args.has("line") ? args.get("line").getAsInt() : -1;
        boolean focus = !args.has("focus") || args.get("focus").getAsBoolean();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                if (line > 0) {
                    new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line - 1, 0)
                        .navigate(focus);
                } else {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .openFile(vf, focus);
                }

                // Force DaemonCodeAnalyzer to run on this file
                PsiFile psiFile = ApplicationManager.getApplication().runReadAction(
                    (Computable<PsiFile>) () -> PsiManager.getInstance(project).findFile(vf));
                if (psiFile != null) {
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "File opened in editor");
                }

                resultFuture.complete("Opened " + pathStr + (line > 0 ? " at line " + line : "") +
                    " (daemon analysis triggered - use get_highlights after a moment)");
            } catch (Exception e) {
                resultFuture.complete("Error opening file: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    /**
     * Show a diff between two files, or between the current file content and a provided string,
     * in IntelliJ's diff viewer.
     */
    private String showDiff(JsonObject args) throws Exception {
        if (!args.has("file")) {
            return "Error: 'file' parameter is required";
        }
        String pathStr = args.get("file").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                String result = showDiffForFile(args, vf, pathStr);
                resultFuture.complete(result);
            } catch (Exception e) {
                resultFuture.complete("Error showing diff: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String showDiffForFile(JsonObject args, VirtualFile vf, String pathStr) {
        if (args.has("file2")) {
            return showTwoFileDiff(args, vf, pathStr);
        } else if (args.has(PARAM_CONTENT)) {
            return showContentDiff(args, vf, pathStr);
        } else {
            return showVcsDiff(vf, pathStr);
        }
    }

    private String showTwoFileDiff(JsonObject args, VirtualFile vf, String pathStr) {
        String pathStr2 = args.get("file2").getAsString();
        VirtualFile vf2 = resolveVirtualFile(pathStr2);
        if (vf2 == null) {
            return "Error: Second file not found: " + pathStr2;
        }
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        var content2 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf2);
        var request = new com.intellij.diff.requests.SimpleDiffRequest(
            "Diff: " + vf.getName() + " vs " + vf2.getName(),
            content1, content2, vf.getName(), vf2.getName());
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        return "Showing diff: " + pathStr + " vs " + pathStr2;
    }

    private String showContentDiff(JsonObject args, VirtualFile vf, String pathStr) {
        String newContent = args.get(PARAM_CONTENT).getAsString();
        String title = args.has(JSON_TITLE) ? args.get(JSON_TITLE).getAsString() : "Proposed Changes";
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        var content2 = com.intellij.diff.DiffContentFactory.getInstance()
            .create(project, newContent, vf.getFileType());
        var request = new com.intellij.diff.requests.SimpleDiffRequest(
            title, content1, content2, DIFF_LABEL_CURRENT, "Proposed");
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        return "Showing diff for " + pathStr + ": current vs proposed changes";
    }

    private String showVcsDiff(VirtualFile vf, String pathStr) {
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        com.intellij.diff.DiffManager.getInstance().showDiff(project,
            new com.intellij.diff.requests.SimpleDiffRequest(
                "File: " + vf.getName(), content1, content1, DIFF_LABEL_CURRENT, DIFF_LABEL_CURRENT));
        return "Opened " + pathStr + " in diff viewer. " +
            "Tip: pass 'file2' for two-file diff, or 'content' to diff against proposed changes.";
    }

    private String createScratchFile(JsonObject args) {
        String name = args.has("name") ? args.get("name").getAsString() : "scratch.txt";
        String content = args.has(PARAM_CONTENT) ? args.get(PARAM_CONTENT).getAsString() : "";

        try {
            final VirtualFile[] resultFile = new VirtualFile[1];
            final String[] errorMsg = new String[1];

            EdtUtil.invokeAndWait(() ->
                createAndOpenScratchFile(name, content, resultFile, errorMsg));

            if (resultFile[0] == null) {
                return "Error: Failed to create scratch file" +
                    (errorMsg[0] != null ? ": " + errorMsg[0] : "");
            }

            String scratchPath = resultFile[0].getPath();
            int lineCount = content.isEmpty() ? 1 : (int) content.lines().count();
            FileTools.followFileIfEnabled(project, scratchPath, 1, lineCount,
                FileTools.HIGHLIGHT_EDIT, FileTools.agentLabel() + " created scratch");

            return "Created scratch file: " + scratchPath + " (" + content.length() + FORMAT_CHARS_SUFFIX;
        } catch (Exception e) {
            LOG.warn("Failed to create scratch file", e);
            return "Error creating scratch file: " + e.getMessage();
        }
    }

    private void createAndOpenScratchFile(String name, String content,
                                          VirtualFile[] resultFile, String[] errorMsg) {
        try {
            com.intellij.ide.scratch.ScratchFileService scratchService =
                com.intellij.ide.scratch.ScratchFileService.getInstance();
            com.intellij.ide.scratch.ScratchRootType scratchRoot =
                com.intellij.ide.scratch.ScratchRootType.getInstance();

            // Cast needed: runWriteAction is overloaded (Computable vs. ThrowableComputable)
            //noinspection RedundantCast
            resultFile[0] = ApplicationManager.getApplication().runWriteAction(
                (com.intellij.openapi.util.Computable<VirtualFile>) () -> {
                    try {
                        VirtualFile file = scratchService.findFile(
                            scratchRoot, name,
                            com.intellij.ide.scratch.ScratchFileService.Option.create_if_missing
                        );
                        if (file != null) {
                            OutputStream out = file.getOutputStream(null);
                            out.write(content.getBytes(StandardCharsets.UTF_8));
                            out.close();
                        }
                        return file;
                    } catch (IOException e) {
                        LOG.warn("Failed to create/write scratch file", e);
                        errorMsg[0] = e.getMessage();
                        return null;
                    }
                }
            );

            if (resultFile[0] != null) {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openFile(resultFile[0], true);
            }
        } catch (Exception e) {
            LOG.warn("Failed in EDT execution", e);
            errorMsg[0] = e.getMessage();
        }
    }

    /**
     * List all scratch files visible to the IDE.
     * Returns paths that can be used with intellij_read_file.
     */
    @SuppressWarnings("unused")
    private String listScratchFiles(JsonObject args) {
        try {
            StringBuilder result = new StringBuilder();
            final int[] count = {0};
            final Set<String> seenPaths = new HashSet<>();

            EdtUtil.invokeAndWait(() -> {
                try {
                    result.append("Scratch files:\n");

                    // First, check currently open files in editors (catches files open but not in VFS yet)
                    com.intellij.openapi.fileEditor.FileEditorManager editorManager =
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                    VirtualFile[] openFiles = editorManager.getOpenFiles();

                    for (VirtualFile file : openFiles) {
                        // Check if this is a scratch file (path contains "scratches")
                        String path = file.getPath();
                        if (path.contains("scratches") && !file.isDirectory()) {
                            seenPaths.add(path);
                            long sizeKB = file.getLength() / 1024;
                            result.append("- ").append(path)
                                .append(" (").append(sizeKB).append(" KB) [OPEN]\n");
                            count[0]++;
                        }
                    }

                    // Then, list files from scratch root directory (catches files on disk)
                    com.intellij.ide.scratch.ScratchRootType scratchRoot =
                        com.intellij.ide.scratch.ScratchRootType.getInstance();

                    // Get scratch root directory
                    VirtualFile scratchesDir = scratchRoot.findFile(null, "",
                        com.intellij.ide.scratch.ScratchFileService.Option.existing_only);

                    if (scratchesDir != null && scratchesDir.exists()) {
                        listScratchFilesRecursive(scratchesDir, result, count, 0, seenPaths);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to list scratch files", e);
                    result.append("Error listing scratch files: ").append(e.getMessage());
                }
            });

            if (count[0] == 0 && !result.toString().contains("Error")) {
                result.append("\nTotal: 0 scratch files\n");
                result.append("Use create_scratch_file to create one.");
            } else {
                result.append("\nTotal: ").append(count[0]).append(" scratch file(s)\n");
                result.append("Use intellij_read_file with these paths to read content.");
            }

            return result.toString();
        } catch (Exception e) {
            LOG.warn("Failed to list scratch files", e);
            return "Error listing scratch files: " + e.getMessage();
        }
    }

    private void listScratchFilesRecursive(VirtualFile dir, StringBuilder result, int[] count, int depth, Set<
        String> seenPaths) {
        if (depth > 3) return; // Prevent excessive recursion

        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                listScratchFilesRecursive(child, result, count, depth + 1, seenPaths);
            } else {
                String path = child.getPath();
                if (!seenPaths.contains(path)) {  // Skip if already listed from open files
                    seenPaths.add(path);
                    String indent = "  ".repeat(depth);
                    long sizeKB = child.getLength() / 1024;
                    result.append(indent).append("- ").append(path)
                        .append(" (").append(sizeKB).append(" KB)\n");
                    count[0]++;
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private String getChatHtml(JsonObject args) throws Exception {
        var panel = com.github.catatafishen.ideagentforcopilot.ui.ChatConsolePanel.Companion.getInstance(project);
        if (panel == null) {
            return "Error: Chat panel not found. Is the Copilot tool window open?";
        }
        String html = panel.getPageHtml();
        if (html == null) {
            return "Error: Could not retrieve page HTML. Browser may not be ready.";
        }
        return html;
    }

    // ---- Conversation History ----

    private String searchConversationHistory(JsonObject args) {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: project base path unavailable";

        java.io.File agentDir = new java.io.File(basePath, ".agent-work");
        java.io.File archiveDir = new java.io.File(agentDir, "conversations");
        java.io.File currentFile = new java.io.File(agentDir, "conversation.json");

        String query = args.has("query") ? args.get("query").getAsString() : null;
        String file = args.has("file") ? args.get("file").getAsString() : null;
        int maxChars = args.has("max_chars") ? args.get("max_chars").getAsInt() : 8000;

        // List mode: no file selected and no query
        if (file == null && query == null) {
            return listConversations(currentFile, archiveDir);
        }

        // Read specific conversation
        if (file != null && query == null) {
            return readConversation(file, currentFile, archiveDir, maxChars);
        }

        // Search mode
        return searchConversations(query, file, currentFile, archiveDir, maxChars);
    }

    private static String listConversations(java.io.File currentFile, java.io.File archiveDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conversations:\n\n");
        if (currentFile.exists() && currentFile.length() > 10) {
            sb.append("• current (").append(formatFileSize(currentFile.length())).append(")\n");
        }
        if (archiveDir.exists()) {
            java.io.File[] archives = archiveDir.listFiles((d, n) -> n.endsWith(".json"));
            if (archives != null && archives.length > 0) {
                java.util.Arrays.sort(archives, java.util.Comparator.comparing(java.io.File::getName).reversed());
                for (java.io.File f : archives) {
                    String name = f.getName().replace("conversation-", "").replace(".json", "");
                    sb.append("• ").append(name).append(" (").append(formatFileSize(f.length())).append(")\n");
                }
            }
        }
        if (sb.length() < 20) {
            return "No conversation history found.";
        }
        sb.append("\nUse 'file' parameter to read a specific conversation (e.g., file='current' or file='2026-03-04T15-30-00').");
        sb.append("\nUse 'query' parameter to search across all conversations.");
        return sb.toString();
    }

    private static String readConversation(String file, java.io.File currentFile,
                                           java.io.File archiveDir, int maxChars) {
        java.io.File target = resolveConversationFile(file, currentFile, archiveDir);
        if (target == null || !target.exists()) {
            return "Error: Conversation file not found: " + file;
        }
        return conversationJsonToText(target, null, maxChars);
    }

    private static String searchConversations(String query, String file, java.io.File currentFile,
                                              java.io.File archiveDir, int maxChars) {
        String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();

        // Collect files to search
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        if (file != null) {
            java.io.File target = resolveConversationFile(file, currentFile, archiveDir);
            if (target != null && target.exists()) files.add(target);
        } else {
            if (currentFile.exists() && currentFile.length() > 10) files.add(currentFile);
            if (archiveDir.exists()) {
                java.io.File[] archives = archiveDir.listFiles((d, n) -> n.endsWith(".json"));
                if (archives != null) {
                    java.util.Arrays.sort(archives, java.util.Comparator.comparing(java.io.File::getName).reversed());
                    files.addAll(java.util.Arrays.asList(archives));
                }
            }
        }

        int totalMatches = 0;
        for (java.io.File f : files) {
            String label = f.equals(currentFile) ? "current" : f.getName().replace("conversation-", "").replace(".json", "");
            String result = conversationJsonToText(f, lowerQuery, maxChars - sb.length());
            if (!result.isEmpty()) {
                long matchCount = result.lines().filter(l -> l.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)).count();
                totalMatches += matchCount;
                sb.append("── ").append(label).append(" (").append(matchCount).append(" matches) ──\n");
                sb.append(result).append("\n");
            }
            if (sb.length() >= maxChars) break;
        }

        if (totalMatches == 0) return "No matches found for: " + query;
        return sb.toString().trim();
    }

    private static java.io.File resolveConversationFile(String name, java.io.File currentFile, java.io.File archiveDir) {
        if ("current".equalsIgnoreCase(name)) return currentFile;
        java.io.File direct = new java.io.File(archiveDir, "conversation-" + name + ".json");
        if (direct.exists()) return direct;
        // Fuzzy match: find archive containing the name
        if (archiveDir.exists()) {
            java.io.File[] archives = archiveDir.listFiles((d, n) -> n.contains(name) && n.endsWith(".json"));
            if (archives != null && archives.length > 0) return archives[0];
        }
        return null;
    }

    /**
     * Reads a conversation JSON file and converts to text.
     * If searchQuery is non-null, only includes entries matching the query (case-insensitive).
     */
    private static String conversationJsonToText(java.io.File file, String searchQuery, int maxChars) {
        try {
            String json = java.nio.file.Files.readString(file.toPath());
            var arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            StringBuilder sb = new StringBuilder();

            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                var obj = el.getAsJsonObject();
                String type = obj.has("type") ? obj.get("type").getAsString() : "";
                String line = formatConversationEntry(obj, type);
                if (line == null || line.isEmpty()) continue;

                if (searchQuery != null && !line.toLowerCase(java.util.Locale.ROOT).contains(searchQuery)) {
                    continue;
                }
                sb.append(line).append("\n");
                if (sb.length() >= maxChars) {
                    sb.append("...[truncated at ").append(maxChars).append(" chars]\n");
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error reading " + file.getName() + ": " + e.getMessage();
        }
    }

    private static String formatConversationEntry(JsonObject obj, String type) {
        return switch (type) {
            case "prompt" -> {
                String text = obj.has("text") ? obj.get("text").getAsString() : "";
                String ts = obj.has("ts") ? " [" + obj.get("ts").getAsString() + "]" : "";
                yield ">>> " + text + ts;
            }
            case "text" -> {
                String raw = obj.has("raw") ? obj.get("raw").getAsString() : "";
                yield raw.isEmpty() ? null : raw.trim();
            }
            case "thinking" -> {
                String raw = obj.has("raw") ? obj.get("raw").getAsString() : "";
                yield raw.isEmpty() ? null : "[thinking] " + raw.trim();
            }
            case "tool" -> {
                String title = obj.has(JSON_TITLE) ? obj.get(JSON_TITLE).getAsString() : "tool";
                String toolArgs = obj.has("args") ? obj.get("args").getAsString() : "";
                yield "Tool: " + title + (toolArgs.isEmpty() ? "" : " " + toolArgs);
            }
            case "subagent" -> {
                String agentType = obj.has("agentType") ? obj.get("agentType").getAsString() : "";
                String desc = obj.has("description") ? obj.get("description").getAsString() : "";
                yield "SubAgent: " + agentType + " — " + desc;
            }
            case "context" -> "Context files attached";
            case "status" -> {
                String msg = obj.has("message") ? obj.get("message").getAsString() : "";
                yield msg.isEmpty() ? null : "Status: " + msg;
            }
            case "separator" -> {
                String ts = obj.has("timestamp") ? obj.get("timestamp").getAsString() : "";
                yield "--- Session " + ts + " ---";
            }
            default -> null;
        };
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Returns the currently active (focused) file in the editor.
     */
    @SuppressWarnings("unused")
    private String getActiveFile(JsonObject args) throws Exception {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var editorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                var editor = editorManager.getSelectedTextEditor();
                if (editor == null) {
                    resultFuture.complete("No active editor");
                    return;
                }
                var doc = editor.getDocument();
                var vf = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(doc);
                if (vf == null) {
                    resultFuture.complete("No file associated with active editor");
                    return;
                }

                String basePath = project.getBasePath();
                String filePath = vf.getPath();
                String displayPath = basePath != null ? relativize(basePath, filePath) : filePath;
                int line = editor.getCaretModel().getLogicalPosition().line + 1;
                int column = editor.getCaretModel().getLogicalPosition().column + 1;

                resultFuture.complete(displayPath + " (line " + line + ", column " + column + ")");
            } catch (Exception e) {
                resultFuture.complete("Error: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    /**
     * Run a scratch file with optional classpath module and interactive mode.
     * Supports .kts, .kt, .java, .groovy, .py and other runnable file types.
     */
    @SuppressWarnings("unused")
    private String runScratchFile(JsonObject args) throws Exception {
        if (!args.has("name")) {
            return "Error: 'name' parameter is required (scratch file name, e.g. 'test.kts')";
        }
        String name = args.get("name").getAsString();
        String moduleName = args.has("module") ? args.get("module").getAsString() : "";
        boolean interactive = args.has("interactive") && args.get("interactive").getAsBoolean();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                // 1. Find the scratch file
                VirtualFile scratchFile = findScratchFile(name);
                if (scratchFile == null) {
                    resultFuture.complete("Error: Scratch file not found: '" + name
                        + "'. Use list_scratch_files to see available files.");
                    return;
                }

                // 1b. Open the scratch in the editor so the user can follow along
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openFile(scratchFile, false);

                // 2. Find appropriate configuration type for this file extension
                String extension = scratchFile.getExtension();
                var configType = findScratchConfigType(extension);
                if (configType == null) {
                    resultFuture.complete("Error: No run configuration type found for ."
                        + extension + " files. Available types: " + listAvailableConfigTypes());
                    return;
                }

                // 3. Create temporary run configuration
                var factories = configType.getConfigurationFactories();
                if (factories.length == 0) {
                    resultFuture.complete("Error: No configuration factory for " + configType.getDisplayName());
                    return;
                }

                var runManager = com.intellij.execution.RunManager.getInstance(project);
                var settings = runManager.createConfiguration(
                    "Scratch: " + scratchFile.getName(), factories[0]);
                var config = settings.getConfiguration();

                // 4. Set script/file path via reflection
                boolean pathSet;
                boolean needsModule = false;
                if (config instanceof com.intellij.execution.scratch.JavaScratchConfiguration scratchConfig) {
                    scratchConfig.setScratchFileUrl(scratchFile.getUrl());
                    // Main class name must match the public class in the scratch file
                    String className = scratchFile.getNameWithoutExtension();
                    scratchConfig.setMainClassName(className);
                    pathSet = true;
                    needsModule = true;
                } else {
                    pathSet = setScriptPath(config, scratchFile.getPath());
                }

                // 4b. For Python configs, set the SDK home from the global SDK table
                if ("py".equalsIgnoreCase(extension)) {
                    trySetPythonSdkHome(config);
                }

                // 4c. Set working directory (needed by Node.js and other script runners)
                trySetWorkingDirectory(config, scratchFile.getParent().getPath());

                // 5. Set classpath module (auto-detect for Java/Kotlin if not specified)
                String moduleStatus = "";
                if (!moduleName.isEmpty()) {
                    var module = com.intellij.openapi.module.ModuleManager.getInstance(project)
                        .findModuleByName(moduleName);
                    if (module != null) {
                        trySetModule(config, module);
                        moduleStatus = "\nClasspath: " + moduleName;
                    } else {
                        moduleStatus = "\nWarning: Module '" + moduleName + "' not found";
                    }
                } else if (needsModule) {
                    var modules = com.intellij.openapi.module.ModuleManager.getInstance(project).getModules();
                    if (modules.length > 0) {
                        trySetModule(config, modules[0]);
                        moduleStatus = "\nClasspath: " + modules[0].getName() + " (auto-detected)";
                    }
                }

                // 6. Set interactive mode (primarily for Kotlin scripts)
                String interactiveStatus = "";
                if (interactive) {
                    boolean set = trySetInteractiveMode(config, true);
                    interactiveStatus = set ? "\nInteractive mode: ON"
                        : "\nWarning: Interactive mode not supported for this config type";
                }

                // 7. Execute
                settings.setTemporary(true);
                settings.setEditBeforeRun(false);
                runManager.addConfiguration(settings);
                runManager.setSelectedConfiguration(settings);

                var executor = com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance();
                var envBuilder = com.intellij.execution.runners.ExecutionEnvironmentBuilder
                    .createOrNull(executor, settings);
                if (envBuilder == null) {
                    resultFuture.complete("Error: Cannot create execution environment for "
                        + configType.getDisplayName());
                    return;
                }

                com.intellij.execution.ExecutionManager.getInstance(project)
                    .restartRunProfile(envBuilder.build());

                resultFuture.complete("Started: " + scratchFile.getName()
                    + " [" + configType.getDisplayName() + "]"
                    + (pathSet ? "" : "\nWarning: Could not set script path on config")
                    + moduleStatus + interactiveStatus
                    + "\nOutput will appear in the Run panel. Use read_run_output to read it.");
            } catch (Exception e) {
                LOG.warn("Failed to run scratch file", e);
                resultFuture.complete("Error running scratch file: " + e.getMessage());
            }
        });

        return resultFuture.get(15, TimeUnit.SECONDS);
    }

    // ---- Scratch File Helpers ----

    private VirtualFile findScratchFile(String name) {
        // Try as absolute/project-relative path first
        VirtualFile asPath = resolveVirtualFile(name);
        if (asPath != null && !asPath.isDirectory()) return asPath;

        // Try finding in scratch root directory
        try {
            var scratchRoot = com.intellij.ide.scratch.ScratchRootType.getInstance();
            // Try exact relative path match
            VirtualFile file = scratchRoot.findFile(null, name,
                com.intellij.ide.scratch.ScratchFileService.Option.existing_only);
            if (file != null && !file.isDirectory()) return file;
        } catch (Exception ignored) {
            // Fall through to directory search
        }

        // Search by filename in scratch root directory
        try {
            var scratchService = com.intellij.ide.scratch.ScratchFileService.getInstance();
            var scratchRoot = com.intellij.ide.scratch.ScratchRootType.getInstance();
            VirtualFile dir = scratchService.getVirtualFile(scratchRoot);
            if (dir != null) return findFileByName(dir, name, 0);
        } catch (Exception ignored) {
            // Fall through
        }

        return null;
    }

    private VirtualFile findFileByName(VirtualFile dir, String name, int depth) {
        if (depth > 3) return null;
        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                VirtualFile found = findFileByName(child, name, depth + 1);
                if (found != null) return found;
            } else if (child.getName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    private com.intellij.execution.configurations.ConfigurationType findScratchConfigType(String extension) {
        if (extension == null) return null;

        // Java scratch files have a dedicated config type
        if ("java".equalsIgnoreCase(extension)) {
            return com.intellij.execution.scratch.JavaScratchConfigurationType.getInstance();
        }

        // Map extensions to config type IDs or display-name search terms
        // Use exact IDs (prefixed with "id:") when available to avoid ambiguous matches
        String searchTerm = switch (extension.toLowerCase()) {
            case "kts" -> "kotlin script";
            case "groovy", "gvy" -> "groovy";
            case "py" -> "id:PythonConfigurationType";
            case "scala" -> "scala";
            case "js", "mjs", "ts", "mts" -> "id:NodeJSConfigurationType";
            default -> extension;
        };

        // For exact ID lookups, use ConfigurationTypeUtil directly
        if (searchTerm.startsWith("id:")) {
            return com.intellij.execution.configurations.ConfigurationTypeUtil
                .findConfigurationType(searchTerm.substring(3));
        }

        // For display-name fuzzy matching, iterate all config types
        var configTypes = com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensions();
        for (var ct : configTypes) {
            String displayName = ct.getDisplayName().toLowerCase();
            String id = ct.getId().toLowerCase();
            if (displayName.contains(searchTerm) || id.contains(searchTerm.replace(" ", ""))) {
                return ct;
            }
        }
        return null;
    }

    private String listAvailableConfigTypes() {
        var configTypes = com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensions();
        StringBuilder sb = new StringBuilder();
        for (var ct : configTypes) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(ct.getDisplayName());
        }
        return sb.toString();
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private boolean setScriptPath(com.intellij.execution.configurations.RunConfiguration config, String path) {
        for (String method : java.util.List.of("setupFilePath", "setFilePath", "setScriptName", "setScriptPath",
            "setScriptFile", "setMainScriptFilePath", "setMainClassName")) {
            try {
                config.getClass().getMethod(method, String.class).invoke(config, path);
                return true;
            } catch (Exception ignored) {
                // Try next method name
            }
        }
        return false;
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private void trySetModule(com.intellij.execution.configurations.RunConfiguration config,
                              com.intellij.openapi.module.Module module) {
        try {
            config.getClass().getMethod("setModule", com.intellij.openapi.module.Module.class)
                .invoke(config, module);
        } catch (Exception ignored) {
            // Config type may not support module setting
        }
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private void trySetWorkingDirectory(com.intellij.execution.configurations.RunConfiguration config,
                                        String workingDir) {
        for (String method : java.util.List.of("setWorkingDirectory", "setWorkDir", "setWorkingDir")) {
            try {
                config.getClass().getMethod(method, String.class).invoke(config, workingDir);
                return;
            } catch (Exception ignored) {
                // Try next method name
            }
        }
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private boolean trySetInteractiveMode(com.intellij.execution.configurations.RunConfiguration config,
                                          boolean interactive) {
        for (String method : java.util.List.of("setInteractiveMode", "setIsInteractive",
            "setMakeBeforeRun")) {
            try {
                config.getClass().getMethod(method, boolean.class).invoke(config, interactive);
                return true;
            } catch (Exception ignored) {
                // Try next method name
            }
        }
        return false;
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private void trySetPythonSdkHome(com.intellij.execution.configurations.RunConfiguration config) {
        // Find a Python SDK in the global SDK table and set it on the config via reflection
        var sdkTable = com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance();
        for (var sdk : sdkTable.getAllJdks()) {
            if ("Python SDK".equals(sdk.getSdkType().getName())) {
                try {
                    config.getClass().getMethod("setSdkHome", String.class)
                        .invoke(config, sdk.getHomePath());
                    return;
                } catch (Exception ignored) {
                    // Config type may not support setSdkHome
                }
            }
        }
    }

    // ---- End Scratch File Helpers ----

    /**
     * Returns all currently open editor tabs with their file paths.
     */
    @SuppressWarnings("unused")
    private String getOpenEditors(JsonObject args) throws Exception {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var editorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                VirtualFile[] openFiles = editorManager.getOpenFiles();
                if (openFiles.length == 0) {
                    resultFuture.complete("No open editors");
                    return;
                }

                // Find the currently selected file to mark it
                var selectedEditor = editorManager.getSelectedTextEditor();
                VirtualFile activeFile = null;
                if (selectedEditor != null) {
                    activeFile = com.intellij.openapi.fileEditor.FileDocumentManager
                        .getInstance().getFile(selectedEditor.getDocument());
                }

                String basePath = project.getBasePath();
                StringBuilder sb = new StringBuilder();
                sb.append("Open editors (").append(openFiles.length).append("):\n");
                for (VirtualFile file : openFiles) {
                    String filePath = file.getPath();
                    String displayPath = basePath != null ? relativize(basePath, filePath) : filePath;
                    boolean isActive = activeFile != null && file.equals(activeFile);
                    boolean isModified = com.intellij.openapi.fileEditor.FileDocumentManager
                        .getInstance().isFileModified(file);
                    sb.append(isActive ? "* " : "  ");
                    sb.append(displayPath);
                    if (isModified) sb.append(" [modified]");
                    sb.append('\n');
                }
                resultFuture.complete(sb.toString().trim());
            } catch (Exception e) {
                resultFuture.complete("Error: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String listThemes(JsonObject args) {
        var lafManager = com.intellij.ide.ui.LafManager.getInstance();
        var current = lafManager.getCurrentUIThemeLookAndFeel();
        String currentName = current != null ? current.getName() : "";

        var themes = kotlin.sequences.SequencesKt.toList(lafManager.getInstalledThemes());
        var sb = new StringBuilder("Available themes:\n\n");
        for (var theme : themes) {
            boolean active = theme.getName().equals(currentName);
            sb.append(active ? "  ▸ " : "  • ").append(theme.getName())
                .append(theme.isDark() ? " (dark)" : " (light)");
            if (active) sb.append(" ← current");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String setTheme(JsonObject args) throws Exception {
        if (!args.has("theme")) {
            return "Missing required parameter: 'theme' (theme name or partial name)";
        }
        String themeQuery = args.get("theme").getAsString();
        String queryLower = themeQuery.toLowerCase();

        var lafManager = com.intellij.ide.ui.LafManager.getInstance();
        var themes = kotlin.sequences.SequencesKt.toList(lafManager.getInstalledThemes());

        // Find matching theme: exact name first, then case-insensitive substring
        com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo target = null;
        for (var theme : themes) {
            if (theme.getName().equals(themeQuery)) {
                target = theme;
                break;
            }
            if (target == null && theme.getName().toLowerCase().contains(queryLower)) {
                target = theme;
            }
        }

        if (target == null) {
            return "Theme not found: '" + themeQuery + "'. Use list_themes to see available themes.";
        }

        var finalTarget = target;
        java.util.concurrent.CompletableFuture<String> resultFuture = new java.util.concurrent.CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                lafManager.setCurrentLookAndFeel(finalTarget, false);
                lafManager.updateUI();
                resultFuture.complete("Theme changed to '" + finalTarget.getName() + "'.");
            } catch (Exception e) {
                LOG.warn("Failed to set theme", e);
                resultFuture.complete("Failed to set theme: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }
}
