package com.github.catatafishen.agentbridge.session.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ConversationDatabase} lifecycle: initialisation, WAL pragma,
 * isReady, getConnection, and dispose. Pure JDBC against in-memory SQLite —
 * no IntelliJ platform needed.
 */
class ConversationDatabaseTest {

    private Connection conn;
    private ConversationDatabase database;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        database = new ConversationDatabase();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    @Test
    void isReadyReturnsFalseBeforeInitialization() {
        assertFalse(database.isReady());
        assertNull(database.getConnection());
    }

    @Test
    void initializeWithConnectionMakesDatabaseReady() throws Exception {
        database.initializeWithConnection(conn);
        assertTrue(database.isReady());
        assertNotNull(database.getConnection());
    }

    @Test
    void initializeSetsWalJournalMode() throws Exception {
        // In-memory databases cannot use WAL (they report "memory").
        // Verify by using a file-based temp DB instead.
        java.nio.file.Path tempDb = java.nio.file.Files.createTempFile("conv-test-", ".db");
        try (Connection fileConn = DriverManager.getConnection("jdbc:sqlite:" + tempDb)) {
            ConversationDatabase fileDb = new ConversationDatabase();
            fileDb.initializeWithConnection(fileConn);
            try (Statement s = fileConn.createStatement();
                 ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1));
            }
            fileDb.dispose();
        } finally {
            java.nio.file.Files.deleteIfExists(tempDb);
        }
    }

    @Test
    void initializeSetsSynchronousNormal() throws Exception {
        database.initializeWithConnection(conn);
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA synchronous")) {
            assertTrue(rs.next());
            // NORMAL = 1
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void initializeEnablesForeignKeys() throws Exception {
        database.initializeWithConnection(conn);
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void initializeCreatesSchemaTablesAutomatically() throws Exception {
        database.initializeWithConnection(conn);
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sessions'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void disposeClosesConnection() throws Exception {
        database.initializeWithConnection(conn);
        assertTrue(database.isReady());
        database.dispose();
        assertFalse(database.isReady());
        assertNull(database.getConnection());
    }

    @Test
    void disposeIsIdempotent() throws Exception {
        database.initializeWithConnection(conn);
        database.dispose();
        database.dispose(); // second call must not throw
        assertFalse(database.isReady());
    }

    @Test
    void getDatabasePathReturnsNullForTestConstructor() {
        assertNull(database.getDatabasePath());
    }
}
