package com.minion.core.tools.browser;

import com.google.gson.*;
import com.minion.core.tools.*;
import com.minion.core.tools.plugin.BrowserManager;
import com.minion.core.tools.dev.ArtifactVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 语义动作、人工接管、下载/分页可核验回执。不得把配置成功冒充业务完成。 */
public class BrowserActionTool implements Tool {
    private final BrowserSession fixed;
    private final BrowserManager manager;
    private final Workspace workspace;
    public BrowserActionTool(BrowserSession session) { this(session,null); }
    public BrowserActionTool(BrowserSession session,Workspace workspace) { this.fixed=session;this.manager=null;this.workspace=workspace; }
    /** 注册应使用 manager，保留插件启用 gate；不要捕获旧的 session。 */
    public BrowserActionTool(BrowserManager manager,Workspace workspace) { this.fixed=null;this.manager=manager;this.workspace=workspace; }
    @Override public String name() { return "BrowserAction"; }
    @Override public String description() { return "浏览器语义动作；wait检查真正可见/可交互；click要求expectedVersion及receiptSelector，不盲重试。frameSelector必须明确且唯一（跨域不回退）；人工接管后resume需用户确认/令牌/锚点。下载配置≠完成；分页输出覆盖证据。"; }
    @Override public JsonObject schema() {
        JsonObject s=SchemaGenerator.objectSchema(description(),new String[]{"action","selector","frameSelector","nextSelector","rowSelector","keySelector","keyAttribute","pageSelector","expectedVersion","receiptSelector","receiptText","value","path","outputPath","targetId","guid","resumeToken","resolution"},new String[]{"action"});
        JsonObject p=s.getAsJsonObject("properties");
        for(String k:Arrays.asList("timeoutMs","pages","expectedPages","expectedRecords","firstPage")){JsonObject x=new JsonObject();x.addProperty("type","integer");p.add(k,x);}
        for(String k:Arrays.asList("interactive","userConfirmed")){JsonObject x=new JsonObject();x.addProperty("type","boolean");p.add(k,x);} return s;
    }
    @Override public ToolResult execute(JsonObject args) {
        BrowserSession session=manager==null?fixed:manager.session();
        if(session==null)return ToolResult.error(BrowserManager.NOT_READY);
        synchronized(session){try{return run(session,args);}catch(Exception e){return ToolResult.error(e.getMessage()==null?e.toString():e.getMessage());}}
    }
    private ToolResult run(BrowserSession s,JsonObject a)throws Exception {
        String action=text(a,"action").toLowerCase(Locale.ROOT),selector=text(a,"selector"),frame=text(a,"frameSelector");
        BrowserReliability r=s.reliability();
        if("status".equals(action)){JsonObject state=r.state();state.add("page",r.probe(selector,frame,false));return ok(state);}
        if("manual_takeover".equals(action))return ok(r.pause(text(a,"value")));
        if("resume".equals(action))return ok(r.resume(a));
        if("download_status".equals(action)){Path p=path(text(a,"path").isEmpty()?"downloads":text(a,"path"));return result(s.downloadStatus(text(a,"guid"),p,integer(a,"timeoutMs",10000),this::allowed));}
        r.assertAutomaticAllowed();
        if("tabs".equals(action))return ToolResult.success(s.tabs());
        if("new_tab".equals(action))return ToolResult.success(s.newTab(text(a,"value")));
        if("switch_tab".equals(action))return ToolResult.success(s.switchTab(text(a,"targetId")));
        if("close_tab".equals(action))return ToolResult.success(s.closeTab(text(a,"targetId")));
        if("network_idle".equals(action))return ToolResult.success(s.waitForNetworkIdle(integer(a,"timeoutMs",15000),800));
        if("download_setup".equals(action)){Path p=path(text(a,"path").isEmpty()?"downloads":text(a,"path"));Files.createDirectories(p);return ToolResult.success(s.configureDownloads(p.toString()));}
        if("upload".equals(action))return upload(s,a);
        if("paginate".equals(action))return paginate(s,a);
        if("form".equals(action))return ToolResult.error("form批量副作用已禁用；请逐字段type/select并核对，提交单独click+回执");
        if("snapshot".equals(action))return ok(r.probe(selector,frame,false));
        if("scroll".equals(action)){int amount=integer(a,"value",600);return ToolResult.success(s.evaluate("(function(){"+BrowserReliability.documentScript(frame)+"d.defaultView.scrollBy(0,"+Math.max(-10000,Math.min(amount,10000))+");return '已滚动，尚未证明新数据加载';})()"));}
        if("links".equals(action))return ToolResult.success(s.evaluate("(function(){"+BrowserReliability.documentScript(frame)+"return Array.prototype.map.call(d.querySelectorAll('a[href]'),function(e){return {text:(e.innerText||'').slice(0,1000),href:e.href}}).slice(0,200)})()"));
        if(selector.isEmpty())return ToolResult.error(action+" 需要 selector 参数");
        if(Arrays.asList("wait","wait_selector").contains(action))return ok(r.waitFor(selector,frame,!a.has("interactive")||a.get("interactive").getAsBoolean(),integer(a,"timeoutMs",10000)));
        if(Arrays.asList("click","type","select").contains(action))return ok(r.act(action,a));
        if("text".equals(action))return ok(r.waitFor(selector,frame,false,integer(a,"timeoutMs",10000)));
        if("table".equals(action)){r.waitFor(selector,frame,false,integer(a,"timeoutMs",10000));return ToolResult.success(s.evaluate("(function(){"+BrowserReliability.documentScript(frame)+"var list=d.querySelectorAll("+js(selector)+");if(list.length!==1)throw Error('表格必须唯一');return Array.prototype.map.call(list[0].querySelectorAll('tr'),function(r){return Array.prototype.map.call(r.querySelectorAll('th,td'),function(c){return (c.innerText||'').trim()})})})()"));}
        return ToolResult.error("未知 action: "+action);
    }
    private ToolResult upload(BrowserSession s,JsonObject a)throws Exception {
        String selector=text(a,"selector");if(selector.isEmpty()||text(a,"path").isEmpty())return ToolResult.error("upload需要selector/path");
        if(!text(a,"frameSelector").isEmpty())return ToolResult.error("iframe上传尚未支持；未回退主页面");
        List<String> files=new ArrayList<String>();for(String raw:text(a,"path").split("[,\\n]")){if(raw.trim().isEmpty())continue;Path p=path(raw.trim());if(!Files.isRegularFile(p))throw new IOException("上传文件不存在");files.add(p.toString());}
        if(files.isEmpty())return ToolResult.error("没有可上传文件");
        String receipt=text(a,"receiptSelector");
        if(!receipt.isEmpty()&&BrowserReliability.matches(s.reliability().probe(receipt,"",false),text(a,"receiptText")))return ToolResult.error("上传回执在选择文件前已存在，不能将旧回执当本次上传成功；未执行");
        s.reliability().beginUncertain("upload");
        try{
            String selected=s.uploadUnchecked(selector,files);
            if(receipt.isEmpty()){s.reliability().finishUncertain();return ToolResult.success(selected);}
            JsonObject observed=s.reliability().waitFor(receipt,"",false,integer(a,"timeoutMs",10000));
            if(!BrowserReliability.matches(observed,text(a,"receiptText")))throw new IOException("上传页面回执不匹配");
            s.reliability().finishUncertain();JsonObject out=new JsonObject();out.addProperty("status","passed");out.addProperty("scope","page_upload_receipt_not_server_content_hash");out.add("receipt",observed);return ok(out);
        }catch(Exception e){s.reliability().failedUncertain("上传结果不确定，人工核对后恢复");throw e;}
    }
    private ToolResult paginate(BrowserSession s,JsonObject a)throws Exception {
        String container=text(a,"selector"),row=text(a,"rowSelector"),key=text(a,"keySelector"),attribute=text(a,"keyAttribute"),marker=text(a,"pageSelector"),frame=text(a,"frameSelector"),next=text(a,"nextSelector");
        if(container.isEmpty()||row.isEmpty()||(key.isEmpty()&&attribute.isEmpty())||next.isEmpty())return ToolResult.error("paginate需要selector/rowSelector、keySelector或keyAttribute、nextSelector；禁止无唯一键盲抓取");
        Path output=text(a,"outputPath").isEmpty()?null:path(text(a,"outputPath"));
        if(output!=null&&Files.exists(output))return ToolResult.error("分页证据输出已存在，不覆盖: "+output);
        String capture="(function(){"+BrowserReliability.documentScript(frame)
            +"var cs=d.querySelectorAll("+js(container)+");if(cs.length!==1)throw Error('容器必须唯一');var rr=cs[0].querySelectorAll("+js(row)+");if(rr.length>10000)throw Error('单页超过10000行');var rows=Array.prototype.map.call(rr,function(r){var kk="+(key.isEmpty()?"[r]":"r.querySelectorAll("+js(key)+")")+";if(kk.length!==1)throw Error('行主键必须唯一匹配');return {key:String("+(attribute.isEmpty()?"kk[0].innerText||kk[0].textContent||''":"kk[0].getAttribute("+js(attribute)+")||''")+").trim(),text:String(r.innerText||r.textContent||'').trim()}});var mm="+(marker.isEmpty()?"[]":"d.querySelectorAll("+js(marker)+")")+";if(mm.length>1)throw Error('页标识必须唯一');return {url:d.location.href,marker:mm.length?String(mm[0].innerText||mm[0].textContent||'').trim():'',rows:rows};})()";
        PaginationEvidence evidence=new PaginationEvidence();int max=Math.max(1,Math.min(integer(a,"pages",5),100)),timeout=integer(a,"timeoutMs",10000),bytes=0;boolean exhausted=false;
        for(int i=0;i<max;i++){
            s.reliability().waitFor(container,frame,false,timeout);
            JsonObject page=BrowserReliability.object(s.inspect(capture));bytes+=page.toString().length();if(bytes>8*1024*1024)throw new IOException("分页证据超过8MB上限");
            page.add("page",s.reliability().probe(container,frame,false));
            if(!evidence.add(page))break;
            JsonObject n=s.reliability().probe(next,frame,true);
            if(n.get("matches").getAsInt()==0||BrowserReliability.bool(n,"disabled")){exhausted=true;break;}
            if(i+1==max)break;
            if(!BrowserReliability.bool(n,"interactive"))throw new IOException("下一页不可交互；未点击");
            String old=PaginationEvidence.fingerprint(page);
            s.reliability().beginUncertain("paginate_next");
            try{
                JsonObject clicked=BrowserReliability.object(s.inspect(BrowserReliability.script(frame,next,true,"if(!info.interactive||info.authRequired||info.pageVersion!=="+js(text(n,"pageVersion"))+")throw Error('下一页状态改变');e.click();return {executed:true};")));
                if(!BrowserReliability.bool(clicked,"executed"))throw new IOException("翻页没有执行回执");
                long end=BrowserReliability.deadline(timeout);boolean changed=false;String candidate="";int consistent=0;
                do{JsonObject current=BrowserReliability.object(s.inspect(capture));String fingerprint=PaginationEvidence.fingerprint(current);
                    if(current.getAsJsonArray("rows").size()>0&&!old.equals(fingerprint)){consistent=fingerprint.equals(candidate)?consistent+1:1;candidate=fingerprint;if(consistent>=2){changed=true;break;}}else{consistent=0;candidate="";}
                    ArtifactVerifier.pause(100);
                }while(System.nanoTime()<end);
                if(!changed){s.reliability().failedUncertain("下一页数据未变化；不计新页、不再次点击");JsonObject report=evidence.report(false,integer(a,"expectedPages",-1),integer(a,"expectedRecords",-1),integer(a,"firstPage",-1));report.addProperty("status","failed");report.addProperty("reason","duplicate_or_unchanged_page");return save(report,output);}
                s.reliability().finishUncertain();
            }catch(Exception e){s.reliability().failedUncertain("翻页回执不确定，禁止再次点击");JsonObject report=evidence.report(false,integer(a,"expectedPages",-1),integer(a,"expectedRecords",-1),integer(a,"firstPage",-1));report.addProperty("status","unknown");report.addProperty("reason",e.getMessage());report.add("automation",s.automationState());return save(report,output);}
        }
        return save(evidence.report(exhausted,integer(a,"expectedPages",-1),integer(a,"expectedRecords",-1),integer(a,"firstPage",-1)),output);
    }
    private ToolResult save(JsonObject report,Path output)throws Exception {
        String json=report.toString();
        if(output!=null){ArtifactVerifier.checkPath(output,this::allowed);if(output.getParent()!=null)Files.createDirectories(output.getParent());Files.write(output,json.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);JsonObject summary=report.deepCopy();summary.remove("records");summary.addProperty("evidencePath",output.toString());summary.addProperty("sha256",ArtifactVerifier.sha256(output));return result(summary);}
        if(json.length()>60000){JsonObject summary=report.deepCopy();summary.remove("records");summary.addProperty("recordsOmitted",true);summary.addProperty("reason","输出过大，需指定新outputPath保存完整记录；不可按省略结果认定已交付");summary.addProperty("status","partial");return result(summary);}return result(report);
    }
    private Path path(String raw)throws IOException {if(workspace==null)throw new IOException("功能未配置工作区");Path p=PathsGuard.resolve(workspace.cwd().toString(),raw).toAbsolutePath().normalize();ArtifactVerifier.checkPath(p,this::allowed);return p;}
    private boolean allowed(Path p){return workspace!=null&&PathsGuard.inside(workspace.workDir(),p);}
    private static ToolResult result(JsonObject o){return "passed".equals(text(o,"status"))?ok(o):ToolResult.error(o.toString());}
    private static ToolResult ok(JsonObject o){return ToolResult.success(o.toString());}
    private static String js(String s){return BrowserReliability.js(s);}
    private static String text(JsonObject o,String k){return BrowserReliability.text(o,k);}
    private static int integer(JsonObject o,String k,int d){return BrowserReliability.integer(o,k,d);}
}
