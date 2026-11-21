package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * NotebookEdit（开发说明 v2.4 §3.1/§3.8）：.ipynb cells 纯 JSON 编辑（gson，无 Python 依赖）。
 * notebook_path、cell_id（或 cell-N 索引）、new_source、cell_type(code/markdown)、
 * edit_mode(replace/insert/delete)；insert 在指定 cell 后插入，delete 需高危确认。
 * 读状态校验同 Edit（read-before-write，ccSemantics 开启时）。
 */
public class NotebookEditTool implements Tool {

    private final Workspace workspace;
    private final com.minion.core.security.ReadStateStore readState;
    private final boolean ccSemantics;

    public NotebookEditTool(Workspace workspace) { this(workspace, null, false); }

    public NotebookEditTool(Workspace workspace, com.minion.core.security.ReadStateStore readState,
                            boolean ccSemantics) {
        this.workspace = workspace;
        this.readState = readState;
        this.ccSemantics = ccSemantics;
    }

    @Override public String name() { return "NotebookEdit"; }

    @Override public String description() {
        return "编辑 .ipynb 单元格：edit_mode=replace/insert/delete；cell_id 或 cell-N 索引定位";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("编辑 notebook 单元格",
                new String[]{"notebook_path", "cell_id", "new_source", "cell_type", "edit_mode"},
                new String[]{"notebook_path", "cell_id", "new_source"});
    }

    @Override public boolean isHighRisk(JsonObject args) {
        return "delete".equals(text(args, "edit_mode"));   // 删除单元格破坏性
    }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String path = text(args, "notebook_path");
        if (path.isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 notebook_path");
        Path p = PathsGuard.resolve(workspace.cwd().toString(), path);
        if (!Files.isRegularFile(p)) return ToolResult.errorWithoutSideEffects("文件不存在: " + p);
        ToolResult guard = PathsGuard.errorIfOutside(workspace, null, null, p);
        if (guard != null) return guard;
        // read-before-write（ccSemantics；§3.1 同 Edit 口径）
        if (ccSemantics && readState != null) {
            com.minion.core.security.ReadStateStore.Entry e =
                    readState.get(com.minion.core.security.ReadStateStore.keyOf(p));
            if (e == null || e.isPartialView)
                return ToolResult.errorWithoutSideEffects("请先 Read 该 notebook 后再编辑（read-before-write）: " + p);
        }
        JsonObject nb = JsonParser.parseString(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray cells = nb.getAsJsonArray("cells");
        if (cells == null) return ToolResult.errorWithoutSideEffects("notebook 缺少 cells 数组");

        String cellRef = text(args, "cell_id");
        if (cellRef.isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 cell_id（id 或 cell-N 索引）");
        int index = locate(cells, cellRef);
        String mode = text(args, "edit_mode");
        if (mode.isEmpty()) mode = "replace";
        String source = text(args, "new_source");

        if ("delete".equals(mode)) {
            if (index < 0) return ToolResult.errorWithoutSideEffects("未找到单元格: " + cellRef);
            cells.remove(index);
        } else if ("insert".equals(mode)) {
            if (index < 0) return ToolResult.errorWithoutSideEffects("insert 需定位既有单元格（在其后插入）: " + cellRef);
            JsonArray rebuilt = new JsonArray();
            for (int i = 0; i < cells.size(); i++) {
                rebuilt.add(cells.get(i));
                if (i == index) rebuilt.add(newCell(text(args, "cell_type"), source));
            }
            nb.add("cells", rebuilt);
        } else if ("replace".equals(mode)) {
            if (index < 0) return ToolResult.errorWithoutSideEffects("未找到单元格: " + cellRef);
            String type = text(args, "cell_type");
            JsonObject old = cells.get(index).getAsJsonObject();
            cells.set(index, newCell(type.isEmpty() ? strOr(old, "cell_type", "code") : type, source));
        } else {
            return ToolResult.errorWithoutSideEffects("edit_mode 只支持 replace/insert/delete: " + mode);
        }
        Files.write(p, nb.toString().getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("已" + mode + " 单元格 " + cellRef + "（" + cells.size() + " 个单元格）: " + p);
    }

    /** cell_id 精确匹配 id 字段；cell-N（0 起）索引定位 */
    private static int locate(JsonArray cells, String ref) {
        if (ref.matches("cell-\\d+")) {
            int i = Integer.parseInt(ref.substring(5));
            return i >= 0 && i < cells.size() ? i : -1;
        }
        for (int i = 0; i < cells.size(); i++) {
            JsonElement e = cells.get(i);
            if (e.isJsonObject() && ref.equals(strOr(e.getAsJsonObject(), "id", null))) return i;
        }
        return -1;
    }

    private static JsonObject newCell(String type, String source) {
        JsonObject cell = new JsonObject();
        cell.addProperty("cell_type", "markdown".equals(type) ? "markdown" : "code");
        cell.addProperty("id", "cell-" + Long.toHexString(System.currentTimeMillis()));
        JsonArray lines = new JsonArray();
        for (String line : source.split("\n", -1)) lines.add(new com.google.gson.JsonPrimitive(line + "\n"));
        if (lines.size() > 0) {   // 末行不补换行
            String last = lines.get(lines.size() - 1).getAsString();
            lines.set(lines.size() - 1, new com.google.gson.JsonPrimitive(
                    last.endsWith("\n") ? last.substring(0, last.length() - 1) : last));
        }
        cell.add("source", lines);
        if ("code".equals(cell.get("cell_type").getAsString())) {
            cell.add("outputs", new JsonArray());
            cell.add("execution_count", null);
        }
        return cell;
    }

    private static String text(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }
}
