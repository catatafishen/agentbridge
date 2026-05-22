package com.github.catatafishen.agentbridge.psi.tools.database.proxy;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Calls JetBrains' built-in MCP tools in-process, bypassing the HTTP transport layer.
 * <p>
 * JetBrains' {@code mcpserver} plugin ({@code com.intellij.mcpServer}) exposes tools as
 * {@code McpTool} instances collected by {@code McpServerService}. This proxy reaches
 * those tools directly via reflection — no HTTP round-trip, no session management.
 * <p>
 * <b>How it works:</b>
 * <ol>
 *   <li>Discovers the mcpserver plugin's class loader.</li>
 *   <li>Calls {@code McpServerService.getMcpTools$intellij_mcpserver(McpToolFilter, boolean,
 *       Implementation, McpSessionOptions)} (Kotlin module-internal — the {@code $} in the
 *       mangled name prevents direct calling, so reflection is required). {@code Implementation}
 *       and {@code McpSessionOptions} are constructed explicitly to avoid relying on
 *       {@code getTheOnlySession()}, which fails at startup before any MCP client connects.</li>
 *   <li>Builds a {@code McpCallInfo} with the current project (all fields constructed via
 *       reflection to stay off the compile classpath).</li>
 *   <li>Wraps it in {@code McpCallAdditionalDataElement} (a {@code CoroutineContext.Element}).</li>
 *   <li>Bridges the Kotlin suspend function via
 *       {@code kotlinx.coroutines.BuildersKt.runBlocking()}.</li>
 * </ol>
 * <p>
 * Only available in IntelliJ 2026.1+ where the {@code com.intellij.mcpServer} plugin ships.
 */
final class JetBrainsMcpProxy {

    private static final Logger LOG = Logger.getInstance(JetBrainsMcpProxy.class);
    private static final String MCPSERVER_PLUGIN_ID = "com.intellij.mcpServer";

    private static final AtomicReference<Map<String, Object>> toolCacheRef = new AtomicReference<>();
    private static final AtomicReference<ClassLoader> mcpClassLoaderRef = new AtomicReference<>();

    private JetBrainsMcpProxy() {
    }

    /**
     * Returns tool names actually registered in the running JetBrains MCP server.
     * Returns an empty list if the plugin is unavailable or loading fails.
     */
    static List<String> getRegisteredToolNames() {
        try {
            return List.copyOf(loadToolCache().keySet());
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable rootCause = e.getCause() != null ? e.getCause() : e;
            LOG.warn("JetBrains MCP tools unavailable — database tools will not be registered. " +
                "Check that the JetBrains AI Assistant plugin is installed.", rootCause);
            return Collections.emptyList();
        }
    }

    /**
     * Calls a JetBrains MCP tool in-process and returns its text result.
     *
     * @param project  current project (injected into {@code McpCallInfo})
     * @param toolName exact JetBrains tool name (e.g. {@code "execute_sql_query"})
     * @param argsJson JSON object string with the tool's parameters
     * @return tool result text; prefixed with {@code "Error: "} if the tool returned an error
     * @throws IllegalArgumentException     if the tool is not registered in the MCP server
     * @throws ReflectiveOperationException if in-process invocation fails
     */
    static String callTool(Project project, String toolName, String argsJson)
        throws ReflectiveOperationException {
        Map<String, Object> tools = loadToolCache();
        Object tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("JetBrains MCP tool not found: " + toolName);
        }
        return invokeInProcess(mcpClassLoaderRef.get(), project, tool, argsJson);
    }

    // ── Internal machinery ──────────────────────────────────────────────────

    private static Map<String, Object> loadToolCache() throws ReflectiveOperationException {
        Map<String, Object> cached = toolCacheRef.get();
        if (cached != null) return cached;
        synchronized (JetBrainsMcpProxy.class) {
            cached = toolCacheRef.get();
            if (cached != null) return cached;
            ClassLoader cl = findMcpClassLoader();
            if (cl == null) {
                // mcpserver plugin not installed — cache an empty map so callers degrade gracefully
                toolCacheRef.set(Collections.emptyMap());
                return Collections.emptyMap();
            }
            Map<String, Object> loaded = discoverTools(cl);
            mcpClassLoaderRef.set(cl);
            toolCacheRef.set(loaded);
            return loaded;
        }
    }

    private static @Nullable ClassLoader findMcpClassLoader() {
        return PlatformApiCompat.getPluginClassLoader(MCPSERVER_PLUGIN_ID);
    }

    private static Map<String, Object> discoverTools(ClassLoader cl) throws ReflectiveOperationException {
        // McpServerService.Companion.getInstance()
        Class<?> serviceClass = Class.forName("com.intellij.mcpserver.impl.McpServerService", true, cl);
        Object companion = serviceClass.getDeclaredField("Companion").get(null);
        Object service = companion.getClass().getDeclaredMethod("getInstance").invoke(companion);

        Class<?> mcpToolFilterClass = Class.forName("com.intellij.mcpserver.McpToolFilter", true, cl);
        Class<?> implementationClass = Class.forName(
            "io.modelcontextprotocol.kotlin.sdk.types.Implementation", true, cl);
        Class<?> sessionOptionsClass = Class.forName(
            "com.intellij.mcpserver.impl.McpServerService$McpSessionOptions", true, cl);

        // Construct Implementation explicitly.
        // The $default constructor (mask=28 = bits 2+3+4) lets Kotlin fill in defaults for
        // title, websiteUrl, and icons, so we only need to supply name and version.
        Class<?> markerClass = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker", true, cl);
        Constructor<?> implCtor = implementationClass.getDeclaredConstructor(
            String.class, String.class, String.class, String.class, List.class, int.class, markerClass);
        Object implementation = implCtor.newInstance("agentbridge", "1.0", null, null, null, 28, null);

        // Build McpSessionOptions manually. The $default variant of getMcpTools uses
        // getTheOnlySession() as its default value for McpSessionOptions, which throws at
        // startup before any MCP client has connected. Constructing the options explicitly
        // avoids that dependency.
        Object sessionOptions = buildSessionOptions(cl);

        // Call getMcpTools$intellij_mcpserver directly (NOT $default) with all four params.
        Method getToolsMethod = serviceClass.getDeclaredMethod(
            "getMcpTools$intellij_mcpserver",
            mcpToolFilterClass, boolean.class, implementationClass, sessionOptionsClass);
        // null filter = no filter, false = include all tools (not just hidden ones)
        List<?> tools = (List<?>) getToolsMethod.invoke(service, null, false, implementation, sessionOptions);

        Map<String, Object> map = new ConcurrentHashMap<>();
        for (Object tool : tools) {
            Object descriptor = tool.getClass().getMethod("getDescriptor").invoke(tool);
            String name = (String) descriptor.getClass().getMethod("getName").invoke(descriptor);
            map.put(name, tool);
        }
        return map;
    }

    private static String invokeInProcess(ClassLoader cl, Project project, Object tool, String argsJson)
        throws ReflectiveOperationException {
        Object argsJsonObject = parseJsonObject(cl, argsJson);
        Object mcpCallInfo = buildMcpCallInfo(cl, project, tool, argsJsonObject);
        Object coroutineContext = wrapInCoroutineContext(cl, mcpCallInfo);

        Class<?> jsonObjectClass = Class.forName("kotlinx.serialization.json.JsonObject", true, cl);
        Class<?> continuationClass = Class.forName("kotlin.coroutines.Continuation", true, cl);
        Method callMethod = findCallMethod(tool, jsonObjectClass, continuationClass);

        Class<?> buildersKtClass = Class.forName("kotlinx.coroutines.BuildersKt", true, cl);
        Class<?> coroutineContextClass = Class.forName("kotlin.coroutines.CoroutineContext", true, cl);
        Class<?> function2Class = Class.forName("kotlin.jvm.functions.Function2", true, cl);
        Method runBlocking = buildersKtClass.getDeclaredMethod(
            "runBlocking", coroutineContextClass, function2Class);

        Object result = runBlocking.invoke(null, coroutineContext,
            new com.github.catatafishen.agentbridge.psi.tools.database.proxy.McpToolCallable(callMethod, tool, argsJsonObject));
        return extractResultText(cl, result);
    }

    /**
     * Finds {@code McpTool.call()} walking up the class and interface hierarchy.
     */
    private static Method findCallMethod(Object tool, Class<?> jsonObjectClass, Class<?> continuationClass)
        throws NoSuchMethodException {
        Class<?> cls = tool.getClass();
        while (cls != null) {
            for (Class<?> iface : cls.getInterfaces()) {
                try {
                    return iface.getMethod("call", jsonObjectClass, continuationClass);
                } catch (NoSuchMethodException ignored) { //NOSONAR — expected during interface scan
                }
            }
            try {
                return cls.getMethod("call", jsonObjectClass, continuationClass);
            } catch (NoSuchMethodException ignored) { //NOSONAR — expected during class scan
            }
            cls = cls.getSuperclass();
        }
        throw new NoSuchMethodException(
            "McpTool.call(JsonObject, Continuation) not found on " + tool.getClass().getName());
    }

    private static Object buildMcpCallInfo(ClassLoader cl, Project project, Object tool, Object argsJsonObject)
        throws ReflectiveOperationException {

        Class<?> clientInfoClass = Class.forName("com.intellij.mcpserver.ClientInfo", true, cl);
        Object clientInfo = clientInfoClass.getDeclaredConstructor(String.class, String.class)
            .newInstance("agentbridge", "1.0");

        Object descriptor = tool.getClass().getMethod("getDescriptor").invoke(tool);
        Class<?> descriptorClass = Class.forName("com.intellij.mcpserver.McpToolDescriptor", true, cl);
        Class<?> jsonObjectClass = Class.forName("kotlinx.serialization.json.JsonObject", true, cl);
        Object emptyJsonObject = parseJsonObject(cl, "{}");
        Object sessionOptions = buildSessionOptions(cl);
        Class<?> sessionOptionsClass = Class.forName(
            "com.intellij.mcpserver.impl.McpServerService$McpSessionOptions", true, cl);

        Class<?> mcpCallInfoClass = Class.forName("com.intellij.mcpserver.McpCallInfo", true, cl);
        Constructor<?> ctor = mcpCallInfoClass.getDeclaredConstructor(
            int.class, clientInfoClass, Project.class, descriptorClass,
            jsonObjectClass, jsonObjectClass, sessionOptionsClass, Map.class);
        return ctor.newInstance(0, clientInfo, project, descriptor, argsJsonObject, emptyJsonObject,
            sessionOptions, Map.of());
    }

    private static Object buildSessionOptions(ClassLoader cl) throws ReflectiveOperationException {
        Class<?> sessionOptionsClass = Class.forName(
            "com.intellij.mcpserver.impl.McpServerService$McpSessionOptions", true, cl);
        Class<?> askModeClass = Class.forName(
            "com.intellij.mcpserver.impl.McpServerService$AskCommandExecutionMode", true, cl);
        Class<?> mcpToolFilterClass = Class.forName("com.intellij.mcpserver.McpToolFilter", true, cl);
        Class<?> markerClass = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker", true, cl);

        Object dontAsk = Stream.of(askModeClass.getEnumConstants())
            .filter(e -> "DONT_ASK".equals(e.toString()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("DONT_ASK enum constant not found"));

        // Use the 3-param $default constructor (AskCommandExecutionMode, McpToolFilter, String, int, Marker).
        // mask=6 (bits 1+2 set) → use Kotlin defaults for toolFilter (null) and localAgentId (null),
        // so we only supply commandExecutionMode. The 2-param $default constructor is deprecated since
        // McpSessionOptions gained a localAgentId: String? field; using the 3-param form is canonical.
        Constructor<?> ctor = sessionOptionsClass.getDeclaredConstructor(
            askModeClass, mcpToolFilterClass, String.class, int.class, markerClass);
        return ctor.newInstance(dontAsk, null, null, 6, null);
    }

    /**
     * Wraps {@code McpCallInfo} in {@code McpCallAdditionalDataElement} (a {@code CoroutineContext.Element}).
     */
    private static Object wrapInCoroutineContext(ClassLoader cl, Object mcpCallInfo)
        throws ReflectiveOperationException {
        Class<?> callInfoClass = Class.forName("com.intellij.mcpserver.McpCallInfo", true, cl);
        Class<?> elementClass = Class.forName(
            "com.intellij.mcpserver.McpCallAdditionalDataElement", true, cl);
        return elementClass.getDeclaredConstructor(callInfoClass).newInstance(mcpCallInfo);
    }

    private static Object parseJsonObject(ClassLoader cl, String json) throws ReflectiveOperationException {
        Class<?> jsonClass = Class.forName("kotlinx.serialization.json.Json", true, cl);
        Object jsonInstance;
        try {
            // Json.Default is the companion object — usually exposed as a static field
            Field defaultField = jsonClass.getField("Default");
            jsonInstance = defaultField.get(null);
        } catch (NoSuchFieldException e) {
            // Fallback: companion is compiled as Json$Default with an INSTANCE field
            Class<?> defaultClass = Class.forName("kotlinx.serialization.json.Json$Default", true, cl);
            jsonInstance = defaultClass.getField("INSTANCE").get(null);
        }
        return jsonClass.getMethod("parseToJsonElement", String.class).invoke(jsonInstance, json);
    }

    private static String extractResultText(ClassLoader cl, Object result) throws ReflectiveOperationException {
        Class<?> textContentClass = Class.forName(
            "com.intellij.mcpserver.McpToolCallResultContent$Text", true, cl);
        Object[] content = (Object[]) result.getClass().getMethod("getContent").invoke(result);
        boolean isError = (boolean) result.getClass().getMethod("isError").invoke(result);

        StringBuilder sb = new StringBuilder();
        for (Object item : content) {
            if (textContentClass.isInstance(item)) {
                String text = (String) item.getClass().getMethod("getText").invoke(item);
                sb.append(text);
            }
        }
        return isError ? "Error: " + sb : sb.toString();
    }
}
