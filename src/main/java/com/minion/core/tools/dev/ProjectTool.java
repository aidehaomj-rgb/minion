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
    @Override public String description(){return "项目脚手架：list/create；模板 blank/python-data/flask/flask-dashboard/sqlite/pyinstaller/java-maven/web/pwa，均兼容 Win7 基线";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("创建项目模板",new String[]{"action","template","path","name"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){return "create".equalsIgnoreCase(text(args,"action"));}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        String action=text(args,"action").toLowerCase(Locale.ROOT);
        if("list".equals(action))return ToolResult.success("blank  空白项目\npython-data  pandas 数据分析\nflask  本地网页\nflask-dashboard  内网数据看板\nsqlite  SQLite 数据应用\npyinstaller  Windows EXE\njava-maven  JDK8/Maven\nweb  原生 HTML/CSS/JS\npwa  可安装的离线网页 App");
        if(!"create".equals(action))return ToolResult.error("未知 action（支持 list/create）");
        String template=text(args,"template"), raw=text(args,"path"); if(raw.trim().isEmpty())raw=text(args,"name");
        if(!raw.matches("[A-Za-z0-9._\\-/\\u4e00-\\u9fa5]+"))return ToolResult.error("path/name 非法");
        Path root=workspace.cwd().resolve(raw).normalize().toAbsolutePath();ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),root);if(guard!=null)return guard;
        if(Files.exists(root)){try(java.util.stream.Stream<Path>s=Files.list(root)){if(s.findAny().isPresent())return ToolResult.error("目标目录非空，拒绝覆盖: "+root);}}
        int count=createAt(root,template);
        return ToolResult.success("已创建 "+template+" 项目: "+root+"\n文件数: "+count);
    }
    /** GUI 新建项目向导与 Agent 工具共用的脚手架入口。目标目录必须不存在或为空。 */
    public static int createAt(Path root,String template)throws Exception{
        Path target=root.toAbsolutePath().normalize();
        if(Files.exists(target)){try(java.util.stream.Stream<Path>s=Files.list(target)){if(s.findAny().isPresent())throw new IllegalArgumentException("目标目录非空: "+target);}}
        Map<String,String> files=templates(template);if(files==null)throw new IllegalArgumentException("未知模板: "+template);
        Files.createDirectories(target);
        for(Map.Entry<String,String> e:files.entrySet()){Path f=target.resolve(e.getKey());Files.createDirectories(f.getParent());Files.write(f,e.getValue().getBytes(StandardCharsets.UTF_8));}
        return files.size();
    }
    static Map<String,String> templates(String t){Map<String,String> m=new LinkedHashMap<String,String>();
        if("blank".equals(t)){/* project.md / skills 由 GUI 向导创建；Agent 工具只创建空目录 */}
        else if("python-data".equals(t)){m.put("main.py","# -*- coding: utf-8 -*-\nimport pandas as pd\n\ndef main():\n    df = pd.read_excel('input.xlsx')\n    print(df.describe(include='all'))\n    df.to_excel('output.xlsx', index=False)\n\nif __name__ == '__main__':\n    main()\n");m.put("requirements.txt","pandas<1.4\nopenpyxl<3.1\nxlrd<2\nmatplotlib<3.6\n");}
        else if("flask".equals(t)){m.put("app.py","# -*- coding: utf-8 -*-\nfrom flask import Flask, render_template\napp = Flask(__name__)\n@app.route('/')\ndef index(): return render_template('index.html')\nif __name__ == '__main__': app.run(host='127.0.0.1', port=5000, debug=True)\n");m.put("templates/index.html","<!doctype html><meta charset=\"utf-8\"><title>Minion App</title><h1>应用已运行</h1>\n");m.put("requirements.txt","Flask<2.3\n");}
        else if("flask-dashboard".equals(t)){m.put("app.py","# -*- coding: utf-8 -*-\nfrom flask import Flask, jsonify, render_template\nimport pandas as pd\napp = Flask(__name__)\n\ndef load_data():\n    try: return pd.read_csv('data.csv', encoding='utf-8-sig')\n    except IOError: return pd.DataFrame()\n\n@app.route('/')\ndef index(): return render_template('index.html')\n\n@app.route('/api/summary')\ndef summary():\n    df = load_data()\n    return jsonify({'rows': int(len(df)), 'columns': list(df.columns), 'preview': df.head(20).fillna('').to_dict('records')})\n\nif __name__ == '__main__': app.run(host='0.0.0.0', port=5000, debug=False)\n");m.put("templates/index.html","<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>内网数据看板</title><link rel=\"stylesheet\" href=\"/static/style.css\"><main><h1>内网数据看板</h1><section id=\"stats\">正在读取...</section><div class=\"table\"><table id=\"data\"></table></div></main><script src=\"/static/app.js\"></script></html>\n");m.put("static/style.css","body{font-family:Arial,'Microsoft YaHei',sans-serif;margin:0;background:#f4f6f8;color:#263238}main{max-width:1100px;margin:auto;padding:24px}section{background:white;padding:16px;margin:16px 0;border-radius:6px}.table{overflow:auto;background:white}table{border-collapse:collapse;width:100%}th,td{padding:8px;border:1px solid #ddd;text-align:left}\n");m.put("static/app.js","fetch('/api/summary').then(function(r){return r.json()}).then(function(d){document.getElementById('stats').textContent='行数：'+d.rows+'；字段：'+d.columns.join('、');var h='<tr>'+d.columns.map(function(c){return '<th>'+c+'</th>'}).join('')+'</tr>';d.preview.forEach(function(row){h+='<tr>'+d.columns.map(function(c){return '<td>'+String(row[c]||'')+'</td>'}).join('')+'</tr>'});document.getElementById('data').innerHTML=h}).catch(function(e){document.getElementById('stats').textContent='加载失败：'+e});\n");m.put("data.csv","名称,数值\n示例,1\n");m.put("requirements.txt","Flask<2.3\npandas<1.4\n");m.put("启动看板.cmd","@echo off\npython app.py\npause\n");}
        else if("sqlite".equals(t)){m.put("app.py","# -*- coding: utf-8 -*-\nimport sqlite3\nwith sqlite3.connect('data.db') as db:\n    db.execute('create table if not exists records(id integer primary key, name text)')\n    print(list(db.execute('select * from records')))\n");}
        else if("pyinstaller".equals(t)){m.put("main.py","# -*- coding: utf-8 -*-\nimport tkinter as tk\nroot=tk.Tk(); root.title('Minion App'); tk.Label(root,text='程序已运行').pack(padx=50,pady=30); root.mainloop()\n");m.put("build.cmd","@echo off\npython -m PyInstaller --noconfirm --onefile --windowed --name MinionApp main.py\npause\n");m.put("requirements.txt","PyInstaller==4.10\n");}
        else if("java-maven".equals(t)){m.put("pom.xml","<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>local.minion</groupId><artifactId>app</artifactId><version>1.0.0</version><properties><maven.compiler.source>1.8</maven.compiler.source><maven.compiler.target>1.8</maven.compiler.target><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>\n");m.put("src/main/java/local/minion/App.java","package local.minion; public class App { public static void main(String[] args){ System.out.println(\"Hello Minion\"); } }\n");}
        else if("web".equals(t)){m.put("index.html","<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><link rel=\"stylesheet\" href=\"style.css\"><title>Minion Web</title><main><h1>Minion Web</h1><button id=\"go\">开始</button></main><script src=\"app.js\"></script></html>\n");m.put("style.css","body{font-family:Arial,sans-serif;max-width:900px;margin:40px auto;padding:20px;color:#222}button{padding:10px 20px}\n");m.put("app.js","document.getElementById('go').onclick=function(){alert('已连接')};\n");}
        else if("pwa".equals(t)){m.put("index.html","<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"theme-color\" content=\"#1565c0\"><link rel=\"manifest\" href=\"manifest.json\"><link rel=\"stylesheet\" href=\"style.css\"><title>离线 App</title><main><h1>离线 App</h1><p id=\"status\">已启动</p><textarea id=\"note\" placeholder=\"输入内容，将保存在本机\"></textarea></main><script src=\"app.js\"></script></html>\n");m.put("style.css","body{font-family:Arial,'Microsoft YaHei',sans-serif;margin:0;background:#eef3f8;color:#203040}main{max-width:720px;margin:auto;padding:24px}textarea{box-sizing:border-box;width:100%;height:240px;padding:12px;font-size:16px}\n");m.put("app.js","var note=document.getElementById('note');note.value=localStorage.getItem('note')||'';note.oninput=function(){localStorage.setItem('note',note.value)};if('serviceWorker' in navigator){navigator.serviceWorker.register('sw.js')}\n");m.put("manifest.json","{\"name\":\"Minion 离线 App\",\"short_name\":\"MinionApp\",\"start_url\":\"./\",\"display\":\"standalone\",\"background_color\":\"#eef3f8\",\"theme_color\":\"#1565c0\"}\n");m.put("sw.js","var CACHE='minion-pwa-v1';var FILES=['./','index.html','style.css','app.js','manifest.json'];self.addEventListener('install',function(e){e.waitUntil(caches.open(CACHE).then(function(c){return c.addAll(FILES)}))});self.addEventListener('fetch',function(e){e.respondWith(caches.match(e.request).then(function(r){return r||fetch(e.request)}))});\n");m.put("启动预览.cmd","@echo off\npython -m http.server 8000\npause\n");}
        else return null;return m;}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
}
