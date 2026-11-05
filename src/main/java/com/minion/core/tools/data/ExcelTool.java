package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** pandas 驱动的 Excel/CSV 分析与写出工具，兼容 Python 3.7.6。 */
public class ExcelTool implements Tool {
    private final Workspace workspace;
    private final PythonRuntime python;
    private final CheckpointStore checkpoints;

    public ExcelTool(Workspace workspace, PythonRuntime python) { this(workspace, python, null); }
    public ExcelTool(Workspace workspace, PythonRuntime python, CheckpointStore checkpoints) {
        this.workspace = workspace; this.python = python; this.checkpoints = checkpoints;
    }
    @Override public String name() { return "Excel"; }
    public static boolean readOnlyAction(String action) {
        return java.util.Arrays.asList("inspect","read","profile","batch_profile","validate","merge_audit").contains(action);
    }
    @Override public String description() {
        return "Excel/CSV：inspect/read/profile/batch_profile/filter；read/filter支持offset(0起)和rows分页，textColumns指定按文本读的单号列；merge_audit审查重复键及未匹配/validate检查sheet、公式、ID和Decimal合计；patch_cells按data=[{sheet,cell,value}]回填新工作簿，保留普通sheet/样式/公式；原有write/append/merge/pivot/groupby/export/chart。写操作要求outputPath，不能把分页样本当全表。";
    }
    @Override public JsonObject schema() {
        JsonObject schema=SchemaGenerator.objectSchema("Excel/CSV 分析与写出",
                new String[]{"action","path","paths","sheet","rows","offset","textColumns","columns","filter","outputPath","data","otherPath","on","how","index","valueColumn","agg","chartType","x","y"},
                new String[]{"action"});
        schema.getAsJsonObject("properties").getAsJsonObject("data").addProperty("description","JSON字符串；patch_cells为[{sheet,cell:如B2,value:标量,formula:可选布尔}]。长单号必须字符串，只有formula=true才写公式。write/append仍为行对象数组。");
        schema.getAsJsonObject("properties").getAsJsonObject("textColumns").addProperty("description","按文本读取的ID列名，英文逗号分隔；保留文本前导零，不能修复源数值已损失精度");
        schema.getAsJsonObject("properties").getAsJsonObject("offset").addProperty("description","read/filter数据行偏移，从0开始（表头不计）");
        return schema;
    }
    @Override public boolean isHighRisk(JsonObject args) {
        String action = text(args, "action").toLowerCase(Locale.ROOT);
        if (!isWriteAction(action)) return false;
        String output = text(args, "outputPath");
        return output.isEmpty() || Files.exists(resolve(output));
    }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = text(args, "action").toLowerCase(Locale.ROOT);
        boolean writeOnly = "write".equals(action);
        boolean batchProfile = "batch_profile".equals(action);
        Path input = null;
        String raw = text(args, "path");
        if ((!writeOnly && !batchProfile) || !raw.isEmpty()) {
            if (raw.isEmpty()) return ToolResult.error(action + " 缺少 path");
            input = resolve(raw);
            ToolResult guard = PathsGuard.errorIfOutside(workspace,null,null,input);
            if (guard != null) return guard;
            if (!Files.isRegularFile(input)) return ToolResult.error("文件不存在: " + input);
            if (!supportedInput(input)) return ToolResult.error("输入仅支持 xlsx/xls/xlsm/csv/tsv");
        }

        Path other = null;
        if ("merge".equals(action)||"merge_audit".equals(action)) {
            String value = text(args, "otherPath");
            if (value.isEmpty()) return ToolResult.error("merge 缺少 otherPath");
            other = resolve(value);
            ToolResult guard = PathsGuard.errorIfOutside(workspace,null,null,other);
            if (guard != null) return guard;
            if (!Files.isRegularFile(other)) return ToolResult.error("合并文件不存在: " + other);
        }

        String batchPaths = "";
        if (batchProfile) {
            StringBuilder checked = new StringBuilder();
            for (String item : text(args,"paths").split("[;\\r\\n]+")) {
                if (item.trim().isEmpty()) continue;
                Path p = resolve(item.trim());
                ToolResult guard = PathsGuard.errorIfOutside(workspace,null,null,p);
                if (guard != null) return guard;
                if (!Files.isRegularFile(p) || !supportedInput(p)) return ToolResult.error("批量分析文件不存在或格式不支持: " + p);
                if (checked.length() > 0) checked.append(';');
                checked.append(p.toString());
            }
            if (checked.length() == 0) return ToolResult.error("batch_profile 需要 paths（分号或换行分隔）");
            batchPaths = checked.toString();
        }

        Path output = null;
        if (isWriteAction(action)) {
            String value = text(args, "outputPath");
            if (value.isEmpty()) return ToolResult.error(action + " 需要 outputPath；为保护原文件不默认覆盖");
            output = resolve(value);
            if (!insideWorkspace(output)) return ToolResult.error("输出路径必须位于工作区: " + output);
            if("patch_cells".equals(action)) {
                if(input==null||output.equals(input))return ToolResult.error("patch_cells须输出新文件，不能覆盖源工作簿");
                String ext=extension(input.toString());
                if(!("xlsx".equals(ext)||"xlsm".equals(ext))||!ext.equals(extension(output.toString())))
                    return ToolResult.error("patch_cells支持xlsx/xlsm，输出扩展名须与输入一致");
            }
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            if (checkpoints != null) {
                try { checkpoints.create(output, "Excel." + action); }
                catch (Exception e) { return ToolResult.error("创建 Excel 写出前检查点失败，未写文件: " + e.getMessage()); }
            }
        }

        int rows = Math.max(1, Math.min(integer(args, "rows", 50), 1000));
        int offset;
        try{offset=args.has("offset")?Integer.parseInt(text(args,"offset")):0;}catch(NumberFormatException e){return ToolResult.error("offset须为非负整数");}
        if(offset<0)return ToolResult.error("offset不能为负，数据行从0开始（表头不计）");
        JsonObject payload=new JsonObject();
        for(String key:new String[]{"sheet","columns","filter","data","on","how","index","valueColumn","agg","chartType","x","y","textColumns"})payload.addProperty(key,text(args,key));
        payload.addProperty("action",action);payload.addProperty("path",input==null?"":input.toString());
        payload.addProperty("outputPath",output==null?"":output.toString());
        payload.addProperty("otherPath",other==null?"":other.toString());
        payload.addProperty("paths",batchPaths);payload.addProperty("rows",rows);payload.addProperty("offset",offset);
        return ResourcePython.run(python,workspace.cwd(),"excel_workflow.py",payload);
    }

    private Path resolve(String raw) { return PathsGuard.resolve(workspace.cwd().toString(), raw).toAbsolutePath().normalize(); }
    private boolean insideWorkspace(Path p) { return PathsGuard.inside(workspace.workDir(), p.toAbsolutePath().normalize()); }
    private static boolean supportedInput(Path p) { String e=extension(p.getFileName().toString()); return "xlsx".equals(e)||"xls".equals(e)||"xlsm".equals(e)||"csv".equals(e)||"tsv".equals(e); }
    private static boolean isWriteAction(String a) { return "patch_cells".equals(a)||"write".equals(a)||"append".equals(a)||"merge".equals(a)||"pivot".equals(a)||"groupby".equals(a)||"export".equals(a)||"chart".equals(a); }
    private static String text(JsonObject o,String k) {return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}

    private static int integer(JsonObject o, String k, int d) { try { return o.has(k) ? Integer.parseInt(o.get(k).getAsString()) : d; } catch (Exception e) { return d; } }
    private static String extension(String s) { int i=s.lastIndexOf('.'); return i<0?"":s.substring(i+1).toLowerCase(Locale.ROOT); }
}
