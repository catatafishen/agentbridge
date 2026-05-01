package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.RefactorRenderer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Java files to Kotlin using IntelliJ's bundled J2K (Java-to-Kotlin) converter.
 *
 * <p>Uses reflection to invoke {@code JavaToKotlinAction.Handler.convertFiles$default}
 * (the synchronous old API present in IntelliJ IDEA 2025.x). The new suspend-based
 * API in 2026.1+ is detected and reported with a clear error pointing to the
 * unsupported handler — a follow-up can add a Kotlin shim for that bridge.
 *
 * <p>The converter handles classes/interfaces/enums/annotations, methods, fields,
 * generics, Java 8+ features (lambdas, streams), and automatic import management.
 * Some advanced constructs may need manual adjustment after conversion.
 *
 * <p>Inspired by jetbrains-index-mcp-plugin's {@code ConvertJavaToKotlinTool}.
 */
public final class ConvertJavaToKotlinTool extends RefactoringTool {

    private static final Logger LOG = Logger.getInstance(ConvertJavaToKotlinTool.class);
    private static final String PARAM_FILES = "files";
    private static final String NEW_HANDLER_FQN = "org.jetbrains.kotlin.idea.actions.JavaToKotlinActionHandler";
    private static final String OLD_HANDLER_FQN = "org.jetbrains.kotlin.idea.actions.JavaToKotlinAction$Handler";
    private static final String CONVERT_FILES_DEFAULT = "convertFiles$default";
    private static final String NO_KOTLIN_FILE_PRODUCED = "Conversion did not produce a Kotlin file";

    private final boolean hasKotlin;

    public ConvertJavaToKotlinTool(Project project, boolean hasKotlin) {
        super(project);
        this.hasKotlin = hasKotlin;
    }

    @Override
    public @NotNull String id() {
        return "convert_java_to_kotlin";
    }

    @Override
    public @NotNull String displayName() {
        return "Convert Java to Kotlin";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "convert_java_to_kotlin {files}";
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RefactorRenderer.INSTANCE;
    }

    @Override
    public @NotNull String description() {
        return "Convert Java files to Kotlin using IntelliJ's bundled J2K converter. " +
            "Creates .kt files alongside the originals, formats and optimizes imports, and deletes " +
            "the original .java files on success. Some advanced constructs (e.g. complex generics, " +
            "static initializers) may need manual adjustment after conversion. " +
            "Requires the Kotlin plugin to be installed and enabled for the target module.";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.required(PARAM_FILES, TYPE_ARRAY,
                "List of Java file paths (absolute or project-relative) to convert.")
        );
        addArrayItems(s, PARAM_FILES);
        return s;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!hasKotlin) {
            return ToolUtils.ERROR_PREFIX + "convert_java_to_kotlin requires the Kotlin plugin to be installed";
        }
        if (!args.has(PARAM_FILES) || !args.get(PARAM_FILES).isJsonArray()) {
            return ToolUtils.ERROR_PREFIX + "'files' parameter (array of paths) is required";
        }
        List<String> requestedPaths = parseFilesArray(args.get(PARAM_FILES).getAsJsonArray());
        if (requestedPaths.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "'files' must contain at least one path";
        }

        String[] holder = new String[1];
        // invokeAndWait runs inline when already on the EDT (tests) and otherwise
        // blocks the calling thread until the EDT task completes — which is exactly
        // the semantics we want for synchronous tool invocation.
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                holder[0] = convertAll(requestedPaths);
            } catch (Exception e) {
                LOG.warn("convert_java_to_kotlin failed", e);
                holder[0] = ToolUtils.ERROR_PREFIX + "Conversion failed: " + e.getMessage();
            }
        });
        return holder[0];
    }

    private List<String> parseFilesArray(JsonArray arr) {
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (el != null && el.isJsonPrimitive()) {
                String s = el.getAsString();
                if (s != null && !s.isBlank()) out.add(s);
            }
        }
        return out;
    }

    /**
     * Resolves all requested paths, groups by module, then dispatches each group to the
     * detected converter API. Returns a human-readable summary.
     */
    private String convertAll(List<String> requestedPaths) {
        // Pass 1: resolve virtual files outside ReadAction (refreshAndFindVirtualFile
        // emits VFS events that require a write lock — illegal inside a ReadAction).
        List<ResolvedPath> resolved = new ArrayList<>(requestedPaths.size());
        for (String requested : requestedPaths) {
            VirtualFile vf = resolveVirtualFile(requested);
            if (vf == null) vf = refreshAndFindVirtualFile(requested);
            resolved.add(new ResolvedPath(requested, vf));
        }

        // Pass 2: PSI inspection inside ReadAction.
        List<Target> targets = ReadAction.compute(() -> resolveTargets(resolved));
        Map<Module, List<Target>> byModule = groupByModule(targets);

        if (!byModule.isEmpty()) {
            ConverterApi api = detectConverterApi();
            if (api == null) {
                return ToolUtils.ERROR_PREFIX + "Kotlin J2K handler not found on classpath. " +
                    "Ensure the Kotlin plugin is installed and enabled.";
            }
            if (api.isNew()) {
                return ToolUtils.ERROR_PREFIX +
                    "This IDE uses the suspend-based J2K API (IDEA 2026.1+) which is not yet supported. " +
                    "Please run the conversion manually via Code → Convert Java File to Kotlin File.";
            }
            for (Map.Entry<Module, List<Target>> entry : byModule.entrySet()) {
                convertModule(api, entry.getKey(), entry.getValue());
            }

            ApplicationManager.getApplication().invokeAndWait(() -> {
                PsiDocumentManager.getInstance(project).commitAllDocuments();
                FileDocumentManager.getInstance().saveAllDocuments();
            });
        }

        return formatSummary(targets);
    }

    private void convertModule(ConverterApi api, Module module, List<Target> targets) {
        try {
            List<PsiJavaFile> javaFiles = new ArrayList<>(targets.size());
            for (Target t : targets) javaFiles.add(t.javaFile);
            Object result = invokeOldApi(api, javaFiles, module);
            matchOldApiResults(result, targets);
        } catch (Exception e) {
            LOG.warn("J2K conversion failed for module " + module.getName(), e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            for (Target t : targets) {
                if (t.status == Status.PENDING) {
                    t.status = Status.FAILED;
                    t.reason = "Conversion error: " + reason;
                }
            }
        }
    }

    private List<Target> resolveTargets(List<ResolvedPath> resolved) {
        List<Target> out = new ArrayList<>(resolved.size());
        PsiManager psiManager = PsiManager.getInstance(project);
        for (ResolvedPath rp : resolved) {
            out.add(resolveOne(psiManager, rp));
        }
        return out;
    }

    private Target resolveOne(PsiManager psiManager, ResolvedPath rp) {
        Target t = new Target(rp.requested());
        VirtualFile vf = rp.virtualFile();
        if (vf == null) {
            t.skip("File not found");
            return t;
        }
        PsiFile psi = psiManager.findFile(vf);
        if (!(psi instanceof PsiJavaFile javaFile)) {
            t.skip("Not a Java file (.java extension required)");
            return t;
        }
        t.javaFile = javaFile;
        t.javaPath = vf.getPath();
        t.expectedKotlinPath = vf.getPath().substring(0, vf.getPath().length() - ".java".length()) + ".kt";
        t.module = ModuleUtilCore.findModuleForPsiElement(javaFile);
        if (t.module == null) {
            t.skip("No module found for file");
        }
        return t;
    }

    private Map<Module, List<Target>> groupByModule(List<Target> targets) {
        Map<Module, List<Target>> byModule = new LinkedHashMap<>();
        for (Target t : targets) {
            if (t.status == Status.PENDING && t.module != null) {
                byModule.computeIfAbsent(t.module, m -> new ArrayList<>()).add(t);
            }
        }
        return byModule;
    }

    private Object invokeOldApi(ConverterApi api, List<PsiJavaFile> javaFiles, Module module)
        throws ReflectiveOperationException {
        // Old API: JavaToKotlinAction.Handler.convertFiles$default
        // Two known overloads:
        //   - 7 real params (with forceUsingOldJ2k) → $default has 10 params
        //   - 6 real params                          → $default has 9 params
        Method tenParam = findDefaultMethod(api.handlerClass(), 10);
        Method nineParam = findDefaultMethod(api.handlerClass(), 9);
        if (tenParam == null && nineParam == null) {
            throw new NoSuchMethodException(
                CONVERT_FILES_DEFAULT + " not found on " + api.handlerClass().getName());
        }
        try {
            if (tenParam != null) {
                // (instance, files, project, module, enableExternal, askExternal,
                //  forceUsingOldJ2k(defaulted), settings(defaulted), mask=96, marker)
                return tenParam.invoke(null, api.instance(), javaFiles, project, module,
                    Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, null, 96, null);
            }
            // (instance, files, project, module, enableExternal, askExternal,
            //  settings(defaulted), mask=32, marker)
            return nineParam.invoke(null, api.instance(), javaFiles, project, module,
                Boolean.TRUE, Boolean.FALSE, null, 32, null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private static Method findDefaultMethod(Class<?> handlerClass, int paramCount) {
        for (Method m : handlerClass.getDeclaredMethods()) {
            if (CONVERT_FILES_DEFAULT.equals(m.getName()) && m.getParameterCount() == paramCount) {
                // Accessing package-private generated $default method on Kotlin plugin classes —
                // unavoidable for cross-version reflection without a hard dependency on the Kotlin SDK.
                @SuppressWarnings("java:S3011")
                boolean accessible = m.trySetAccessible();
                if (!accessible) {
                    LOG.debug("trySetAccessible returned false for " + m);
                }
                return m;
            }
        }
        return null;
    }

    /**
     * Old API returns {@code List<KtFile>}. Match each KtFile back to a target by
     * comparing virtual file paths (uses reflection to avoid a compile-time dep on KtFile).
     */
    private void matchOldApiResults(Object result, List<Target> targets) {
        if (!(result instanceof List<?> converted)) {
            failPendingTargets(targets, "Converter returned no files");
            return;
        }
        Map<String, Target> byKtPath = indexPendingByKtPath(targets);
        markFromKtFiles(converted, byKtPath);
        for (Target t : byKtPath.values()) {
            if (t.status == Status.PENDING) markByFilesystem(t);
        }
    }

    private static void failPendingTargets(List<Target> targets, String reason) {
        for (Target t : targets) {
            if (t.status == Status.PENDING) t.fail(reason);
        }
    }

    private static Map<String, Target> indexPendingByKtPath(List<Target> targets) {
        Map<String, Target> byKtPath = new HashMap<>();
        for (Target t : targets) {
            if (t.status == Status.PENDING && t.expectedKotlinPath != null) {
                byKtPath.put(t.expectedKotlinPath, t);
            }
        }
        return byKtPath;
    }

    private void markFromKtFiles(List<?> converted, Map<String, Target> byKtPath) {
        for (Object ktFile : converted) {
            VirtualFile vf = ktFileVirtualFile(ktFile);
            if (vf == null) continue;
            Target t = byKtPath.remove(vf.getPath());
            if (t != null) markConverted(t, vf);
        }
    }

    private static @Nullable VirtualFile ktFileVirtualFile(Object ktFile) {
        if (ktFile == null) return null;
        try {
            Object vf = ktFile.getClass().getMethod("getVirtualFile").invoke(ktFile);
            return vf instanceof VirtualFile virtualFile ? virtualFile : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private void markByFilesystem(Target t) {
        if (t.expectedKotlinPath == null) {
            t.fail(NO_KOTLIN_FILE_PRODUCED);
            return;
        }
        VirtualFile kt = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.expectedKotlinPath);
        if (kt != null) {
            markConverted(t, kt);
        } else {
            t.fail(NO_KOTLIN_FILE_PRODUCED);
        }
    }

    private void markConverted(Target t, VirtualFile ktFile) {
        t.status = Status.CONVERTED;
        t.kotlinPath = ktFile.getPath();
        t.javaDeleted = LocalFileSystem.getInstance().findFileByPath(t.javaPath) == null;
    }

    private String formatSummary(List<Target> targets) {
        int converted = 0;
        int skipped = 0;
        int failed = 0;
        StringBuilder sb = new StringBuilder();
        String basePath = project.getBasePath();
        for (Target t : targets) {
            switch (t.status) {
                case CONVERTED -> converted++;
                case SKIPPED -> skipped++;
                case FAILED, PENDING -> failed++;
            }
            sb.append("- ").append(t.requestedPath).append(": ");
            if (t.status == Status.CONVERTED) {
                String rel = basePath != null ? relativize(basePath, t.kotlinPath) : t.kotlinPath;
                sb.append("converted → ").append(rel);
                if (t.javaDeleted) sb.append(" (.java deleted)");
            } else {
                sb.append(t.status.name().toLowerCase());
                if (t.reason != null) sb.append(": ").append(t.reason);
            }
            sb.append('\n');
        }
        String header = String.format("Converted %d, skipped %d, failed %d (of %d)%n%n",
            converted, skipped, failed, targets.size());
        return header + sb;
    }

    /**
     * Detects the available J2K API by class lookup. Returns {@code null} when the Kotlin
     * plugin is not on the classpath.
     */
    private static @Nullable ConverterApi detectConverterApi() {
        ConverterApi newApi = tryLoadHandler(NEW_HANDLER_FQN, true);
        if (newApi != null) return newApi;
        return tryLoadHandler(OLD_HANDLER_FQN, false);
    }

    private static @Nullable ConverterApi tryLoadHandler(String fqn, boolean isNew) {
        try {
            Class<?> cls = Class.forName(fqn);
            Object instance = cls.getField("INSTANCE").get(null);
            return new ConverterApi(cls, instance, isNew);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    private enum Status {PENDING, CONVERTED, SKIPPED, FAILED}

    private record ResolvedPath(String requested, @Nullable VirtualFile virtualFile) {
    }

    private static final class Target {
        final String requestedPath;
        Status status = Status.PENDING;
        String reason;
        PsiJavaFile javaFile;
        String javaPath;
        String expectedKotlinPath;
        String kotlinPath;
        Module module;
        boolean javaDeleted;

        Target(String requestedPath) {
            this.requestedPath = requestedPath;
        }

        void skip(String why) {
            this.status = Status.SKIPPED;
            this.reason = why;
        }

        void fail(String why) {
            this.status = Status.FAILED;
            this.reason = why;
        }
    }

    private record ConverterApi(Class<?> handlerClass, Object instance, boolean isNew) {
    }
}
