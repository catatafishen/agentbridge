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
}
