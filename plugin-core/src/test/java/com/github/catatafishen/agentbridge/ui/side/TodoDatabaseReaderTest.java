package com.github.catatafishen.agentbridge.ui.side;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoDatabaseReaderTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tempDir.resolve("session.db").toFile();
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE todos ("
                + "id TEXT PRIMARY KEY, "
                + "title TEXT NOT NULL, "
                + "description TEXT, "
                + "status TEXT DEFAULT 'pending' CHECK(status IN ('pending','in_progress','done','blocked')), "
                + "created_at TEXT DEFAULT (datetime('now')), "
                + "updated_at TEXT DEFAULT (datetime('now'))"
                + ")");
            stmt.execute("CREATE TABLE todo_deps ("
                + "todo_id TEXT NOT NULL, "
                + "depends_on TEXT NOT NULL, "
                + "PRIMARY KEY (todo_id, depends_on))");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void readTodos_emptyTable_returnsEmptyList() {
        List<TodoDatabaseReader.TodoItem> items = TodoDatabaseReader.readTodos(dbFile);
        assertTrue(items.isEmpty());
    }

    @Test
    void readTodos_nonExistentFile_returnsEmptyList() {
        File missing = tempDir.resolve("nonexistent.db").toFile();
        List<TodoDatabaseReader.TodoItem> items = TodoDatabaseReader.readTodos(missing);
        assertTrue(items.isEmpty());
    }

    @Test
    void readTodos_returnsAllItems() throws Exception {
        insertTodo("t1", "First task", "Description 1", "pending");
        insertTodo("t2", "Second task", "Description 2", "done");

        List<TodoDatabaseReader.TodoItem> items = TodoDatabaseReader.readTodos(dbFile);
        assertEquals(2, items.size());
    }

    @Test
    void readTodos_orderedByStatusPriority() throws Exception {
        insertTodo("done-task", "Done", null, "done");
        insertTodo("progress-task", "In progress", null, "in_progress");
        insertTodo("pending-task", "Pending", null, "pending");
        insertTodo("blocked-task", "Blocked", null, "blocked");

        List<TodoDatabaseReader.TodoItem> items = TodoDatabaseReader.readTodos(dbFile);
        assertEquals(4, items.size());
        assertEquals("in_progress", items.get(0).status());
        assertEquals("pending", items.get(1).status());
        assertEquals("blocked", items.get(2).status());
        assertEquals("done", items.get(3).status());
    }

    @Test
    void todoItem_statusHelpers() {
        TodoDatabaseReader.TodoItem done = new TodoDatabaseReader.TodoItem(
            "id", "title", null, "done", null, null);
        assertTrue(done.isDone());
        assertFalse(done.isBlocked());
        assertFalse(done.isInProgress());

        TodoDatabaseReader.TodoItem blocked = new TodoDatabaseReader.TodoItem(
            "id", "title", null, "blocked", null, null);
        assertTrue(blocked.isBlocked());
        assertFalse(blocked.isDone());

        TodoDatabaseReader.TodoItem inProgress = new TodoDatabaseReader.TodoItem(
            "id", "title", null, "in_progress", null, null);
        assertTrue(inProgress.isInProgress());
    }

    @Test
    void readTodos_preservesDescription() throws Exception {
        insertTodo("t1", "Task", "Detailed description here", "pending");
        List<TodoDatabaseReader.TodoItem> items = TodoDatabaseReader.readTodos(dbFile);
        assertEquals("Detailed description here", items.get(0).description());
    }

    @Test
    void readTodos_nullDescription_handled() throws Exception {
        insertTodo("t1", "Task", null, "pending");
        List<TodoDatabaseReader.TodoItem> items = TodoDatabaseReader.readTodos(dbFile);
        assertNull(items.get(0).description());
    }

    @Test
    void readTodos_noTodosTable_returnsEmptyList() throws Exception {
        File otherDb = tempDir.resolve("other.db").toFile();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + otherDb.getAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE something_else (id TEXT)");
        }
        List<TodoDatabaseReader.TodoItem> items = TodoDatabaseReader.readTodos(otherDb);
        assertTrue(items.isEmpty());
    }

    private void insertTodo(String id, String title, String description, String status) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            String desc = description != null ? "'" + description + "'" : "NULL";
            stmt.execute("INSERT INTO todos (id, title, description, status) VALUES ('"
                + id + "', '" + title + "', " + desc + ", '" + status + "')");
        }
    }
}
