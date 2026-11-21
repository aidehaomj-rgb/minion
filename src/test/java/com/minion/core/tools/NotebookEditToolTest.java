package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** §3.1/§3.8 NotebookEdit 验收：replace/insert/delete、cell-N 索引与 id 定位、read-before-write */
public class NotebookEditToolTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private Path notebook() throws Exception {
        JsonObject nb = new JsonObject();
        nb.addProperty("nbformat", 4);
        JsonArray cells = new JsonArray();
        cells.add(cell("c1", "print(1)"));
        cells.add(cell("c2", "print(2)"));
        nb.add("cells", cells);
        Path p = tmp.getRoot().toPath().resolve("demo.ipynb");
        Files.write(p, nb.toString().getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static JsonObject cell(String id, String source) {
        JsonObject c = new JsonObject();
        c.addProperty("cell_type", "code");
        c.addProperty("id", id);
        JsonArray lines = new JsonArray();
        lines.add(new com.google.gson.JsonPrimitive(source));
        c.add("source", lines);
        return c;
    }

    private static JsonArray cells(Path p) throws Exception {
        return JsonParser.parseString(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("cells");
    }

    @Test public void replaceByCellId() throws Exception {
        Path p = notebook();
        Workspace ws = new Workspace(tmp.getRoot().getAbsolutePath());
        ToolResult r = new NotebookEditTool(ws).execute(args(
                "{\"notebook_path\":\"demo.ipynb\",\"cell_id\":\"c1\",\"new_source\":\"print('新')\"}"));
        assertTrue(r.ok);
        JsonArray cells = cells(p);
        assertEquals(2, cells.size());
        assertTrue(cells.get(0).getAsJsonObject().getAsJsonArray("source")
                .get(0).getAsString().contains("新"));
    }

    @Test public void insertAfterIndexAndDelete() throws Exception {
        Path p = notebook();
        NotebookEditTool tool = new NotebookEditTool(new Workspace(tmp.getRoot().getAbsolutePath()));
        assertTrue(tool.execute(args(
                "{\"notebook_path\":\"demo.ipynb\",\"cell_id\":\"cell-0\",\"edit_mode\":\"insert\","
                        + "\"new_source\":\"# 说明\",\"cell_type\":\"markdown\"}")).ok);
        assertEquals(3, cells(p).size());
        assertEquals("markdown", cells(p).get(1).getAsJsonObject().get("cell_type").getAsString());
        // delete 高危字段正确标注（isHighRisk）
        assertTrue(tool.isHighRisk(args(
                "{\"notebook_path\":\"x\",\"cell_id\":\"c1\",\"new_source\":\"\",\"edit_mode\":\"delete\"}")));
        assertTrue(tool.execute(args(
                "{\"notebook_path\":\"demo.ipynb\",\"cell_id\":\"cell-1\",\"edit_mode\":\"delete\","
                        + "\"new_source\":\"\"}")).ok);
        assertEquals(2, cells(p).size());
    }

    /** 未找到单元格报错；read-before-write（ccSemantics 开启时未读拒改） */
    @Test public void notFoundAndReadBeforeWrite() throws Exception {
        Path p = notebook();
        com.minion.core.security.ReadStateStore store = new com.minion.core.security.ReadStateStore();
        NotebookEditTool tool = new NotebookEditTool(
                new Workspace(tmp.getRoot().getAbsolutePath()), store, true);
        ToolResult denied = tool.execute(args(
                "{\"notebook_path\":\"demo.ipynb\",\"cell_id\":\"c1\",\"new_source\":\"x\"}"));
        assertFalse(denied.ok);
        assertTrue(denied.output.contains("Read"));
        // 登记读状态后放行
        store.record(com.minion.core.security.ReadStateStore.keyOf(p), null, "h", null, 0, 0, 1, false, false);
        assertTrue(tool.execute(args(
                "{\"notebook_path\":\"demo.ipynb\",\"cell_id\":\"c1\",\"new_source\":\"x\"}")).ok);
        // 不存在的单元格
        assertFalse(new NotebookEditTool(new Workspace(tmp.getRoot().getAbsolutePath())).execute(args(
                "{\"notebook_path\":\"demo.ipynb\",\"cell_id\":\"nope\",\"new_source\":\"x\"}")).ok);
    }
}
