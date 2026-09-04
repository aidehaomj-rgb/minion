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
    @Override public String description() {
        return "Excel/CSV：inspect/read/profile/batch_profile/filter 查看分析；write/append/merge/pivot/groupby/export 写出新文件；chart 生成图表。写操作默认要求 outputPath";
    }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("Excel/CSV 分析与写出",
                new String[]{"action","path","paths","sheet","rows","columns","filter","outputPath","data","otherPath","on","how","index","valueColumn","agg","chartType","x","y"},
                new String[]{"action"});
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
            ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), input);
            if (guard != null) return guard;
            if (!Files.isRegularFile(input)) return ToolResult.error("文件不存在: " + input);
            if (!supportedInput(input)) return ToolResult.error("输入仅支持 xlsx/xls/xlsm/csv/tsv");
        }

        Path other = null;
        if ("merge".equals(action)) {
            String value = text(args, "otherPath");
            if (value.isEmpty()) return ToolResult.error("merge 缺少 otherPath");
            other = resolve(value);
            ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), other);
            if (guard != null) return guard;
            if (!Files.isRegularFile(other)) return ToolResult.error("合并文件不存在: " + other);
        }

        String batchPaths = "";
        if (batchProfile) {
            StringBuilder checked = new StringBuilder();
            for (String item : text(args,"paths").split("[;\\r\\n]+")) {
                if (item.trim().isEmpty()) continue;
                Path p = resolve(item.trim());
                ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), p);
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
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            if (checkpoints != null) {
                try { checkpoints.create(output, "Excel." + action); }
                catch (Exception e) { return ToolResult.error("创建 Excel 写出前检查点失败，未写文件: " + e.getMessage()); }
            }
        }

        int rows = Math.max(1, Math.min(integer(args, "rows", 50), 1000));
        List<String> argv = new ArrayList<String>();
        argv.add(action);
        argv.add(input == null ? EMPTY : input.toString());
        argv.add(arg(text(args, "sheet")));
        argv.add(String.valueOf(rows));
        argv.add(arg(text(args, "columns")));
        argv.add(arg(text(args, "filter")));
        argv.add(output == null ? EMPTY : output.toString());
        argv.add(arg(text(args, "data")));
        argv.add(other == null ? EMPTY : other.toString());
        argv.add(arg(text(args, "on")));
        argv.add(arg(text(args, "how")));
        argv.add(arg(text(args, "index")));
        argv.add(arg(text(args, "valueColumn")));
        argv.add(arg(text(args, "agg")));
        argv.add(arg(text(args, "chartType")));
        argv.add(arg(text(args, "x")));
        argv.add(arg(text(args, "y")));
        argv.add(arg(batchPaths));

        Path helper = writeHelper();
        try {
            PythonRuntime.RunResult r = python.runFile(helper, argv, workspace.cwd(), 180);
            String out = r.output == null ? "" : r.output.trim();
            return r.ok() ? ToolResult.success(out.isEmpty() ? "完成" : out)
                    : ToolResult.error("Excel 执行失败（请用 Diagnostics 检查 pandas/openpyxl/xlrd/matplotlib）\n" + out);
        } finally { Files.deleteIfExists(helper); }
    }

    private Path writeHelper() throws Exception {
        Path dir = workspace.cwd().resolve(".minion").resolve("tmp"); Files.createDirectories(dir);
        Path helper = Files.createTempFile(dir, "excel-suite-", ".py");
        Files.write(helper, SCRIPT.getBytes(StandardCharsets.UTF_8));
        return helper;
    }
    private Path resolve(String raw) { return PathsGuard.resolve(workspace.cwd().toString(), raw).toAbsolutePath().normalize(); }
    private boolean insideWorkspace(Path p) { return PathsGuard.inside(workspace.workDir(), p.toAbsolutePath().normalize()); }
    private static boolean supportedInput(Path p) { String e=extension(p.getFileName().toString()); return "xlsx".equals(e)||"xls".equals(e)||"xlsm".equals(e)||"csv".equals(e)||"tsv".equals(e); }
    private static boolean isWriteAction(String a) { return "write".equals(a)||"append".equals(a)||"merge".equals(a)||"pivot".equals(a)||"groupby".equals(a)||"export".equals(a)||"chart".equals(a); }

    private static final String EMPTY = "__MINION_EMPTY__";
    private static final String SCRIPT =
            "from __future__ import print_function\nimport sys,os,json\nimport pandas as pd\n"
          + "vals=sys.argv[1:19]\n"
          + "action,path,sheet,rows,columns,query,out,data,other,on,how,index,value,agg,chart,x,y,paths=vals\n"
          + "def val(v): return '' if v=='__MINION_EMPTY__' else v\n"
          + "path,sheet,columns,query,out,data,other,on,how,index,value,agg,chart,x,y,paths=map(val,[path,sheet,columns,query,out,data,other,on,how,index,value,agg,chart,x,y,paths])\n"
          + "rows=int(rows)\n"
          + "def load(p,name=''):\n"
          + " ext=os.path.splitext(p)[1].lower()\n"
          + " if ext in ('.csv','.tsv'):\n"
          + "  try: return pd.read_csv(p,sep=('\\t' if ext=='.tsv' else ','),encoding='utf-8-sig')\n"
          + "  except UnicodeDecodeError: return pd.read_csv(p,sep=('\\t' if ext=='.tsv' else ','),encoding='gbk')\n"
          + " return pd.read_excel(p,sheet_name=(0 if not name else name))\n"
          + "def save(d,p):\n"
          + " ext=os.path.splitext(p)[1].lower()\n"
          + " if ext=='.csv': d.to_csv(p,index=False,encoding='utf-8-sig')\n"
          + " elif ext=='.tsv': d.to_csv(p,index=False,sep='\\t',encoding='utf-8-sig')\n"
          + " else: d.to_excel(p,index=False)\n"
          + "def selected(d):\n"
          + " if columns: d=d[[c.strip() for c in columns.split(',') if c.strip()]]\n"
          + " if query: d=d.query(query)\n"
          + " return d\n"
          + "if action=='inspect':\n"
          + " ext=os.path.splitext(path)[1].lower(); names=['CSV'] if ext in ('.csv','.tsv') else pd.ExcelFile(path).sheet_names\n"
          + " print('文件: '+path); print('工作表: '+', '.join(map(str,names)))\n"
          + " for n in names:\n"
          + "  d=load(path,'' if n=='CSV' else n); print('\\n[%s] 行=%d 列=%d'%(n,len(d),len(d.columns))); print('字段: '+', '.join('%s(%s)'%(c,d[c].dtype) for c in d.columns))\n"
          + "elif action in ('read','filter'):\n"
          + " d=selected(load(path,sheet)); print(d.head(rows).to_csv(index=False))\n"
          + "elif action=='profile':\n"
          + " d=load(path,sheet); print('行=%d 列=%d 缺失=%d 重复=%d'%(len(d),len(d.columns),int(d.isnull().sum().sum()),int(d.duplicated().sum()))); print(d.describe(include='all').transpose().fillna('').to_csv())\n"
          + "elif action=='batch_profile':\n"
          + " files=[p.strip() for p in paths.replace('\\n',';').split(';') if p.strip()]\n"
          + " if not files: raise ValueError('batch_profile 需要 paths（分号或换行分隔）')\n"
          + " print('文件,行,列,缺失单元格,重复行')\n"
          + " for p in files:\n"
          + "  d=load(p,''); print('%s,%d,%d,%d,%d'%(p,len(d),len(d.columns),int(d.isnull().sum().sum()),int(d.duplicated().sum())))\n"
          + "elif action=='write':\n"
          + " obj=json.loads(data); d=pd.DataFrame(obj if isinstance(obj,list) else [obj]); save(d,out); print('已写出 %d 行: %s'%(len(d),out))\n"
          + "elif action=='append':\n"
          + " d=load(path,sheet); obj=json.loads(data); add=pd.DataFrame(obj if isinstance(obj,list) else [obj]); d=pd.concat([d,add],ignore_index=True,sort=False); save(d,out); print('已追加并写出 %d 行: %s'%(len(d),out))\n"
          + "elif action=='merge':\n"
          + " a=load(path,sheet); b=load(other,''); keys=[c.strip() for c in on.split(',') if c.strip()]; d=pd.merge(a,b,on=keys,how=(how or 'inner')); save(d,out); print('已合并写出 %d 行: %s'%(len(d),out))\n"
          + "elif action=='pivot':\n"
          + " d=load(path,sheet); idx=[c.strip() for c in index.split(',') if c.strip()]; p=pd.pivot_table(d,index=idx,values=value,aggfunc=(agg or 'sum')).reset_index(); save(p,out); print('已生成透视结果 %d 行: %s'%(len(p),out))\n"
          + "elif action=='groupby':\n"
          + " d=selected(load(path,sheet)); keys=[c.strip() for c in index.split(',') if c.strip()]; values=[c.strip() for c in value.split(',') if c.strip()]; g=d.groupby(keys)[values].agg(agg or 'sum').reset_index(); save(g,out); print('已生成分组汇总 %d 行: %s'%(len(g),out))\n"
          + "elif action=='export':\n"
          + " d=selected(load(path,sheet)); save(d,out); print('已导出 %d 行: %s'%(len(d),out))\n"
          + "elif action=='chart':\n"
          + " import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt\n"
          + " d=selected(load(path,sheet)); kind=chart or 'line'; d.plot(x=(x or None),y=([c.strip() for c in y.split(',')] if ',' in y else (y or None)),kind=kind); plt.tight_layout(); plt.savefig(out,dpi=150); print('图表已生成: '+out)\n"
          + "else: raise ValueError('action 仅支持 inspect/read/profile/batch_profile/filter/write/append/merge/pivot/groupby/chart/export')\n";

    private static String text(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static String arg(String value) { return value == null || value.isEmpty() ? EMPTY : value; }
    private static int integer(JsonObject o, String k, int d) { try { return o.has(k) ? Integer.parseInt(o.get(k).getAsString()) : d; } catch (Exception e) { return d; } }
    private static String extension(String s) { int i=s.lastIndexOf('.'); return i<0?"":s.substring(i+1).toLowerCase(Locale.ROOT); }
}
