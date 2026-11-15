package com.minion.core.tools.data;

import com.minion.core.tools.ToolArguments;

import com.google.gson.JsonObject;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.security.SecretStore;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.db.SqlGuard;
import com.minion.core.tools.python.PythonRuntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

/** 内网数据库统一入口；连接信息仅作为本轮参数传入，输出和日志均不得回显密码。 */
public final class DatabaseTool implements Tool {
    private final Workspace workspace; private final PythonRuntime python; private final CheckpointStore checkpoints; private final SecretStore secrets;
    public DatabaseTool(Workspace workspace,PythonRuntime python,CheckpointStore checkpoints){this(workspace,python,checkpoints,null);}
    public DatabaseTool(Workspace workspace,PythonRuntime python,CheckpointStore checkpoints,SecretStore secrets){this.workspace=workspace;this.python=python;this.checkpoints=checkpoints;this.secrets=secrets;}
    @Override public String name(){return "Database";}
    @Override public String description(){return "内网数据库：drivers/status/schema/query/execute/export；支持 sqlite、odbc/sqlserver、mysql、postgresql。query/export 只接受单条只读 SQL，execute 需确认；建议数据库账号本身也设为只读";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("数据库连接与查询",
            new String[]{"action","type","connection","connectionRef","sql","limit","outputPath"},new String[]{"action","type"});}
    @Override public boolean isHighRisk(JsonObject args){
        String action=text(args,"action").toLowerCase(Locale.ROOT);
        if("execute".equals(action))return true;
        return "export".equals(action)&&!text(args,"outputPath").isEmpty()
                &&Files.exists(workspace.cwd().resolve(text(args,"outputPath")).normalize());
    }
    @Override public ToolResult execute(JsonObject args)throws Exception{
        String action=text(args,"action").toLowerCase(Locale.ROOT),type=text(args,"type").toLowerCase(Locale.ROOT);
        if("drivers".equals(action)||"status".equals(action))return drivers();
        if(!Arrays.asList("schema","query","execute","export").contains(action))return ToolResult.error("action 支持 drivers/status/schema/query/execute/export");
        if(!Arrays.asList("sqlite","odbc","sqlserver","mysql","postgresql","postgres").contains(type))return ToolResult.error("type 支持 sqlite/odbc/sqlserver/mysql/postgresql");
        String sql=text(args,"sql");String rejection=readSqlError(action,sql);
        if(rejection!=null)return ToolResult.error(rejection);
        int limit=Math.max(1,Math.min(integer(args,"limit",200),5000));
        String connection=text(args,"connection"),connectionRef=text(args,"connectionRef");
        if(connection.isEmpty()&&!connectionRef.isEmpty()){
            if(secrets==null)return ToolResult.error("本会话未启用加密密钥库");
            connection=secrets.get(connectionRef);if(connection==null)return ToolResult.error("密钥库中不存在连接: "+connectionRef);
        }
        if("sqlite".equals(type)){
            if(connection.isEmpty())return ToolResult.error("sqlite connection 请填写数据库相对路径或 {\"database\":\"x.db\"}");
            connection=normalizeSqlite(connection);
        }else if(connection.isEmpty())return ToolResult.error("connection 需要 JSON 连接参数；不会写入日志");
        Path output=null;
        if("export".equals(action)){
            String raw=text(args,"outputPath");if(raw.isEmpty())return ToolResult.error("export 缺少 outputPath");
            output=workspace.cwd().resolve(raw).normalize().toAbsolutePath();
            ToolResult outputGuard=PathsGuard.errorIfOutside(workspace.workDir(),output);if(outputGuard!=null)return outputGuard;
            String lower=output.getFileName().toString().toLowerCase(Locale.ROOT);
            if(!lower.endsWith(".csv")&&!lower.endsWith(".xlsx"))return ToolResult.error("export 输出仅支持 .csv/.xlsx");
            if(output.getParent()!=null)Files.createDirectories(output.getParent());if(checkpoints!=null)checkpoints.create(output,"Database.export");
        }
        Path helper=helper(), connectionFile=null;
        try{
            // 不把密码放在 Python 命令行参数中；Windows 进程列表可读取命令行。
            connectionFile=Files.createTempFile(helper.getParent(),"connection-",".json");
            Files.write(connectionFile,connection.getBytes(StandardCharsets.UTF_8));
            PythonRuntime.RunResult r=python.runFile(helper,Arrays.asList(action,type,"@"+connectionFile,arg(sql),String.valueOf(limit),output==null?EMPTY:output.toString()),workspace.cwd(),180);
            String out=r.output==null?"":r.output.trim();return r.ok()?ToolResult.success(out.isEmpty()?"完成":out):ToolResult.error("数据库操作失败；请检查对应离线驱动\n"+sanitize(out));
        }finally{if(connectionFile!=null)Files.deleteIfExists(connectionFile);Files.deleteIfExists(helper);}
    }
    private ToolResult drivers()throws Exception{
        String code="import importlib.util\nmods=['sqlite3','pyodbc','pymysql','psycopg2','pandas','openpyxl']\nprint('\\n'.join(m+'='+('ok' if importlib.util.find_spec(m) else 'missing') for m in mods))";
        PythonRuntime.RunResult r=python.runCode(code,workspace.cwd(),30);return r.ok()?ToolResult.success(r.output.trim()):ToolResult.error(r.output);
    }
    private String normalizeSqlite(String raw){
        String value=raw.trim();
        try{JsonObject o=com.google.gson.JsonParser.parseString(value).getAsJsonObject();value=o.has("database")?o.get("database").getAsString():value;}catch(Exception ignored){}
        Path p=workspace.cwd().resolve(value).normalize().toAbsolutePath();ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),p);if(guard!=null)throw new IllegalArgumentException("SQLite 路径越出工作区");
        JsonObject o=new JsonObject();o.addProperty("database",p.toString());return o.toString();
    }
    private Path helper()throws Exception{Path d=workspace.cwd().resolve(".minion/tmp");Files.createDirectories(d);Path p=Files.createTempFile(d,"database-",".py");Files.write(p,SCRIPT.getBytes(StandardCharsets.UTF_8));return p;}
    static String readSqlError(String action,String sql){
        if(!"query".equals(action)&&!"export".equals(action))return null;
        // PRAGMA 可修改 SQLite 持久化设置；结构探查请用 schema。
        return SqlGuard.check(sql);
    }
    private static String sanitize(String s){return com.minion.core.diagnostics.SecretRedactor.redact(s);}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){return ToolArguments.integer(o,k,d);}
    private static String arg(String v){return v==null||v.isEmpty()?EMPTY:v;}private static final String EMPTY="__MINION_EMPTY__";
    private static final String SCRIPT=
            "from __future__ import print_function\nimport sys,json,os\naction,kind,raw,sql,limit,out=sys.argv[1:7]\nif raw.startswith('@'):\n with open(raw[1:],encoding='utf-8') as f: raw=f.read()\nsql='' if sql=='__MINION_EMPTY__' else sql\nout='' if out=='__MINION_EMPTY__' else out\nlimit=int(limit)\ncfg=json.loads(raw)\n"
          + "def connect():\n"
          + " if kind=='sqlite':\n  import sqlite3,pathlib; path=cfg['database']; uri=pathlib.Path(path).resolve().as_uri()+'?mode=ro' if action!='execute' else path; c=sqlite3.connect(uri,uri=action!='execute'); c.row_factory=sqlite3.Row; c.execute('PRAGMA query_only=ON') if action!='execute' else None; return c\n"
          + " if kind in ('odbc','sqlserver'):\n  import pyodbc\n  if 'connection_string' in cfg: return pyodbc.connect(cfg['connection_string'])\n  parts=[]\n  for k,v in cfg.items(): parts.append(str(k)+'='+str(v))\n  return pyodbc.connect(';'.join(parts))\n"
          + " if kind=='mysql':\n  import pymysql; return pymysql.connect(host=cfg.get('host','localhost'),port=int(cfg.get('port',3306)),user=cfg.get('user'),password=cfg.get('password',''),database=cfg.get('database'),charset='utf8mb4')\n"
          + " if kind in ('postgres','postgresql'):\n  import psycopg2; return psycopg2.connect(host=cfg.get('host','localhost'),port=int(cfg.get('port',5432)),user=cfg.get('user'),password=cfg.get('password',''),dbname=cfg.get('database'))\n"
          + " raise ValueError('unsupported database type')\n"
          + "con=connect()\ntry:\n cur=con.cursor()\n"
          + " if action=='schema':\n"
          + "  if kind=='sqlite': q=\"select type,name,tbl_name,sql from sqlite_master where type in ('table','view','index') order by type,name\"\n"
          + "  elif kind=='mysql': q=\"select table_schema,table_name,column_name,data_type from information_schema.columns where table_schema=database() order by table_name,ordinal_position\"\n"
          + "  elif kind in ('postgres','postgresql'): q=\"select table_schema,table_name,column_name,data_type from information_schema.columns where table_schema not in ('pg_catalog','information_schema') order by table_schema,table_name,ordinal_position\"\n"
          + "  else: q=\"select table_schema,table_name,column_name,data_type from information_schema.columns order by table_schema,table_name,ordinal_position\"\n"
          + "  cur.execute(q); rows=cur.fetchmany(limit); cols=[d[0] for d in cur.description]; print(json.dumps([dict(zip(cols,r)) for r in rows],ensure_ascii=False,indent=2,default=str))\n"
          + " elif action in ('query','export'):\n"
          + "  cur.execute(sql); rows=cur.fetchmany(limit); cols=[d[0] for d in cur.description]; data=[dict(zip(cols,r)) for r in rows]\n"
          + "  if action=='query': print(json.dumps(data,ensure_ascii=False,indent=2,default=str))\n"
          + "  else:\n   import pandas as pd\n   d=pd.DataFrame(data); ext=os.path.splitext(out)[1].lower(); d.to_excel(out,index=False) if ext in ('.xlsx','.xls') else d.to_csv(out,index=False,encoding='utf-8-sig'); print('已导出 %d 行: %s'%(len(d),out))\n"
          + " elif action=='execute':\n"
          + "  cur.execute(sql); con.commit(); print('执行成功，影响行数: '+str(cur.rowcount))\n"
          + " else: raise ValueError('action 支持 schema/query/execute/export')\n"
          + "finally:\n con.close()\n";
}
