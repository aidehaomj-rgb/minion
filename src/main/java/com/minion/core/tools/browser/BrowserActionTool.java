package com.minion.core.tools.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 给中小模型使用的语义化浏览器动作，减少手写 BrowserEval JavaScript 的失败率。 */
public class BrowserActionTool implements Tool {
    private final BrowserSession session;
    private final Workspace workspace;
    public BrowserActionTool(BrowserSession session){this(session,null);}
    public BrowserActionTool(BrowserSession session,Workspace workspace){this.session=session;this.workspace=workspace;}
    @Override public String name(){return "BrowserAction";}
    @Override public String description(){return "语义化网页操作：元素交互、等待/网络空闲、上传/下载、多页抓取、标签页管理与人工接管；selector 使用 CSS";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("网页元素操作",
            new String[]{"action","selector","nextSelector","value","path","outputPath","targetId","timeoutMs","pages"},new String[]{"action"});}
    @Override public ToolResult execute(JsonObject args){
        String action=text(args,"action").toLowerCase(Locale.ROOT),selector=text(args,"selector"),value=text(args,"value");
        try{
            if("tabs".equals(action))return ok(session.tabs());
            if("new_tab".equals(action))return ok(session.newTab(value));
            if("switch_tab".equals(action))return ok(session.switchTab(text(args,"targetId")));
            if("close_tab".equals(action))return ok(session.closeTab(text(args,"targetId")));
            if("network_idle".equals(action))return ok(session.waitForNetworkIdle(integer(args,"timeoutMs",15000),800));
            if("manual_takeover".equals(action))return ToolResult.success("已暂停自动操作。请在 Chrome 中手工完成验证码、登录或敏感确认，完成后再让 Agent 执行 status/继续。");
            if("download_setup".equals(action))return configureDownloads(text(args,"path"));
            if("upload".equals(action))return upload(selector,text(args,"path"));
            if("paginate".equals(action))return paginate(args);
            if("snapshot".equals(action))return ok(session.evaluate("JSON.stringify({title:document.title,url:location.href,headings:[...document.querySelectorAll('h1,h2,h3')].slice(0,100).map(e=>(e.innerText||'').trim()),forms:[...document.forms].map(f=>[...f.elements].slice(0,100).map(e=>({tag:e.tagName,type:e.type||'',name:e.name||'',id:e.id||'',label:e.getAttribute('aria-label')||'',value:e.value||''}))),buttons:[...document.querySelectorAll('button,input[type=submit]')].slice(0,100).map(e=>({text:(e.innerText||e.value||'').trim(),id:e.id||'',name:e.name||''}))})"));
            if("form".equals(action))return fillForm(value);
            if("links".equals(action))return ok(session.evaluate("JSON.stringify([...document.querySelectorAll('a[href]')].slice(0,200).map(a=>({text:(a.innerText||a.textContent||'').trim(),href:a.href})))"));
            if("scroll".equals(action)){String amount=value.isEmpty()?"window.innerHeight":value;return ok(session.evaluate("window.scrollBy(0,"+numeric(amount)+"); '已滚动'"));}
            if(selector.isEmpty())return ToolResult.error(action+" 需要 selector 参数");
            String q="document.querySelector("+js(selector)+")";
            if("click".equals(action)){
                ToolResult result=ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到元素');e.click();return '已点击 '+"+js(selector)+"})()"));
                session.waitForPage(10000);
                return result;
            }
            if("type".equals(action))return ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到元素');e.focus();__minion_set_value(e,"+js(value)+");return '已输入' })()"));
            if("select".equals(action))return ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到元素');e.value="+js(value)+";e.dispatchEvent(new Event('change',{bubbles:true}));return '已选择' })()"));
            if("text".equals(action))return ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到元素');return e.innerText||e.textContent||''})()"));
            if("table".equals(action))return ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到表格');return JSON.stringify([...e.querySelectorAll('tr')].map(r=>[...r.querySelectorAll('th,td')].map(c=>(c.innerText||'').trim())))})()"));
            if("wait".equals(action)||"wait_selector".equals(action))return waitFor(selector,integer(args,"timeoutMs",10000));
            return ToolResult.error("未知 action: "+action+"（支持 click/type/select/text/table/links/snapshot/form/scroll/wait_selector/network_idle/upload/download_setup/paginate/tabs/new_tab/switch_tab/close_tab/manual_takeover）");
        }catch(IOException e){return ToolResult.error(e.getMessage());}
    }
    private ToolResult configureDownloads(String raw)throws IOException{
        if(workspace==null)return ToolResult.error("下载功能未配置工作区");Path dir=raw.trim().isEmpty()?workspace.cwd().resolve("downloads"):workspace.cwd().resolve(raw).normalize().toAbsolutePath();ToolResult g=PathsGuard.errorIfOutside(workspace.workDir(),dir);if(g!=null)return g;Files.createDirectories(dir);return ok(session.configureDownloads(dir.toString()));
    }
    private ToolResult upload(String selector,String raw)throws IOException{
        if(workspace==null)return ToolResult.error("上传功能未配置工作区");if(selector.trim().isEmpty()||raw.trim().isEmpty())return ToolResult.error("upload 需要 selector 和 path");List<String> files=new ArrayList<String>();
        for(String item:raw.split("[,\\n]")){if(item.trim().isEmpty())continue;Path p=workspace.cwd().resolve(item.trim()).normalize().toAbsolutePath();ToolResult g=PathsGuard.errorIfOutside(workspace.workDir(),p);if(g!=null)return g;if(!Files.isRegularFile(p))return ToolResult.error("上传文件不存在: "+p);files.add(p.toString());}
        return files.isEmpty()?ToolResult.error("没有可上传文件"):ok(session.upload(selector,files));
    }
    private ToolResult fillForm(String value)throws IOException{
        JsonObject fields;try{fields=new Gson().fromJson(value,JsonObject.class);}catch(Exception e){return ToolResult.error("form 的 value 必须是 JSON 对象：CSS选择器到填写值的映射");}
        if(fields==null||fields.size()==0)return ToolResult.error("form 的 value 不能为空");
        return ok(session.evaluate("(()=>{let fields="+fields.toString()+",done=[];Object.keys(fields).forEach(s=>{let e=document.querySelector(s);if(!e)throw Error('未找到元素: '+s);let v=fields[s];if(e.type==='checkbox'||e.type==='radio'){e.checked=!!v;e.dispatchEvent(new Event('change',{bubbles:true}))}else{e.focus();__minion_set_value(e,String(v))}done.push(s)});return '已填写 '+done.length+' 个字段'})()"));
    }
    private ToolResult paginate(JsonObject args)throws IOException{
        String selector=text(args,"selector"),next=text(args,"nextSelector"),outputPath=text(args,"outputPath");int pages=Math.max(1,Math.min(integer(args,"pages",5),100)),delay=Math.max(100,Math.min(integer(args,"timeoutMs",1000),10000));StringBuilder out=new StringBuilder();
        for(int i=0;i<pages;i++){String script=selector.trim().isEmpty()?"document.body.innerText":"(()=>{let e=document.querySelector("+js(selector)+");return e?(e.innerText||e.textContent||''):''})()";out.append("--- 第 ").append(i+1).append(" 页 ---\n").append(session.evaluate(script)).append('\n');if(i+1>=pages)break;if(next.trim().isEmpty()){session.evaluate("window.scrollBy(0,window.innerHeight); 'ok'");}else{String clicked=session.evaluate("(()=>{let e=document.querySelector("+js(next)+");if(!e||e.disabled||e.getAttribute('aria-disabled')==='true')return 'STOP';e.click();return 'NEXT'})()");if(!"NEXT".equals(clicked))break;session.waitForPage(Math.max(3000,delay*3));}try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        String full=out.toString();if(!outputPath.trim().isEmpty()){if(workspace==null)return ToolResult.error("分页结果落盘未配置工作区");Path p=workspace.cwd().resolve(outputPath).normalize().toAbsolutePath();ToolResult g=PathsGuard.errorIfOutside(workspace.workDir(),p);if(g!=null)return g;if(p.getParent()!=null)Files.createDirectories(p.getParent());Files.write(p,full.getBytes(StandardCharsets.UTF_8));return ToolResult.success("分页抓取结果已保存: "+p+"\n字符数: "+full.length());}return ToolResult.success(full.length()>60000?full.substring(0,60000)+"\n[输出已截断；可用 outputPath 保存完整结果]":full);
    }
    private ToolResult waitFor(String selector,int timeout)throws IOException{
        long end=System.currentTimeMillis()+Math.max(100,Math.min(timeout,60000));
        while(System.currentTimeMillis()<end){if("true".equals(session.evaluate("!!document.querySelector("+js(selector)+")")))return ToolResult.success("元素已出现: "+selector);try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        return ToolResult.error("等待元素超时: "+selector);
    }
    private static ToolResult ok(String value){return ToolResult.success(value==null?"":value);}
    private static String js(String s){return new Gson().toJson(s==null?"":s);}
    private static String numeric(String s){try{return String.valueOf(Integer.parseInt(s));}catch(Exception e){return "window.innerHeight";}}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?Integer.parseInt(o.get(k).getAsString()):d;}catch(Exception e){return d;}}
}
