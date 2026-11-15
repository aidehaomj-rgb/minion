package com.minion.core.tools.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import com.minion.core.tools.dev.ArtifactVerifier;
import com.minion.core.tools.PathsGuard;

/**
 * 浏览器会话:懒启动 Chrome、封装 CDP 命令、网络/console 事件查询。
 * 应用内单例(BrowserSession 非线程安全,工具执行已串行化)。
 */
public class BrowserSession {

    private final ChromeLauncher launcher;
    private final CdpClient client;
    private volatile String currentUrl = "";
    private volatile boolean domainsEnabled;
    private volatile boolean helperInjected;
    private final BrowserReliability reliability = new BrowserReliability(this);
    private Path downloadDirectory;
    private long downloadCursor;
    private final java.util.Map<Path,String> existingDownloads = new java.util.HashMap<Path,String>();
    private static final String SET_VALUE_HELPER = "window.__minion_set_value=function(el,v){var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');if(d&&d.set){d.set.call(el,v);}else{el.value=v;}el.dispatchEvent(new el.ownerDocument.defaultView.Event('input',{bubbles:true}));el.dispatchEvent(new el.ownerDocument.defaultView.Event('change',{bubbles:true}));};";

    BrowserReliability reliability() { return reliability; }
    public synchronized void assertAutomaticAllowed() throws IOException { reliability.assertAutomaticAllowed(); }
    public synchronized JsonObject automationState() { return reliability.state(); }

    public BrowserSession(ChromeLauncher launcher, CdpClient client) {
        this.launcher = launcher;
        this.client = client;
    }

    // 注意:AgentLoop 同回合并行执行工具,公开方法用 synchronized 串行化,
    // 避免多个工具并发触发 ensureConnected 的双连接竞态。

    private void ensureConnected() throws IOException {
        if (client.isConnected()) return;
        // 断线重连:旧页面的域启用/辅助函数注入均失效,需重新执行
        domainsEnabled = false;
        helperInjected = false;
        String ws;
        try {
            ws = launcher.pageEndpoint();
        } catch (Exception e) {
            throw new IOException("浏览器启动失败: " + e.getMessage());
        }
        client.connect(ws);
    }

    /** Network/Runtime 事件域启用(幂等):网络记录与 console 日志的前提 */
    private void enableDomains() throws IOException {
        if (domainsEnabled) return;
        client.command("Network.enable", new JsonObject());
        client.command("Runtime.enable", new JsonObject());
        client.command("Page.enable", new JsonObject());
        domainsEnabled = true;
    }

    /**
     * 注入页面级辅助函数(幂等):__minion_set_value(el, v) ——
     * React/Vue 受控组件填值:原生 value setter + 触发 input 事件,
     * 模型填表时直接用,不用手写事件细节。
     */
    private void ensureHelper() throws IOException {
        if (helperInjected) return;
        JsonObject params = new JsonObject();
        params.addProperty("expression", SET_VALUE_HELPER);
        params.addProperty("returnByValue", true);
        client.command("Runtime.evaluate", params);
        helperInjected = true;
    }

    public synchronized String open(String url) throws IOException {
        assertAutomaticAllowed();
        ensureConnected();
        enableDomains();
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        JsonObject navigation = client.command("Page.navigate", params);
        if (navigation.has("errorText")) throw new IOException("导航失败: " + navigation.get("errorText").getAsString());
        helperInjected = false;
        currentUrl = url;
        if (navigation.has("loaderId")) waitForLoader(navigation.get("loaderId").getAsString(),15000);
        waitForPage(15000);
        return "已打开: " + url;
    }

    public synchronized String back() throws IOException {
        assertAutomaticAllowed();
        ensureConnected();
        enableDomains();
        JsonObject history = client.command("Page.getNavigationHistory", new JsonObject());
        int index = history.get("currentIndex").getAsInt();
        if (index <= 0) throw new IOException("没有可后退的历史页面");
        JsonObject back = new JsonObject();
        back.addProperty("entryId", history.getAsJsonArray("entries").get(index - 1).getAsJsonObject().get("id").getAsInt());
        client.command("Page.navigateToHistoryEntry", back);
        helperInjected = false;
        waitForPage(15000);
        return "已后退";
    }

    public synchronized String refresh() throws IOException {
        assertAutomaticAllowed();
        ensureConnected();
        enableDomains();
        client.command("Page.reload", new JsonObject());
        helperInjected = false;
        waitForPage(15000);
        return "已刷新";
    }

    /** 等待页面加载完成(readyState=complete,上限 timeoutMs;未连接/SPA 无 load 事件时不阻塞) */
    public synchronized void waitForPage(int timeoutMs) throws IOException {
        if (!client.isConnected()) return; // 未连接由后续 ensureConnected 负责
        long deadline = BrowserReliability.deadline(timeoutMs);
        while (System.nanoTime() < deadline) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("expression", "document.readyState");
                params.addProperty("returnByValue", true);
                JsonObject r = client.command("Runtime.evaluate", params);
                if(r.has("exceptionDetails"))throw new IOException("JS 异常: "+r.getAsJsonObject("exceptionDetails"));
                if (r.has("result") && r.getAsJsonObject("result").has("value")
                        && "complete".equals(r.getAsJsonObject("result").get("value").getAsString())) {
                    return;
                }
            } catch (IOException e) {
                if(!e.getMessage().contains("Execution context was destroyed")&&!e.getMessage().contains("Cannot find context"))throw e;
            }
            try { Thread.sleep(300); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("等待页面已取消", e); }
        }
        throw new IOException("等待页面readyState=complete超时，未确认导航完成");
    }

    private void waitForLoader(String loaderId,int timeoutMs)throws IOException {
        long end=BrowserReliability.deadline(timeoutMs);
        do {
            JsonObject tree=client.command("Page.getFrameTree",new JsonObject());
            if(tree.has("frameTree")&&loaderId.equals(value(tree.getAsJsonObject("frameTree").getAsJsonObject("frame"),"loaderId")))return;
            ArtifactVerifier.pause(100);
        }while(System.nanoTime()<end);
        throw new IOException("导航loader未更新，旧页面不能当新页面完成");
    }

    /** 执行 JS 并返回值;JS 异常附最近 3 条 console 错误 */
    public synchronized String evaluate(String expression) throws IOException {
        assertAutomaticAllowed();
        return inspect(expression);
    }

    /** 仅包内固定探针使用；不可向模型暴露为任意 JS 绕过接管闩锁。 */
    synchronized String inspect(String expression) throws IOException {
        ensureConnected();
        enableDomains();
        ensureHelper();
        JsonObject params = new JsonObject();
        // 语义click/SPA/用户导航可替换document，不能仅依赖Java侧helperInjected缓存。
        params.addProperty("expression", "if(typeof window.__minion_set_value!=='function'){"+SET_VALUE_HELPER+"}\n"+expression);
        params.addProperty("returnByValue", true);
        JsonObject r = client.command("Runtime.evaluate", params);
        if (r.has("exceptionDetails")) {
            String text = r.getAsJsonObject("exceptionDetails").get("text").getAsString();
            throw new IOException("JS 异常: " + text + consoleErrors(3));
        }
        JsonObject result = r.has("result") ? r.getAsJsonObject("result") : new JsonObject();
        if (!result.has("value")) return "(无返回值)";
        return String.valueOf(result.get("value"));
    }

    /** 截图(路径已由工具层守卫;fullPage=true 时 captureBeyondViewport 截全页) */
    public synchronized String screenshot(String absPath, boolean fullPage) throws IOException {
        ensureConnected();
        JsonObject params = new JsonObject();
        if (fullPage) params.addProperty("captureBeyondViewport", true);
        JsonObject r = client.command("Page.captureScreenshot", params);
        if (!r.has("data")) return "截图失败: 无数据";
        byte[] png = Base64.getDecoder().decode(r.get("data").getAsString());
        Path target = Paths.get(absPath);
        if (target.getParent() != null) Files.createDirectories(target.getParent()); // 同 WriteTool：目录不存在时先建
        Files.write(target, png);
        return "截图已保存: " + absPath;
    }

    /** 网络请求汇总:method url → status,耗时 ms(按 requestId 关联) */
    public String debugNetwork(int limit) {
        StringBuilder sb = new StringBuilder();
        List<JsonObject> sent = eventData("Network.requestWillBeSent");
        List<JsonObject> got = eventData("Network.responseReceived");
        int shown = 0;
        for (JsonObject e : sent) {
            if (shown >= limit) break;
            JsonObject req = e.getAsJsonObject("request");
            String method = req.get("method").getAsString();
            String url = req.get("url").getAsString();
            String tail = "";
            String id = e.get("requestId").getAsString();
            double t1 = e.has("timestamp") ? e.get("timestamp").getAsDouble() : 0;
            for (JsonObject g : got) {
                if (id.equals(g.get("requestId").getAsString())) {
                    String status = g.getAsJsonObject("response").get("status").getAsString();
                    double t2 = g.has("timestamp") ? g.get("timestamp").getAsDouble() : 0;
                    tail = " → " + status
                            + (t1 > 0 && t2 > 0 ? ", " + Math.round((t2 - t1) * 1000) + "ms" : "");
                    break;
                }
            }
            sb.append(method).append(' ').append(truncate(url, 120)).append(tail).append('\n');
            shown++;
            if (sb.length() > 20000) { sb.append("... 输出过长已截断\n"); break; }
        }
        return sb.toString().trim().isEmpty() ? "暂无网络记录(需先打开页面)" : sb.toString();
    }

    /** console 日志(错误标 [ERROR]) */
    public String debugConsole(int limit) {
        StringBuilder sb = new StringBuilder();
        List<JsonObject> logs = eventData("Runtime.consoleAPICalled");
        int from = Math.max(0, logs.size() - limit);
        for (int i = from; i < logs.size(); i++) {
            JsonObject e = logs.get(i);
            String type = e.get("type").getAsString();
            StringBuilder args = new StringBuilder();
            JsonElement argsArr = e.get("args");
            if (argsArr != null && argsArr.isJsonArray()) {
                for (JsonElement a : argsArr.getAsJsonArray()) {
                    JsonObject o = a.getAsJsonObject();
                    if (o.has("value")) args.append(o.get("value")).append(' ');
                }
            }
            sb.append("error".equals(type) ? "[ERROR] " : "[").append(type).append("] ")
              .append(args).append('\n');
        }
        return sb.toString().trim().isEmpty() ? "暂无 console 日志" : sb.toString();
    }

    /** evaluate 异常时附带的 console 错误摘要 */
    private String consoleErrors(int n) {
        List<JsonObject> logs = eventData("Runtime.consoleAPICalled");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, logs.size() - n);
        for (int i = from; i < logs.size(); i++) {
            JsonObject e = logs.get(i);
            if (!"error".equals(e.get("type").getAsString())) continue;
            JsonElement argsArr = e.get("args");
            if (argsArr != null && argsArr.isJsonArray()) {
                for (JsonElement a : argsArr.getAsJsonArray()) {
                    JsonObject o = a.getAsJsonObject();
                    if (o.has("value")) sb.append(' ').append(o.get("value"));
                }
            }
        }
        return sb.length() == 0 ? "" : "\n最近 console 错误:" + sb;
    }

    /** 当前页面信息:标题 + URL(已连接时实时查询;未连接提示先 open) */
    public String pageInfo() {
        if (!client.isConnected()) {
            return "当前页面: (未打开,可用 action=open 打开)";
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression", "document.title + ' | ' + location.href");
            params.addProperty("returnByValue", true);
            JsonObject r = client.command("Runtime.evaluate", params);
            if (r.has("result") && r.getAsJsonObject("result").has("value")) {
                return "当前页面: " + r.getAsJsonObject("result").get("value").getAsString();
            }
        } catch (IOException ignored) { }
        return "当前页面: " + (currentUrl.isEmpty() ? "(未知)" : currentUrl);
    }

    /** 等到 performance 资源数稳定一段时间，适配 SPA/Ajax 页面。 */
    public synchronized String waitForNetworkIdle(int timeoutMs, int idleMs) throws IOException {
        assertAutomaticAllowed();ensureConnected(); enableDomains();
        long end=BrowserReliability.deadline(timeoutMs),stableAt=System.nanoTime(),previous=-1;
        do {
            if(client.hasEventGap(0))throw new IOException("网络事件缓冲已截断，不能证明无在途请求；请用关键元素/业务回执核对");
            java.util.Set<String> active=new java.util.HashSet<String>();
            for(JsonObject event:client.events("Network.")){
                JsonObject p=event.has("params")?event.getAsJsonObject("params"):event;String method=value(event,"method"),id=value(p,"requestId");
                if("Network.requestWillBeSent".equals(method)&&!"WebSocket".equals(value(p,"type"))&&!"EventSource".equals(value(p,"type")))active.add(id);
                if("Network.loadingFinished".equals(method)||"Network.loadingFailed".equals(method))active.remove(id);
            }
            long cursor=client.eventCursor();
            if(!active.isEmpty()||cursor!=previous){stableAt=System.nanoTime();previous=cursor;}
            String ready=inspect("document.readyState");
            if(active.isEmpty()&&("\"complete\"".equals(ready)||"complete".equals(ready))&&System.nanoTime()-stableAt>=Math.max(200,Math.min(idleMs,5000))*1000000L)
                return "观察窗口内无在途请求（不证明业务数据/未来请求完成）";
            ArtifactVerifier.pause(100);
        }while(System.nanoTime()<end);
        throw new IOException("等待网络空闲超时，未证明页面完成");
    }

    /** 使用 CDP 为 file input 设置本机文件，绕过脚本无法写入 input.files 的浏览器限制。 */
    public synchronized String upload(String selector, List<String> files) throws IOException {
        assertAutomaticAllowed();
        return uploadUnchecked(selector,files);
    }

    synchronized String uploadUnchecked(String selector, List<String> files) throws IOException {
        ensureConnected();
        JsonObject doc=client.command("DOM.getDocument",new JsonObject());
        int root=doc.getAsJsonObject("root").get("nodeId").getAsInt();
        JsonObject query=new JsonObject();query.addProperty("nodeId",root);query.addProperty("selector",selector);
        JsonObject result=client.command("DOM.querySelectorAll",query);JsonArray nodes=result.getAsJsonArray("nodeIds");
        if(nodes==null||nodes.size()!=1)throw new IOException("上传控件必须唯一匹配: "+selector);
        int node=nodes.get(0).getAsInt();
        JsonObject params=new JsonObject();params.addProperty("nodeId",node);JsonArray paths=new JsonArray();for(String file:files)paths.add(file);params.add("files",paths);
        client.command("DOM.setFileInputFiles",params);
        return "已选择 "+files.size()+" 个文件（尚未验证上传成功，必须核对服务端页面回执）";
    }

    public synchronized String configureDownloads(String path) throws IOException {
        assertAutomaticAllowed();
        ensureConnected();JsonObject p=new JsonObject();p.addProperty("behavior","allow");p.addProperty("downloadPath",path);p.addProperty("eventsEnabled",true);
        try{client.command("Browser.setDownloadBehavior",p);}catch(IOException e){client.command("Page.setDownloadBehavior",p);}
        downloadDirectory = Paths.get(path).toAbsolutePath().normalize(); downloadCursor = client.eventCursor();
        existingDownloads.clear();
        try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(downloadDirectory)){
            for(Path file:files){if(existingDownloads.size()>=10000)throw new IOException("下载目录文件过多，请使用新的独立目录");if(Files.isRegularFile(file))existingDownloads.put(file.toAbsolutePath().normalize(),Files.size(file)+"|"+Files.getLastModifiedTime(file).toMillis());}
        }
        return "下载目录已配置（不表示已下载）: "+path+"；完成需download_status校验CDP completed、真实稳定文件与SHA-256；旧Chrome无下载事件时为unknown";
    }

    public synchronized String tabs() throws IOException {
        JsonArray pages=JsonParser.parseString(launcher.pagesJson()).getAsJsonArray();StringBuilder out=new StringBuilder();
        for(JsonElement e:pages){JsonObject p=e.getAsJsonObject();if(!"page".equals(value(p,"type")))continue;out.append(value(p,"id")).append("  ").append(value(p,"title")).append("  ").append(value(p,"url")).append('\n');}
        return out.length()==0?"没有标签页":out.toString().trim();
    }

    public synchronized String newTab(String url) throws IOException {
        assertAutomaticAllowed();
        ensureConnected();JsonObject p=new JsonObject();p.addProperty("url",url==null||url.trim().isEmpty()?"about:blank":url);JsonObject r=client.command("Target.createTarget",p);
        return "已创建标签页: "+(r.has("targetId")?r.get("targetId").getAsString():"完成");
    }

    public synchronized String switchTab(String targetId) throws IOException {
        assertAutomaticAllowed();
        JsonArray pages=JsonParser.parseString(launcher.pagesJson()).getAsJsonArray();String endpoint=null,url="";
        for(JsonElement e:pages){JsonObject p=e.getAsJsonObject();if(targetId.equals(value(p,"id"))){endpoint=value(p,"webSocketDebuggerUrl");url=value(p,"url");break;}}
        if(endpoint==null||endpoint.isEmpty())throw new IOException("标签页不存在: "+targetId);
        client.disconnect();domainsEnabled=false;helperInjected=false;client.connect(endpoint);currentUrl=url;enableDomains();
        return "已切换标签页: "+targetId+"  "+url;
    }

    public synchronized String closeTab(String targetId) throws IOException {
        assertAutomaticAllowed();
        ensureConnected();JsonObject p=new JsonObject();p.addProperty("targetId",targetId);client.command("Target.closeTarget",p);return "已关闭标签页: "+targetId;
    }

    private static String value(JsonObject o,String key){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():"";}

    private List<JsonObject> eventData(String prefix) {
        List<JsonObject> out = new java.util.ArrayList<JsonObject>();
        for (JsonObject e : client.events(prefix)) out.add(e.has("params") ? e.getAsJsonObject("params") : e);
        return out;
    }

    public synchronized long diagnosticCursor() throws IOException { ensureConnected(); enableDomains(); return client.eventCursor(); }

    public synchronized JsonObject diagnosticsSince(long cursor) {
        JsonObject out = new JsonObject(); JsonArray console = new JsonArray(), failed = new JsonArray(), responses = new JsonArray();
        for (JsonObject event : client.eventsSince("", cursor)) {
            String method = value(event,"method"); JsonObject p = event.has("params") ? event.getAsJsonObject("params") : event;
            if ("Runtime.consoleAPICalled".equals(method) && "error".equals(value(p,"type"))) console.add(p.deepCopy());
            if ("Runtime.exceptionThrown".equals(method)) console.add(p.deepCopy());
            if ("Network.loadingFailed".equals(method)) failed.add(p.deepCopy());
            if ("Network.responseReceived".equals(method)) {
                JsonObject response = p.getAsJsonObject("response"); responses.add(response.deepCopy());
                if (response.has("status") && response.get("status").getAsInt() >= 400) failed.add(response.deepCopy());
            }
        }
        boolean gap = client.hasEventGap(cursor);
        out.addProperty("eventsComplete", !gap); out.addProperty("cursor", cursor); out.addProperty("endCursor", client.eventCursor());
        out.add("consoleErrors", console); out.add("failedRequests", failed); out.add("responses", responses);
        out.addProperty("status", console.size()>0 || failed.size()>0 ? "failed" : gap ? "unknown" : "passed");
        out.addProperty("scope", "observed_events_only_not_future_errors"); return out;
    }

    public synchronized JsonObject visibleElement(String selector, String frame, int timeoutMs) throws IOException {
        return reliability.waitFor(selector, frame == null ? "" : frame, false, timeoutMs);
    }

    /** 固定语义交互入口；供验证工具复用，仍执行版本/登录/回执闩锁。 */
    public synchronized JsonObject verifiedClick(JsonObject args) throws IOException {
        return reliability.act("click",args);
    }

    public synchronized JsonObject pageSnapshot(String selector, String frame) throws IOException {
        return reliability.probe(selector,frame==null?"":frame,false);
    }

    public synchronized JsonObject downloadStatus(String guid, Path file, int timeoutMs, java.util.function.Predicate<Path> allowed) throws Exception {
        if (downloadDirectory == null) throw new IOException("请先配置受控下载目录");
        ArtifactVerifier.checkPath(file, allowed);
        if (!PathsGuard.inside(downloadDirectory.toString(), file)) throw new IOException("文件不在本次下载目录内");
        JsonObject out = new JsonObject(); out.addProperty("status","unknown"); out.addProperty("guid",guid);
        if (guid.isEmpty()) { JsonArray downloads = new JsonArray();
            for (JsonObject e : client.eventsSince("",downloadCursor)) if (value(e,"method").endsWith("downloadWillBegin")) downloads.add(e.getAsJsonObject("params").deepCopy());
            out.add("downloads", downloads); out.addProperty("reason","选择明确guid及其文件路径后核验；没有事件不等于完成"); return out;
        }
        JsonObject begin = null, progress = null;
        for (JsonObject e : client.eventsSince("",downloadCursor)) {
            String method = value(e,"method"); JsonObject p = e.has("params") ? e.getAsJsonObject("params") : new JsonObject();
            if (!guid.equals(value(p,"guid"))) continue;
            if (method.endsWith("downloadWillBegin")) begin=p;
            if (method.endsWith("downloadProgress")) progress=p;
        }
        if (begin != null) out.add("download",begin.deepCopy()); if (progress != null) out.add("progress",progress.deepCopy());
        out.addProperty("eventsComplete",!client.hasEventGap(downloadCursor));
        if (progress != null && "canceled".equals(value(progress,"state"))) { out.addProperty("status","failed"); return out; }
        if (begin==null || progress==null || !"completed".equals(value(progress,"state")) || client.hasEventGap(downloadCursor)) {
            out.addProperty("reason","下载事件缺失/未完成/截断，不能认定完成"); return out;
        }
        String actual = value(progress,"filePath");
        if (!actual.isEmpty() ? !Paths.get(actual).toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())
                : !value(begin,"suggestedFilename").equals(file.getFileName().toString())) throw new IOException("guid与文件名不匹配，不能将其他文件当下载成果");
        JsonObject receipt = ArtifactVerifier.stableFile(file,500,timeoutMs,allowed);
        long size = receipt.get("size").getAsLong();
        String baseline=existingDownloads.get(file.toAbsolutePath().normalize());
        if(baseline!=null&&baseline.equals(size+"|"+receipt.get("modifiedAt").getAsLong()))throw new IOException("文件与下载配置前相同，不能将已有文件冒充新下载");
        if (progress.has("receivedBytes") && progress.get("receivedBytes").getAsLong()!=size) throw new IOException("实际文件大小与下载接收字节不符");
        if (progress.has("totalBytes") && progress.get("totalBytes").getAsLong()>0 && progress.get("totalBytes").getAsLong()!=size) throw new IOException("下载总字节不符");
        out.add("file",receipt); out.addProperty("status","passed"); out.addProperty("scope","cdp_completed_and_stable_file_not_content_validation"); return out;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
