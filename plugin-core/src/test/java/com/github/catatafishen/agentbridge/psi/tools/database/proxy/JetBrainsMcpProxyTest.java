package com.github.catatafishen.agentbridge.psi.tools.database.proxy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JetBrainsMcpProxyTest {

    private static final Class<?>[] PARAMS = {String.class, boolean.class};

    // The fake services below are only ever called through reflection, so the IDE reports their
    // methods as unused; the suppression is intentional.
    @SuppressWarnings("unused")
    private static final class PublicNameService {
        public Object getMcpTools(String filter, boolean flag) {
            return filter + flag;
        }
    }

    @SuppressWarnings("unused")
    private static final class MangledNameService {
        Object getMcpTools$intellij_mcpserver(String filter, boolean flag) {
            return filter + flag;
        }
    }

    @SuppressWarnings("unused")
    private static final class BothNamesService {
        public Object getMcpTools(String filter, boolean flag) {
            return filter + flag;
        }

        Object getMcpTools$intellij_mcpserver(String filter, boolean flag) {
            return filter + flag;
        }
    }

    @SuppressWarnings("unused")
    private static final class OtherSignatureService {
        public Object getMcpTools(String filter) {
            return filter;
        }
    }

    @Test
    void findsPublicMethodIntroducedInBuild262() {
        Method method = JetBrainsMcpProxy.findDeclaredMethod(PublicNameService.class, PARAMS);

        assertNotNull(method);
        assertEquals("getMcpTools", method.getName());
    }

    @Test
    void findsKotlinMangledMethodFromEarlierBuilds() {
        Method method = JetBrainsMcpProxy.findDeclaredMethod(MangledNameService.class, PARAMS);

        assertNotNull(method);
        assertEquals("getMcpTools$intellij_mcpserver", method.getName());
    }

    @Test
    void prefersPlainNameOverMangledName() {
        Method method = JetBrainsMcpProxy.findDeclaredMethod(BothNamesService.class, PARAMS);

        assertNotNull(method);
        assertEquals("getMcpTools", method.getName());
    }

    @Test
    void returnsNullWhenNoOverloadMatchesParameterTypes() {
        assertNull(JetBrainsMcpProxy.findDeclaredMethod(OtherSignatureService.class, PARAMS));
    }

    // The four base parameters stand in for filter, boolean, implementation and session options.
    private static final Class<?>[] BASE_PARAMS = {String.class, boolean.class, Integer.class, Long.class};

    private enum FakeInvocationMode {DIRECT}

    /**
     * 2026.1 and earlier: module-internal (mangled) and no invocation mode.
     */
    @SuppressWarnings("unused")
    private static final class Pre262Service {
        Object getMcpTools$intellij_mcpserver(String filter, boolean flag, Integer impl, Long options) {
            return filter;
        }
    }

    /**
     * Build 262: public, with the invocation mode as a fifth parameter.
     */
    @SuppressWarnings("unused")
    private static final class Build262Service {
        public Object getMcpTools(String filter, boolean flag, Integer impl, Long options, FakeInvocationMode mode) {
            return filter;
        }
    }

    /**
     * Early 2026.2 builds: still mangled, already with the invocation mode.
     */
    @SuppressWarnings("unused")
    private static final class EarlyMangledFiveParamService {
        Object getMcpTools$intellij_mcpserver(String filter, boolean flag, Integer impl, Long options,
                                              FakeInvocationMode mode) {
            return filter;
        }
    }

    @Test
    void usesTheFourParameterOverloadWhenTheInvocationModeClassIsAbsent() {
        // 2026.1: McpToolInvocationMode does not exist, so there is no class to pass. Discovery must
        // still find the old overload instead of failing on the missing class.
        Method method = JetBrainsMcpProxy.findGetMcpTools(Pre262Service.class, BASE_PARAMS, null);

        assertNotNull(method);
        assertEquals("getMcpTools$intellij_mcpserver", method.getName());
        assertEquals(4, method.getParameterCount());
    }

    @Test
    void fallsBackToTheFourParameterOverloadWhenTheInvocationModeClassExistsButTheOverloadDoesNot() {
        Method method = JetBrainsMcpProxy.findGetMcpTools(Pre262Service.class, BASE_PARAMS, FakeInvocationMode.class);

        assertNotNull(method);
        assertEquals(4, method.getParameterCount());
    }

    @Test
    void prefersTheFiveParameterOverloadWhenTheInvocationModeClassExists() {
        Method method = JetBrainsMcpProxy.findGetMcpTools(Build262Service.class, BASE_PARAMS, FakeInvocationMode.class);

        assertNotNull(method);
        assertEquals("getMcpTools", method.getName());
        assertEquals(5, method.getParameterCount());
    }

    @Test
    void findsTheMangledFiveParameterOverloadOfEarly2026_2Builds() {
        Method method = JetBrainsMcpProxy.findGetMcpTools(
            EarlyMangledFiveParamService.class, BASE_PARAMS, FakeInvocationMode.class);

        assertNotNull(method);
        assertEquals("getMcpTools$intellij_mcpserver", method.getName());
        assertEquals(5, method.getParameterCount());
    }

    @Test
    void aFiveParameterOnlyServiceGivesNullWithoutTheInvocationModeClass() {
        assertNull(JetBrainsMcpProxy.findGetMcpTools(Build262Service.class, BASE_PARAMS, null));
    }

    @Test
    void loadOptionalClassReturnsNullForAMissingClass() {
        ClassLoader loader = JetBrainsMcpProxyTest.class.getClassLoader();

        assertNull(JetBrainsMcpProxy.loadOptionalClass("com.intellij.mcpserver.DoesNotExistInOlderPlugins", loader));
        assertEquals(String.class, JetBrainsMcpProxy.loadOptionalClass("java.lang.String", loader));
    }
}
