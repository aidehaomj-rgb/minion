package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class DatabaseReadOnlyTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void queryAndExportRejectStackedOrWritingSql() {
        assertNull(DatabaseTool.readSqlError("query", "SELECT name FROM records"));
        assertNull(DatabaseTool.readSqlError("export", "SELECT name FROM records"));
        assertNotNull(DatabaseTool.readSqlError("query", "SELECT 1; DROP TABLE records"));
        assertNotNull(DatabaseTool.readSqlError("export", "WITH x AS (DELETE FROM records RETURNING *) SELECT * FROM x"));
        assertNotNull(DatabaseTool.readSqlError("export", "PRAGMA journal_mode=OFF"));
        assertNotNull(DatabaseTool.readSqlError("query", "UPDATE records SET name='x'"));
        assertNull(DatabaseTool.readSqlError("execute", "UPDATE records SET name='x'"));
    }

    @Test public void sqliteRejectsUnsafeQueryBeforeOpeningDatabase() throws Exception {
        SqliteTool tool = new SqliteTool(new Workspace(tmp.getRoot().getPath()), new PythonRuntime(""));
        JsonObject args = new JsonObject();
        args.addProperty("action", "query");
        args.addProperty("database", "absent.db");
        args.addProperty("sql", "SELECT 1; DELETE FROM records");
        ToolResult result = tool.execute(args);
        assertFalse(result.ok);
        assertTrue(result.output, result.output.contains("只读工具拒绝执行"));
    }

    @Test public void exportRejectsNonDataExtensionBeforePythonStarts() throws Exception {
        DatabaseTool tool = new DatabaseTool(new Workspace(tmp.getRoot().getPath()), new PythonRuntime(""), null);
        JsonObject args = new JsonObject();
        args.addProperty("action", "export");
        args.addProperty("type", "sqlite");
        args.addProperty("connection", "data.db");
        args.addProperty("sql", "SELECT 1");
        args.addProperty("outputPath", "report.exe");
        ToolResult result = tool.execute(args);
        assertFalse(result.ok);
        assertTrue(result.output, result.output.contains(".csv/.xlsx"));
    }
}
