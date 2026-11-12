package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.google.gson.JsonObject;
import com.intellij.codeInspection.redundantCast.RedundantCastInspection;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared fixture for end-to-end {@code get_commit_problems} tests: simulated changed files that carry many
 * warnings with real IntelliJ quick-fixes (redundant casts). The light fixture has no VCS repository, so the
 * "changed files" are injected through the tool's test seam; everything else (CodeSmellDetector, daemon analysis,
 * quick-fix discovery, {@code apply_action}) is the real thing. The files are created closed; subclasses decide
 * whether to open them.
 */
abstract class CommitProblemsToolTestCase extends BasePlatformTestCase {

    static final int FILE_COUNT = 6;
    static final int CASTS_PER_FILE = 3;
    static final int EXPECTED_FINDINGS = FILE_COUNT * CASTS_PER_FILE;
    static final String REDUNDANT_CAST_FIX = "Remove redundant cast";

    private static final Pattern PROBLEM_LINE = Pattern.compile("^(\\S+\\.java):(\\d+) \\[(\\w+)] (.+)$");
    private static final Pattern TOTAL_LINE = Pattern.compile("^(\\d+) problems in (\\d+) of (\\d+) changed file");

    final List<VirtualFile> changedFiles = new ArrayList<>();
    GetCommitProblemsTool tool;
    ApplyActionTool applyActionTool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new RedundantCastInspection());
        for (int i = 0; i < FILE_COUNT; i++) {
            PsiFile file = myFixture.addFileToProject("Changed" + i + ".java", sourceWithRedundantCasts(i));
            changedFiles.add(file.getVirtualFile());
        }
        tool = new GetCommitProblemsTool(getProject(), () -> List.copyOf(changedFiles));
        applyActionTool = new ApplyActionTool(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile openFile : fem.getOpenFiles()) {
                fem.closeFile(openFile);
            }
        } finally {
            super.tearDown();
        }
    }

    /**
     * A class with {@value #CASTS_PER_FILE} casts of its own type to itself, each flagged by the redundant-cast
     * inspection. The class's own type is used because the light fixture has no JDK, so {@code String} would not resolve.
     */
    static String sourceWithRedundantCasts(int index) {
        String type = "Changed" + index;
        StringBuilder sb = new StringBuilder();
        sb.append("public class ").append(type).append(" {\n");
        for (int c = 0; c < CASTS_PER_FILE; c++) {
            sb.append("    ").append(type).append(" self").append(c).append('(').append(type)
                .append(" x) { return (").append(type).append(") x; }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Calls the tool directly on the test (EDT) thread. {@code get_commit_problems} does all its work on pooled
     * threads and never calls back into the EDT, so no event pumping is needed; this also guards that property,
     * because a dependency on the EDT would block here until the tool's timeout.
     *
     * <p>Do not replace this by {@link #executeSync}: pumping the EDT in a busy loop makes IntelliJ's progress
     * manager throttle the analysis threads in favour of the EDT, which turned a 40 ms analysis into 12 s.</p>
     */
    String run(JsonObject args) throws Exception {
        return tool.execute(args);
    }

    static JsonObject json(Object... keyValues) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value instanceof Boolean b) obj.addProperty(key, b);
            else obj.addProperty(key, (Number) value);
        }
        return obj;
    }

    static Matcher totals(String result) {
        Matcher m = TOTAL_LINE.matcher(result.lines().findFirst().orElse(""));
        assertTrue("Unexpected response: " + result, m.find());
        return m;
    }

    static int totalFindings(String result) {
        return Integer.parseInt(totals(result).group(1));
    }

    static List<String> problemLines(String result) {
        List<String> lines = new ArrayList<>();
        for (String line : result.split("\n")) {
            if (PROBLEM_LINE.matcher(line).matches()) lines.add(line);
        }
        return lines;
    }

    static Matcher firstProblem(String result) {
        for (String line : result.split("\n")) {
            Matcher m = PROBLEM_LINE.matcher(line);
            if (m.matches()) return m;
        }
        throw new AssertionError("No problem line in: " + result);
    }

    static String firstFixName(String result) {
        for (String line : result.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Fix: ")) return trimmed.substring("Fix: ".length());
        }
        throw new AssertionError("No fix listed in: " + result);
    }

    VirtualFile fileNamed(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return changedFiles.stream().filter(f -> f.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("Unknown file in output: " + path));
    }

    /**
     * The changed files that are currently open in an editor.
     */
    List<String> openChangedFileNames() {
        FileEditorManager fem = FileEditorManager.getInstance(getProject());
        return changedFiles.stream().filter(fem::isFileOpen).map(VirtualFile::getName).toList();
    }

    /**
     * Runs {@code action} on a pooled thread while pumping the EDT, for tools that dispatch work back to the EDT
     * (e.g. {@code apply_action}). The test thread blocks on the future between pumps instead of spinning, so that
     * the progress manager does not throttle background analysis in favour of a busy EDT.
     */
    String executeSync(ThrowingSupplier<String> action) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(action.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        long deadline = System.currentTimeMillis() + 120_000;
        while (!future.isDone()) {
            UIUtil.dispatchAllInvocationEvents();
            try {
                future.get(10, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // expected: loop to pump the EDT again
            }
            if (System.currentTimeMillis() > deadline) {
                fail("executeSync timed out after 120 seconds");
            }
        }
        UIUtil.dispatchAllInvocationEvents();
        return future.get();
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
