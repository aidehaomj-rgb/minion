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

/** Python 标准库 sqlite3 驱动，无额外 JDBC/联网依赖。 */
public class SqliteTool implements Tool {
    private final Workspace workspace; private final PythonRuntime python;
    public SqliteTool(Workspace workspace, PythonRuntime python) { this.workspace=workspace; this.python=python; }
    @Override public String name() { return "SQLite"; }
    @Override public String description() { return "操作工作区 SQLite 数据库：schema 查看结构，query 只读查询，execute 执行建表/增删改（需确认）"; }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("SQLite 数据库工具",
                new String[]{"action","database","sql","limit"}, new String[]{"action","database"});
    }
    @Override public boolean isHighRisk(JsonObject args) { return "execute".equalsIgnoreCase(text(args,"action")); }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action=text(args,"action").toLowerCase(Locale.ROOT), raw=text(args,"database");
        Path db=PathsGuard.resolve(workspace.cwd().toString(),raw).toAbsolutePath().normalize();
        ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),db); if(guard!=null)return guard;
        if (!"execute".equals(action) && !Files.isRegularFile(db)) return ToolResult.error("数据库不存在: "+db);
        String sql=text(args,"sql");
        if ("query".equals(action) && !isReadOnly(sql)) return ToolResult.error("query 仅允许 SELECT/WITH/PRAGMA/EXPLAIN；修改请用 execute");
        int limit=Math.max(1,Math.min(integer(args,"limit",200),1000));
        Path helper=writeHelper();
        try {
            PythonRuntime.RunResult r=python.runFile(helper, Arrays.asList(action,db.toString(),arg(sql),String.valueOf(limit)),workspace.cwd(),120);
            String out=r.output==null?"":r.output.trim();
            return r.ok()?ToolResult.success(out.isEmpty()?"完成":out):ToolResult.error("SQLite 执行失败\n"+out);
        } finally { Files.deleteIfExists(helper); }
    }
    private Path writeHelper() throws Exception {
        Path dir=workspace.cwd().resolve(".minion").resolve("tmp"); Files.createDirectories(dir);
        Path p=Files.createTempFile(dir,"sqlite-",".py"); Files.write(p,SCRIPT.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return p;
    }
    private static boolean isReadOnly(String sql) {
        String s=sql==null?"":sql.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("select")||s.startsWith("with")||s.startsWith("pragma")||s.startsWith("explain");
    }
    private static final String SCRIPT=
            "import sys,sqlite3,json\naction,path,sql,limit=sys.argv[1],sys.argv[2],sys.argv[3],int(sys.argv[4])\n"
          + "sql='' if sql=='__MINION_EMPTY__' else sql\n"
          + "con=sqlite3.connect(path)\ncon.row_factory=sqlite3.Row\n"
          + "try:\n"
          + " if action=='schema':\n"
          + "  rows=con.execute(\"select type,name,tbl_name,sql from sqlite_master where type in ('table','view','index') order by type,name\").fetchall()\n"
          + "  print('\\n\\n'.join('%s %s\\n%s'%(r['type'],r['name'],r['sql'] or '') for r in rows))\n"
          + " elif action=='query':\n"
          + "  con.execute('PRAGMA query_only=ON'); cur=con.execute(sql); rows=cur.fetchmany(limit); print(json.dumps([dict(r) for r in rows],ensure_ascii=False,indent=2,default=str))\n"
          + " elif action=='execute':\n"
          + "  before=con.total_changes; con.executescript(sql); con.commit(); print('执行成功，变更行数: %d'%(con.total_changes-before))\n"
          + " else: raise ValueError('action 仅支持 schema/query/execute')\n"
          + "finally:\n con.close()\n";
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static String arg(String value){return value==null||value.isEmpty()?"__MINION_EMPTY__":value;}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?Integer.parseInt(o.get(k).getAsString()):d;}catch(Exception e){return d;}}
}
