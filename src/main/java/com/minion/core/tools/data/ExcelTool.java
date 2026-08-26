package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** pandas 驱动的 Excel/CSV 读取工具；复杂分析继续交给 Python 工具。 */
public class ExcelTool implements Tool {
    private final Workspace workspace;
    private final PythonRuntime python;
    public ExcelTool(Workspace workspace, PythonRuntime python) { this.workspace = workspace; this.python = python; }
    @Override public String name() { return "Excel"; }
    @Override public String description() { return "读取 Excel/CSV 数据：inspect 查看工作表、行列和类型，read 以 CSV 预览指定工作表；复杂统计用 Python+pandas"; }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("Excel/CSV 数据读取",
                new String[]{"action", "path", "sheet", "rows"}, new String[]{"action", "path"});
    }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = text(args, "action").toLowerCase(Locale.ROOT);
        String raw = text(args, "path");
        Path path = PathsGuard.resolve(workspace.cwd().toString(), raw).toAbsolutePath().normalize();
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), path);
        if (guard != null) return guard;
        if (!Files.isRegularFile(path)) return ToolResult.error("文件不存在: " + path);
        String ext = extension(path.getFileName().toString());
        if (!("xlsx".equals(ext) || "xls".equals(ext) || "xlsm".equals(ext) || "csv".equals(ext) || "tsv".equals(ext))) {
            return ToolResult.error("仅支持 xlsx/xls/xlsm/csv/tsv");
        }
        int rows = integer(args, "rows", 50);
        rows = Math.max(1, Math.min(rows, 500));
        Path helper = writeHelper(SCRIPT);
        try {
            PythonRuntime.RunResult r = python.runFile(helper, Arrays.asList(action, path.toString(), arg(text(args, "sheet")), String.valueOf(rows)), workspace.cwd(), 120);
            String out = r.output == null ? "" : r.output.trim();
            return r.ok() ? ToolResult.success(out.isEmpty() ? "(无数据)" : out)
                    : ToolResult.error("Excel 读取失败（请用 Python status 检查 pandas/openpyxl/xlrd）\n" + out);
        } finally { Files.deleteIfExists(helper); }
    }

    private Path writeHelper(String script) throws Exception {
        Path dir = workspace.cwd().resolve(".minion").resolve("tmp"); Files.createDirectories(dir);
        Path helper = Files.createTempFile(dir, "excel-", ".py");
        Files.write(helper, script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return helper;
    }

    private static final String SCRIPT =
            "import sys,os,json\nimport pandas as pd\n"
          + "action,path,sheet,rows=sys.argv[1],sys.argv[2],sys.argv[3],int(sys.argv[4])\n"
          + "sheet='' if sheet=='__MINION_EMPTY__' else sheet\n"
          + "ext=os.path.splitext(path)[1].lower()\n"
          + "def load(name=None):\n"
          + "  if ext in ('.csv','.tsv'):\n"
          + "    try: return pd.read_csv(path,sep=('\\t' if ext=='.tsv' else ','),encoding='utf-8-sig')\n"
          + "    except UnicodeDecodeError: return pd.read_csv(path,sep=('\\t' if ext=='.tsv' else ','),encoding='gbk')\n"
          + "  return pd.read_excel(path,sheet_name=(0 if not name else name))\n"
          + "if action=='inspect':\n"
          + "  names=['CSV'] if ext in ('.csv','.tsv') else pd.ExcelFile(path).sheet_names\n"
          + "  print('文件: '+path)\n  print('工作表: '+', '.join(map(str,names)))\n"
          + "  for n in names:\n"
          + "    d=load(None if n=='CSV' else n)\n"
          + "    print('\\n[%s] 行=%d 列=%d' % (n,len(d),len(d.columns)))\n"
          + "    print('字段: '+', '.join('%s(%s)'%(c,d[c].dtype) for c in d.columns))\n"
          + "elif action=='read':\n"
          + "  d=load(sheet or None)\n  print(d.head(rows).to_csv(index=False))\n"
          + "else:\n  raise ValueError('action 仅支持 inspect/read')\n";

    private static String text(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static String arg(String value) { return value == null || value.isEmpty() ? "__MINION_EMPTY__" : value; }
    private static int integer(JsonObject o, String k, int d) { try { return o.has(k) ? Integer.parseInt(o.get(k).getAsString()) : d; } catch (Exception e) { return d; } }
    private static String extension(String s) { int i=s.lastIndexOf('.'); return i<0?"":s.substring(i+1).toLowerCase(Locale.ROOT); }
}
