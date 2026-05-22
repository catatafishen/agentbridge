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

    @Override
    public Object invoke(Object scope, Object continuation) {
        try {
            return callMethod.invoke(tool, argsJsonObject, continuation);
        } catch (Throwable e) {
            // Rethrow fatal JVM errors — wrapping them hides the root cause and may
            // interfere with expected JVM error-handling (e.g. OOM recovery, thread death).
            if (e instanceof VirtualMachineError || e instanceof ThreadDeath) throw (Error) e;
            throw new IllegalStateException("McpTool.call() failed via reflection", e);
        }
    }
}
