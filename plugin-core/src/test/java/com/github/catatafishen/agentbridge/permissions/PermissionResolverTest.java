package com.github.catatafishen.agentbridge.permissions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PermissionResolver")
class PermissionResolverTest {

    private AbuseDetector abuseDetector;
    private TestPermissionStore store;
    private PermissionResolver resolver;

    @BeforeEach
    void setUp() {
        abuseDetector = new AbuseDetector();
        store = new TestPermissionStore();
        resolver = new PermissionResolver(abuseDetector, store);
    }

    @Nested
    @DisplayName("abuse detection takes highest priority")
    class AbuseDetectionPriority {

        @Test
        @DisplayName("denies git via shell even when explicitly allowed")
        void deniesGitEvenWhenExplicitlyAllowed() {
            store.setExplicit("run_command", ToolPermission.ALLOW);
            store.addSessionAllowed("run_command");

            PermissionResolver.PermissionResult result = resolver.resolve(
                "run_command",
                "{\"command\": \"git status\"}",
                ToolCategory.EXECUTE,
                PermissionDefaults.PERMISSIVE
            );

            assertTrue(result.isDenied());
            assertNotNull(result.denialReason());
            assertTrue(result.denialReason().contains("git"));
        }

        @Test
        @DisplayName("denies grep via shell")
        void deniesGrepViaShell() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "run_command",
                "{\"command\": \"grep -r pattern src/\"}",
                ToolCategory.EXECUTE,
                PermissionDefaults.STANDARD
            );

            assertTrue(result.isDenied());
            assertTrue(result.denialReason().contains("search_text"));
        }
    }

    @Nested
    @DisplayName("explicit per-tool overrides")
    class ExplicitOverrides {

        @Test
        @DisplayName("explicit ALLOW overrides category default")
        void explicitAllowOverridesDefault() {
            store.setExplicit("git_commit", ToolPermission.ALLOW);

            PermissionResolver.PermissionResult result = resolver.resolve(
                "git_commit", null, ToolCategory.GIT_WRITE, PermissionDefaults.STANDARD
            );

            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("explicit DENY overrides session allow")
        void explicitDenyOverridesSession() {
            store.setExplicit("run_command", ToolPermission.DENY);
            store.addSessionAllowed("run_command");

            PermissionResolver.PermissionResult result = resolver.resolve(
                "run_command", null, ToolCategory.EXECUTE, PermissionDefaults.PERMISSIVE
            );

            assertTrue(result.isDenied());
        }

        @Test
        @DisplayName("explicit ASK is returned")
        void explicitAskReturned() {
            store.setExplicit("edit_text", ToolPermission.ASK);

            PermissionResolver.PermissionResult result = resolver.resolve(
                "edit_text", null, ToolCategory.EDIT, PermissionDefaults.PERMISSIVE
            );

            assertEquals(ToolPermission.ASK, result.permission());
        }
    }

    @Nested
    @DisplayName("session-level cache")
    class SessionCache {

        @Test
        @DisplayName("session allow overrides category default")
        void sessionAllowOverridesDefault() {
            store.addSessionAllowed("run_command");

            PermissionResolver.PermissionResult result = resolver.resolve(
                "run_command",
                "{\"command\": \"npm test\"}",
                ToolCategory.EXECUTE,
                PermissionDefaults.STANDARD // EXECUTE defaults to ASK
            );

            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("allowForSession adds to session cache")
        void allowForSessionAddsToCache() {
            resolver.allowForSession("delete_file");

            PermissionResolver.PermissionResult result = resolver.resolve(
                "delete_file", null, ToolCategory.DESTRUCTIVE, PermissionDefaults.STANDARD
            );

            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("clearSession removes session permissions")
        void clearSessionRemoves() {
            resolver.allowForSession("delete_file");
            resolver.clearSession();

            PermissionResolver.PermissionResult result = resolver.resolve(
                "delete_file", null, ToolCategory.DESTRUCTIVE, PermissionDefaults.STANDARD
            );

            // Should fall through to defaults — DESTRUCTIVE = DENY
            assertTrue(result.isDenied());
        }
    }

    @Nested
    @DisplayName("category defaults fallthrough")
    class CategoryDefaults {

        @Test
        @DisplayName("READ category defaults to ALLOW in STANDARD")
        void readDefaultsToAllow() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "read_file", null, ToolCategory.READ, PermissionDefaults.STANDARD
            );
            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("EXECUTE category defaults to ASK in STANDARD")
        void executeDefaultsToAsk() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "run_tests", null, ToolCategory.EXECUTE, PermissionDefaults.STANDARD
            );
            assertEquals(ToolPermission.ASK, result.permission());
        }

        @Test
        @DisplayName("DESTRUCTIVE category defaults to DENY in STANDARD")
        void destructiveDefaultsToDeny() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "delete_file", null, ToolCategory.DESTRUCTIVE, PermissionDefaults.STANDARD
            );
            assertTrue(result.isDenied());
        }

        @Test
        @DisplayName("PERMISSIVE defaults allow EXECUTE")
        void permissiveAllowsExecute() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "run_tests", null, ToolCategory.EXECUTE, PermissionDefaults.PERMISSIVE
            );
            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("PERMISSIVE defaults ASK for DESTRUCTIVE")
        void permissiveAsksForDestructive() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "delete_file", null, ToolCategory.DESTRUCTIVE, PermissionDefaults.PERMISSIVE
            );
            assertEquals(ToolPermission.ASK, result.permission());
        }

        @Test
        @DisplayName("OTHER category defaults to ASK")
        void otherDefaultsToAsk() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "unknown_tool", null, ToolCategory.OTHER, PermissionDefaults.STANDARD
            );
            assertEquals(ToolPermission.ASK, result.permission());
        }
    }

    @Nested
    @DisplayName("path-scope aware resolution")
    class PathScopeAware {

        @Test
        @DisplayName("outside-project READ uses readToolsOutside default (ASK in STANDARD)")
        void outsideReadDefaultsToAsk() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "read_file", null, ToolCategory.READ,
                PathScope.OUTSIDE_PROJECT, PermissionDefaults.STANDARD
            );
            assertEquals(ToolPermission.ASK, result.permission());
        }

        @Test
        @DisplayName("inside-project READ still uses readTools default (ALLOW in STANDARD)")
        void insideReadDefaultsToAllow() {
            PermissionResolver.PermissionResult result = resolver.resolve(
                "read_file", null, ToolCategory.READ,
                PathScope.INSIDE_PROJECT, PermissionDefaults.STANDARD
            );
            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("explicit outside override is preferred over inside override")
        void explicitOutsideTakesPrecedence() {
            store.setExplicit("read_file", ToolPermission.ALLOW);                              // inside
            store.setExplicitScoped("read_file", PathScope.OUTSIDE_PROJECT, ToolPermission.DENY); // outside

            PermissionResolver.PermissionResult inside = resolver.resolve(
                "read_file", null, ToolCategory.READ,
                PathScope.INSIDE_PROJECT, PermissionDefaults.STANDARD
            );
            PermissionResolver.PermissionResult outside = resolver.resolve(
                "read_file", null, ToolCategory.READ,
                PathScope.OUTSIDE_PROJECT, PermissionDefaults.STANDARD
            );
            assertTrue(inside.isAllowed());
            assertTrue(outside.isDenied());
        }

        @Test
        @DisplayName("outside lookup falls back to legacy unscoped override when no outside override exists")
        void outsideFallsBackToLegacyKey() {
            // Only the legacy unscoped key is set — outside resolution should still see it.
            store.setExplicit("read_file", ToolPermission.DENY);

            PermissionResolver.PermissionResult result = resolver.resolve(
                "read_file", null, ToolCategory.READ,
                PathScope.OUTSIDE_PROJECT, PermissionDefaults.STANDARD
            );
            assertTrue(result.isDenied());
        }

        @Test
        @DisplayName("session allow inside-project does NOT leak to outside-project")
        void sessionAllowDoesNotLeakAcrossScopes() {
            resolver.allowForSession("read_file", PathScope.INSIDE_PROJECT);

            PermissionResolver.PermissionResult outside = resolver.resolve(
                "read_file", null, ToolCategory.READ,
                PathScope.OUTSIDE_PROJECT, PermissionDefaults.STANDARD
            );
            // Outside falls through to the readToolsOutside default = ASK.
            assertEquals(ToolPermission.ASK, outside.permission());
        }

        @Test
        @DisplayName("session allow outside-project applies only to outside calls")
        void sessionAllowOutsideAppliesOnlyOutside() {
            resolver.allowForSession("read_file", PathScope.OUTSIDE_PROJECT);

            PermissionResolver.PermissionResult outside = resolver.resolve(
                "read_file", null, ToolCategory.READ,
                PathScope.OUTSIDE_PROJECT, PermissionDefaults.STANDARD
            );
            assertTrue(outside.isAllowed());
        }

        @Test
        @DisplayName("legacy resolve(...) overload behaves like NOT_APPLICABLE")
        void legacyOverloadUsesNotApplicable() {
            // No explicit override, no session allow — legacy overload should match the inside default.
            PermissionResolver.PermissionResult legacy = resolver.resolve(
                "read_file", null, ToolCategory.READ, PermissionDefaults.STANDARD
            );
            PermissionResolver.PermissionResult scoped = resolver.resolve(
                "read_file", null, ToolCategory.READ,
                PathScope.NOT_APPLICABLE, PermissionDefaults.STANDARD
            );
            assertEquals(scoped.permission(), legacy.permission());
            assertTrue(legacy.isAllowed());
        }
    }

    /**
     * In-memory PermissionStore stub for testing without IntelliJ platform.
     */
    private static class TestPermissionStore extends PermissionStore {
        private final java.util.Map<String, ToolPermission> explicit = new java.util.HashMap<>();
        private final java.util.Set<String> sessionAllowed = new java.util.HashSet<>();

        TestPermissionStore() {
            super(null); // We override all methods, so null is fine
        }

        private static String key(String toolId, PathScope scope) {
            return toolId + "|" + (scope == PathScope.OUTSIDE_PROJECT ? "outside" : "inside");
        }

        void setExplicit(String toolId, ToolPermission permission) {
            explicit.put(key(toolId, PathScope.INSIDE_PROJECT), permission);
        }

        void setExplicitScoped(String toolId, PathScope scope, ToolPermission permission) {
            explicit.put(key(toolId, scope), permission);
        }

        void addSessionAllowed(String toolId) {
            sessionAllowed.add(key(toolId, PathScope.INSIDE_PROJECT));
        }

        @Override
        public ToolPermission getExplicitPermission(String toolId) {
            return explicit.get(key(toolId, PathScope.INSIDE_PROJECT));
        }

        @Override
        public ToolPermission getExplicitPermission(String toolId, PathScope scope) {
            return explicit.get(key(toolId, scope));
        }

        @Override
        public void setExplicitPermission(String toolId, ToolPermission permission) {
            explicit.put(key(toolId, PathScope.INSIDE_PROJECT), permission);
        }

        @Override
        public void setExplicitPermission(String toolId, PathScope scope, ToolPermission permission) {
            explicit.put(key(toolId, scope), permission);
        }

        @Override
        public void removeExplicitPermission(String toolId) {
            explicit.remove(key(toolId, PathScope.INSIDE_PROJECT));
        }

        @Override
        public void removeExplicitPermission(String toolId, PathScope scope) {
            explicit.remove(key(toolId, scope));
        }

        @Override
        public void allowForSession(String toolId) {
            sessionAllowed.add(key(toolId, PathScope.INSIDE_PROJECT));
        }

        @Override
        public void allowForSession(String toolId, PathScope scope) {
            sessionAllowed.add(key(toolId, scope));
        }

        @Override
        public boolean isSessionAllowed(String toolId) {
            return sessionAllowed.contains(key(toolId, PathScope.INSIDE_PROJECT));
        }

        @Override
        public boolean isSessionAllowed(String toolId, PathScope scope) {
            return sessionAllowed.contains(key(toolId, scope));
        }

        @Override
        public void clearSessionPermissions() {
            sessionAllowed.clear();
        }
    }
}
