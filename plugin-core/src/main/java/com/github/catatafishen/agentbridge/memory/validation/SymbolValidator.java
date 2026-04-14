package com.github.catatafishen.agentbridge.memory.validation;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class SymbolValidator {

    private static final Logger LOG = Logger.getInstance(SymbolValidator.class);

    private static final Pattern FQN_LIKE = Pattern.compile(
        "[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*){1,10}\\.[A-Z][A-Za-z0-9]+(?:\\.[a-zA-Z][A-Za-z0-9]*)?"
    );

    private static final Pattern FILE_LINE_LIKE = Pattern.compile(
        "[A-Za-z][A-Za-z0-9_-]*\\.[a-zA-Z]{1,10}:\\d+(?:-\\d+)?"
    );

    private static final Pattern FILE_PATH_LIKE = Pattern.compile(
        "(?:[A-Za-z0-9._-]+/){1,15}[A-Za-z][A-Za-z0-9_-]*\\.[a-zA-Z]{1,10}"
    );

    private SymbolValidator() {
    }

    /**
     * Result of validating a single evidence reference.
     *
     * @param reference the original evidence string
     * @param valid     whether the reference resolves to something in the codebase
     * @param type      classification: "fqn", "file_line", "file_path", or "unknown"
     */
    public record ValidationResult(
        @NotNull String reference,
        boolean valid,
        @NotNull String type
    ) {
    }

    /**
     * Validate a list of evidence references against the project codebase.
     *
     * @param project    the IntelliJ project
     * @param references evidence reference strings (FQNs, file:line, file paths)
     * @return validation results for each reference
     */
    public static @NotNull List<ValidationResult> validate(
        @NotNull Project project,
        @NotNull List<String> references
    ) {
        List<ValidationResult> results = new ArrayList<>(references.size());
        for (String ref : references) {
            results.add(validateOne(project, ref));
        }
        return results;
    }

    /**
     * Check if all evidence references are valid.
     */
    public static boolean allValid(@NotNull List<ValidationResult> results) {
        return results.stream().allMatch(ValidationResult::valid);
    }

    /**
     * Check if any evidence reference is invalid.
     */
    public static boolean anyInvalid(@NotNull List<ValidationResult> results) {
        return results.stream().anyMatch(r -> !r.valid());
    }

    private static ValidationResult validateOne(@NotNull Project project, @NotNull String ref) {
        if (FILE_LINE_LIKE.matcher(ref).matches()) {
            return validateFileLineRef(project, ref);
        }
        if (FQN_LIKE.matcher(ref).matches()) {
            return validateFqn(project, ref);
        }
        if (FILE_PATH_LIKE.matcher(ref).matches()) {
            return validateFilePath(project, ref);
        }
        return new ValidationResult(ref, false, "unknown");
    }

    private static ValidationResult validateFqn(@NotNull Project project, @NotNull String fqn) {
        try {
            boolean found = ReadAction.compute(() -> resolveFqnViaPsi(project, fqn));
            return new ValidationResult(fqn, found, "fqn");
        } catch (Exception e) {
            LOG.debug("FQN validation failed for: " + fqn, e);
            return new ValidationResult(fqn, false, "fqn");
        }
    }

    private static ValidationResult validateFileLineRef(@NotNull Project project, @NotNull String ref) {
        String fileName = ref.substring(0, ref.indexOf(':'));
        return validateFileExists(project, fileName, ref);
    }

    private static ValidationResult validateFilePath(@NotNull Project project, @NotNull String path) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return new ValidationResult(path, false, "file_path");
        }

        boolean found = ReadAction.compute(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + path);
            return vf != null && !vf.isDirectory();
        });
        return new ValidationResult(path, found, "file_path");
    }

    private static ValidationResult validateFileExists(
        @NotNull Project project,
        @NotNull String fileName,
        @NotNull String originalRef
    ) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return new ValidationResult(originalRef, false, "file_line");
        }

        boolean found = ReadAction.compute(() -> findFileInProject(basePath, fileName));
        return new ValidationResult(originalRef, found, "file_line");
    }

    /**
     * Resolve a FQN via PSI. Uses reflection to avoid hard dependency on Java plugin.
     */
    private static boolean resolveFqnViaPsi(@NotNull Project project, @NotNull String fqn) {
        try {
            Class<?> facadeClass = Class.forName("com.intellij.psi.JavaPsiFacade");
            Object facade = facadeClass.getMethod("getInstance", Project.class).invoke(null, project);

            Class<?> scopeClass = Class.forName("com.intellij.psi.search.GlobalSearchScope");
            Object scope = scopeClass.getMethod("allScope", Project.class).invoke(null, project);

            // Strip method name from FQN if present (e.g., "com.example.Foo.bar" → "com.example.Foo")
            String className = fqn;
            if (isMethodRef(fqn)) {
                className = fqn.substring(0, fqn.lastIndexOf('.'));
            }

            Object psiClass = facadeClass.getMethod("findClass", String.class, scopeClass)
                .invoke(facade, className, scope);
            return psiClass != null;
        } catch (ClassNotFoundException e) {
            LOG.debug("JavaPsiFacade not available, falling back to file search for: " + fqn);
            return false;
        } catch (Exception e) {
            LOG.debug("FQN resolution failed: " + fqn, e);
            return false;
        }
    }

    /**
     * Heuristic: if the last segment starts with a lowercase letter, it's likely a method ref.
     */
    private static boolean isMethodRef(@NotNull String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fqn.length() - 1) return false;
        return Character.isLowerCase(fqn.charAt(lastDot + 1));
    }

    private static boolean findFileInProject(@NotNull String basePath, @NotNull String fileName) {
        VirtualFile root = LocalFileSystem.getInstance().findFileByPath(basePath);
        if (root == null) return false;
        return findFileRecursive(root, fileName, 0);
    }

    private static final int MAX_SEARCH_DEPTH = 10;

    private static boolean findFileRecursive(@NotNull VirtualFile dir, @NotNull String fileName, int depth) {
        if (depth > MAX_SEARCH_DEPTH) return false;
        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                String name = child.getName();
                if (name.startsWith(".") || "node_modules".equals(name)
                    || "build".equals(name) || "out".equals(name)) {
                    continue;
                }
                if (findFileRecursive(child, fileName, depth + 1)) return true;
            } else if (child.getName().equals(fileName)) {
                return true;
            }
        }
        return false;
    }
}
