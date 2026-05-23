package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for static utility methods in {@link HookEnvironmentProvider}.
 */
class HookEnvironmentProviderTest {

    @Nested
    class GetArgumentEnvironment {
        @Test
        void emptyArgs() {
            var env = HookEnvironmentProvider.getArgumentEnvironment(new JsonObject());
            assertTrue(env.isEmpty());
        }

        @Test
        void stringPrimitive() {
            var args = new JsonObject();
            args.addProperty("path", "/tmp/file.txt");
            var env = HookEnvironmentProvider.getArgumentEnvironment(args);
            assertEquals("/tmp/file.txt", env.get("HOOK_ARG_path"));
        }

        @Test
        void numberPrimitive() {
            var args = new JsonObject();
            args.addProperty("line", 42);
            var env = HookEnvironmentProvider.getArgumentEnvironment(args);
            assertEquals("42", env.get("HOOK_ARG_line"));
        }

        @Test
        void booleanPrimitive() {
            var args = new JsonObject();
            args.addProperty("force", true);
            var env = HookEnvironmentProvider.getArgumentEnvironment(args);
            assertEquals("true", env.get("HOOK_ARG_force"));
        }

        @Test
        void skipsJsonObject() {
            var args = new JsonObject();
            var nested = new JsonObject();
            nested.addProperty("x", 1);
            args.add("nested", nested);
            var env = HookEnvironmentProvider.getArgumentEnvironment(args);
            assertFalse(env.containsKey("HOOK_ARG_nested"));
        }

        @Test
        void skipsJsonArray() {
            var args = new JsonObject();
            var arr = new JsonArray();
            arr.add("a");
            args.add("items", arr);
            var env = HookEnvironmentProvider.getArgumentEnvironment(args);
            assertFalse(env.containsKey("HOOK_ARG_items"));
        }

        @Test
        void skipsJsonNull() {
            var args = new JsonObject();
            args.add("val", JsonNull.INSTANCE);
            var env = HookEnvironmentProvider.getArgumentEnvironment(args);
            assertFalse(env.containsKey("HOOK_ARG_val"));
        }

        @Test
        void multiplePrimitives() {
            var args = new JsonObject();
            args.addProperty("a", "alpha");
            args.addProperty("b", "beta");
            var env = HookEnvironmentProvider.getArgumentEnvironment(args);
            assertEquals(2, env.size());
            assertEquals("alpha", env.get("HOOK_ARG_a"));
            assertEquals("beta", env.get("HOOK_ARG_b"));
        }

        @Test
        void mixedSkipsAndIncludes() {
            var args = new JsonObject();
            args.addProperty("keep", "yes");
            args.add("skip", new JsonObject());
            args.addProperty("alsoKeep", 99);
            var env = HookEnvironmentProvider.getArgumentEnvironment(args);
            assertEquals(2, env.size());
            assertTrue(env.containsKey("HOOK_ARG_keep"));
            assertTrue(env.containsKey("HOOK_ARG_alsoKeep"));
        }
    }

    @Nested
    class PutIfNonEmpty {
        @Test
        void addsNonEmptyList() {
            Map<String, String> env = new LinkedHashMap<>();
            HookEnvironmentProvider.putIfNonEmpty(env, "ROOTS", List.of("/a", "/b"));
            assertEquals("/a\n/b", env.get("ROOTS"));
        }

        @Test
        void skipsEmptyList() {
            Map<String, String> env = new LinkedHashMap<>();
            HookEnvironmentProvider.putIfNonEmpty(env, "ROOTS", Collections.emptyList());
            assertFalse(env.containsKey("ROOTS"));
        }

        @Test
        void skipsNullList() {
            Map<String, String> env = new LinkedHashMap<>();
            HookEnvironmentProvider.putIfNonEmpty(env, "ROOTS", null);
            assertFalse(env.containsKey("ROOTS"));
        }

        @Test
        void singleElementList() {
            Map<String, String> env = new LinkedHashMap<>();
            HookEnvironmentProvider.putIfNonEmpty(env, "KEY", List.of("only"));
            assertEquals("only", env.get("KEY"));
        }
    }
}
