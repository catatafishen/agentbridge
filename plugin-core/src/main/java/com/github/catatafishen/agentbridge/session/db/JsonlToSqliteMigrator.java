package com.github.catatafishen.agentbridge.session.db;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Imports legacy JSONL session files into {@link ConversationDatabase}.
 *
 * <p>Phase 2 task — see {@code .copilot/session-state/.../plan.md}. Runs once
 * on first start after upgrade, in the background, and is idempotent (rows
 * already in the DB are skipped via {@code INSERT OR IGNORE}).
 *
 * <p>Stub for now; implementation lands with the {@code p2-migrator} todo.
 */
public final class JsonlToSqliteMigrator {

    private static final Logger LOG = Logger.getInstance(JsonlToSqliteMigrator.class);

    private JsonlToSqliteMigrator() {
    }

    /**
     * Migrates all JSONL session files for the given project into the
     * {@link ConversationDatabase}. Returns silently if there is nothing to migrate.
     */
    public static void migrateIfNeeded(@NotNull Project project) {
        // Phase 2 — to be implemented.
        LOG.debug("JsonlToSqliteMigrator.migrateIfNeeded: stub (not yet implemented) for "
            + project.getName());
    }
}
