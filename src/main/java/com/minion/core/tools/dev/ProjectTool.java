package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Win7/JDK8/Python3.7 兼容的轻量项目模板。 */
public final class ProjectTool implements Tool {
    private final Workspace workspace;
    public ProjectTool(Workspace workspace) { this.workspace=workspace; }
    @Override public String name(){return "Project";}
    @Override public String description(){return "项目脚手架：list/create；模板 python-data/flask/sqlite/pyinstaller/java-maven/web，均兼容 Win7 基线";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("创建项目模板",new String[]{"action","template","path","name"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){return "create".equalsIgnoreCase(text(args,"action"));}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        String action=text(args,"action").toLowerCase(Locale.ROOT);
        if("list".equals(action))return ToolResult.success("python-data  pandas 数据分析\nflask  本地网页\nsqlite  SQLite 数据应用\npyinstaller  Windows EXE\njava-maven  JDK8/Maven\nweb  原生 HTML/CSS/JS");
        if(!"create".equals(action))return ToolResult.error("未知 action（支持 list/create）");
        String template=text(args,"template"), raw=text(args,"path"); if(raw.trim().isEmpty())raw=text(args,"name");
        if(!raw.matches("[A-Za-z0-9._\\-/\\u4e00-\\u9fa5]+"))return ToolResult.error("path/name 非法");
        Path root=workspace.cwd().resolve(raw).normalize().toAbsolutePath();ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),root);if(guard!=null)return guard;
        if(Files.exists(root)){try(java.util.stream.Stream<Path>s=Files.list(root)){if(s.findAny().isPresent())return ToolResult.error("目标目录非空，拒绝覆盖: "+root);}}
        Map<String,String> files=templates(template);if(files==null)return ToolResult.error("未知模板: "+template);
        for(Map.Entry<String,String> e:files.entrySet()){Path f=root.resolve(e.getKey());Files.createDirectories(f.getParent());Files.write(f,e.getValue().getBytes(StandardCharsets.UTF_8));}
        return ToolResult.success("已创建 "+template+" 项目: "+root+"\n文件数: "+files.size());
    }
    private static Map<String,String> templates(String t){Map<String,String> m=new LinkedHashMap<String,String>();
        if("python-data".equals(t)){m.put("main.py","# -*- coding: utf-8 -*-\nimport pandas as pd\n\ndef main():\n    df = pd.read_excel('input.xlsx')\n    print(df.describe(include='all'))\n    df.to_excel('output.xlsx', index=False)\n\nif __name__ == '__main__':\n    main()\n");m.put("requirements.txt","pandas<1.4\nopenpyxl<3.1\nxlrd<2\nmatplotlib<3.6\n");}
        else if("flask".equals(t)){m.put("app.py","# -*- coding: utf-8 -*-\nfrom flask import Flask, render_template\napp = Flask(__name__)\n@app.route('/')\ndef index(): return render_template('index.html')\nif __name__ == '__main__': app.run(host='127.0.0.1', port=5000, debug=True)\n");m.put("templates/index.html","<!doctype html><meta charset=\"utf-8\"><title>Minion App</title><h1>应用已运行</h1>\n");m.put("requirements.txt","Flask<2.3\n");}
        else if("sqlite".equals(t)){m.put("app.py","# -*- coding: utf-8 -*-\nimport sqlite3\nwith sqlite3.connect('data.db') as db:\n    db.execute('create table if not exists records(id integer primary key, name text)')\n    print(list(db.execute('select * from records')))\n");}
        else if("pyinstaller".equals(t)){m.put("main.py","# -*- coding: utf-8 -*-\nimport tkinter as tk\nroot=tk.Tk(); root.title('Minion App'); tk.Label(root,text='程序已运行').pack(padx=50,pady=30); root.mainloop()\n");m.put("build.cmd","@echo off\npython -m PyInstaller --noconfirm --onefile --windowed --name MinionApp main.py\npause\n");m.put("requirements.txt","PyInstaller==4.10\n");}
        else if("java-maven".equals(t)){m.put("pom.xml","<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>local.minion</groupId><artifactId>app</artifactId><version>1.0.0</version><properties><maven.compiler.source>1.8</maven.compiler.source><maven.compiler.target>1.8</maven.compiler.target><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>\n");m.put("src/main/java/local/minion/App.java","package local.minion; public class App { public static void main(String[] args){ System.out.println(\"Hello Minion\"); } }\n");}
        else if("web".equals(t)){m.put("index.html","<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><link rel=\"stylesheet\" href=\"style.css\"><title>Minion Web</title><main><h1>Minion Web</h1><button id=\"go\">开始</button></main><script src=\"app.js\"></script></html>\n");m.put("style.css","body{font-family:Arial,sans-serif;max-width:900px;margin:40px auto;padding:20px;color:#222}button{padding:10px 20px}\n");m.put("app.js","document.getElementById('go').onclick=function(){alert('已连接')};\n");}
        else return null;return m;}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
}
