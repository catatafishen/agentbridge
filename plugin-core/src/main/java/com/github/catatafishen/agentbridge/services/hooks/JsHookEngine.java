package com.github.catatafishen.agentbridge.services.hooks;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs hook scripts written in JavaScript in-process using the embedded Mozilla Rhino engine.
 *
 * <p>This replaces spawning a {@code sh}/{@code powershell} subprocess for bundled hooks: a single
 * cross-platform {@code .js} file runs identically on every OS and JetBrains IDE, with no external
 * shell, PowerShell, or Node runtime. The script talks to the IDE through the {@code Hook} global
 * (see {@link HookHostApi}) and records its decision there; the engine converts that decision back
 * into the same stdout-JSON contract the subprocess protocol used, so
 * {@link HookExecutor#parseResult} handles both paths identically.
 *
 * <p><b>Sandbox.</b> Scripts run interpreted (optimization level {@code -1}) with a
 * {@link org.mozilla.javascript.ClassShutter} that makes only {@link HookHostApi} visible — every
 * other Java class (including {@code java.lang.Runtime}, {@code Class}, reflection) is hidden, so a
 * script cannot escape to the host. A per-call instruction-count deadline aborts runaway scripts.
 *
 * <p>If a sibling {@code _lib.js} exists next to the hook script, it is evaluated first in the same
 * scope, mirroring the {@code . _lib.sh} sourcing pattern so hooks can share helper functions.
 */
public final class JsHookEngine {

    private static final Logger LOG = Logger.getInstance(JsHookEngine.class);
    private static final String LIB_FILE = "_lib.js";
    private static final Object DEADLINE_KEY = new Object();
    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10_000;

    private static final ContextFactory FACTORY = new HookContextFactory();
    private static final ConcurrentHashMap<Path, CachedSource> SOURCE_CACHE = new ConcurrentHashMap<>();

    private JsHookEngine() {
    }

    /**
     * Evaluates an embedded JavaScript hook and returns its stdout-equivalent JSON
     * (empty string when the script recorded no decision).
     *
     * @throws IOException if the script cannot be read, times out, or throws a script error
     */
    public static @NotNull String evaluate(@NotNull Project project,
                                           @NotNull Path scriptPath,
                                           @NotNull HookEntryConfig entry,
                                           @NotNull HookPayload payload) throws IOException {
        String source = stripShebang(readSource(scriptPath));
        HookHostApi host = new HookHostApi(project, payload, entry.capabilities(), entry.timeout());

        Context cx = FACTORY.enterContext();
        try {
            cx.setClassShutter(HookClassShutter.INSTANCE);
            Scriptable scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "Hook", Context.javaToJS(host, scope));

            cx.putThreadLocal(DEADLINE_KEY, System.currentTimeMillis() + entry.timeout() * 1000L);

            Path lib = scriptPath.getParent().resolve(LIB_FILE);
            if (Files.isRegularFile(lib) && !lib.equals(scriptPath)) {
                cx.evaluateString(scope, stripShebang(readSource(lib)), LIB_FILE, 1, null);
            }
            cx.evaluateString(scope, source, scriptPath.getFileName().toString(), 1, null);
        } catch (TimeoutError e) {
            throw new IOException("Hook timed out after " + entry.timeout() + "s", e);
        } catch (RhinoException e) {
            throw new IOException("Hook script error: " + e.getMessage(), e);
        } finally {
            cx.removeThreadLocal(DEADLINE_KEY);
            Context.exit();
        }

        String result = host.resultJson();
        return result != null ? result : "";
    }

    /**
     * Returns true if the script at {@code scriptPath} is an embedded JS hook: a {@code .js} file
     * whose shebang does <em>not</em> request Node. Node-shebang {@code .js} files (e.g. the
     * internal bot-identity hook) keep running as external subprocesses.
     */
    static boolean isEmbeddedJsHook(@NotNull Path scriptPath) {
        String name = scriptPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".js")) return false;
        return !hasNodeShebang(scriptPath);
    }

    private static boolean hasNodeShebang(@NotNull Path scriptPath) {
        try (var reader = Files.newBufferedReader(scriptPath, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            return first != null && first.startsWith("#!") && first.contains("node");
        } catch (IOException e) {
            return false;
        }
    }

    private static @NotNull String readSource(@NotNull Path path) throws IOException {
        long mtime = Files.getLastModifiedTime(path).toMillis();
        CachedSource cached = SOURCE_CACHE.get(path);
        if (cached != null && cached.mtime == mtime) {
            return cached.source;
        }
        String source = Files.readString(path, StandardCharsets.UTF_8);
        SOURCE_CACHE.put(path, new CachedSource(mtime, source));
        return source;
    }

    /**
     * Rhino cannot parse a {@code #!} shebang line, so drop it if present.
     */
    private static @NotNull String stripShebang(@NotNull String source) {
        if (!source.startsWith("#!")) return source;
        int nl = source.indexOf('\n');
        return nl < 0 ? "" : source.substring(nl + 1);
    }

    private record CachedSource(long mtime, String source) {
    }

    /**
     * Makes only {@link HookHostApi} visible to scripts; everything else (Runtime, ProcessBuilder,
     * Class, reflection) is hidden, blocking sandbox escapes via LiveConnect.
     */
    private static final class HookClassShutter implements org.mozilla.javascript.ClassShutter {
        static final HookClassShutter INSTANCE = new HookClassShutter();
        private static final String ALLOWED = HookHostApi.class.getName();

        @Override
        public boolean visibleToScripts(String fullClassName) {
            return ALLOWED.equals(fullClassName);
        }
    }

    /**
     * Context factory that runs scripts interpreted (portable, observable) and enforces the
     * per-call deadline stored in {@link #DEADLINE_KEY}.
     */
    private static final class HookContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
            // Convert Java String/Boolean/Number returned by HookHostApi into JS primitives, so
            // scripts work with plain JS values and never trigger ClassShutter resolution of
            // java.lang.String (which would otherwise be blocked, breaking every hook).
            cx.getWrapFactory().setJavaPrimitiveWrap(false);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object deadline = cx.getThreadLocal(DEADLINE_KEY);
            if (deadline instanceof Long limit && System.currentTimeMillis() > limit) {
                throw new TimeoutError();
            }
        }
    }

    /**
     * Thrown by the instruction observer to abort a hook that exceeded its time budget.
     * Extends {@link Error} so it propagates cleanly through the Rhino interpreter loop.
     */
    private static final class TimeoutError extends Error {
        TimeoutError() {
            super("hook execution deadline exceeded");
        }
    }
}
