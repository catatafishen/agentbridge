package com.github.catatafishen.agentbridge.psi.tools.database.proxy;

import java.lang.reflect.Method;

/**
 * Bridges Java reflection to the Kotlin coroutine system.
 * <p>
 * {@code kotlinx.coroutines.BuildersKt.runBlocking()} expects a
 * {@code Function2<CoroutineScope, Continuation<T>, Object>} block. This class implements
 * that functional interface using raw types (legal in Java) so we can delegate to
 * {@code McpTool.call(JsonObject, Continuation)} via reflection without requiring the
 * mcpserver plugin to be on the compile classpath.
 */
@SuppressWarnings("rawtypes")
// raw Function2 required — type params aren't available without mcpserver on compile classpath
final class McpToolCallable implements kotlin.jvm.functions.Function2 {

    private final Method callMethod;
    private final Object tool;
    private final Object argsJsonObject;

    McpToolCallable(Method callMethod, Object tool, Object argsJsonObject) {
        this.callMethod = callMethod;
        this.tool = tool;
        this.argsJsonObject = argsJsonObject;
    }

    /**
     * Called by the coroutine runtime inside {@code runBlocking}.
     * {@code scope} is the {@code CoroutineScope}; {@code continuation} is the current
     * coroutine continuation — both passed through to {@code McpTool.call()}.
     */
    @Override
    public Object invoke(Object scope, Object continuation) {
        try {
            return callMethod.invoke(tool, argsJsonObject, continuation);
        } catch (Exception e) {
            throw new IllegalStateException("McpTool.call() failed via reflection", e);
        }
    }
}
