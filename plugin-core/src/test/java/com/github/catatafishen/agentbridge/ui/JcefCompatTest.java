package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JcefCompat}.
 *
 * <p>The {@code createJSQuery} method delegates directly to {@code JBCefJSQuery.create(browser)}
 * and cannot be exercised without a live IntelliJ JCEF environment (covered by integration tests).
 * This test verifies the structural contract: the class is a non-instantiable utility class and
 * that the factory method is declared with the expected signature.
 */
@DisplayName("JcefCompat")
class JcefCompatTest {

    @Nested
    @DisplayName("utility class contract")
    class UtilityClassContract {

        @Test
        @DisplayName("class is final")
        void classIsFinal() {
            assertTrue(Modifier.isFinal(JcefCompat.class.getModifiers()),
                "JcefCompat should be a final utility class");
        }

        @Test
        @DisplayName("private constructor prevents instantiation")
        void privateConstructorPreventsInstantiation() throws NoSuchMethodException {
            Constructor<JcefCompat> ctor = JcefCompat.class.getDeclaredConstructor();
            assertFalse(ctor.canAccess(null),
                "JcefCompat constructor should be private");
        }

        @Test
        @DisplayName("createJSQuery method is declared public and static")
        void createJsQueryIsPublicStatic() throws NoSuchMethodException {
            var method = JcefCompat.class.getDeclaredMethod(
                "createJSQuery", com.intellij.ui.jcef.JBCefBrowserBase.class);
            int mods = method.getModifiers();
            assertTrue(Modifier.isPublic(mods), "createJSQuery should be public");
            assertTrue(Modifier.isStatic(mods), "createJSQuery should be static");
        }
    }
}
