package com.minion.core.tools.browser;

import com.google.gson.*;
import com.minion.core.tools.*;
import com.minion.core.tools.dev.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** 本地HTTP/WebSocket CDP回放；不访问公网，不伪装真实Chrome/登录/运行验证。 */
public class BrowserProtocolGTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private MockWebServer cdp; private CdpClient client; private BrowserSession session; private WebSocket serverSocket;
    private String pageUrl="http://127.0.0.1/site";private boolean consoleFailure;private final AtomicInteger clicks=new AtomicInteger();
    private JsonObject args(String s){return JsonParser.parseString(s).getAsJsonObject();}
    @Before public void setup()throws Exception{
        cdp=new MockWebServer();cdp.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response r){serverSocket=ws;}
            @Override public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);}
            @Override public void onMessage(WebSocket ws,String raw){
                JsonObject m=JsonParser.parseString(raw).getAsJsonObject();String method=m.get("method").getAsString();JsonObject result=new JsonObject();
                if("Page.navigate".equals(method)){
                    pageUrl=m.getAsJsonObject("params").get("url").getAsString();
                    event(ws,"Network.responseReceived","{requestId:'nav',type:'Document',response:{url:"+new Gson().toJson(pageUrl)+",status:200}}");
                    if(consoleFailure)event(ws,"Runtime.consoleAPICalled","{type:'error',args:[{value:'script failed'}]}");
                }
                if("Runtime.evaluate".equals(method)){
                    String expr=m.getAsJsonObject("params").get("expression").getAsString();JsonObject remote=new JsonObject();
                    if(expr.equals("document.readyState"))remote.addProperty("value","complete");
                    else if(expr.contains("e.click();")){clicks.incrementAndGet();remote.add("value",args("{executed:true}"));}
                    else if(expr.contains("return info;")){
                        boolean visible=!expr.contains("d.querySelectorAll(\"#done\")")||clicks.get()>0;
                        JsonObject scene=args("{pageVersion:'doc-v1',visible:true,interactive:true,matches:1,disabled:false,authRequired:false,text:'READY saved'}");scene.addProperty("visible",visible);scene.addProperty("url",pageUrl);remote.add("value",scene);
                    }else remote.addProperty("value",true);
                    result.add("result",remote);
                }
                JsonObject response=new JsonObject();response.add("id",m.get("id"));response.add("result",result);ws.send(response.toString());
            }
        }));cdp.start();client=new CdpClient(2000,1000);
        ChromeLauncher launcher=new ChromeLauncher("",1,tmp.getRoot().toPath(),false,100){@Override public String pageEndpoint(){return cdp.url("/devtools/page/g").toString().replace("http:","ws:");}};
        session=new BrowserSession(launcher,client);session.diagnosticCursor();
    }
    @After public void teardown()throws Exception{client.disconnect();cdp.shutdown();}
    private void event(WebSocket ws,String method,String params){JsonObject o=new JsonObject();o.addProperty("method",method);o.add("params",args(params));ws.send(o.toString());}
    private void emit(String method,String params)throws Exception{event(serverSocket,method,params);client.command("Runtime.enable",new JsonObject());}
    @Test public void cdpParamsAreUnwrappedForConsoleAndNetworkDiagnostics()throws Exception{
        long cursor=session.diagnosticCursor();emit("Network.requestWillBeSent","{requestId:'1',timestamp:1,request:{method:'GET',url:'http://127.0.0.1/a'}}");emit("Network.responseReceived","{requestId:'1',timestamp:2,response:{status:503,url:'http://127.0.0.1/a'}}");emit("Runtime.consoleAPICalled","{type:'error',args:[{value:'broken'}]}");
        assertTrue(session.debugConsole(10).contains("broken"));assertTrue(session.debugNetwork(10).contains("503"));JsonObject d=session.diagnosticsSince(cursor);assertEquals("failed",d.get("status").getAsString());assertEquals(1,d.getAsJsonArray("consoleErrors").size());assertEquals(1,d.getAsJsonArray("failedRequests").size());
    }
    @Test public void oldConsoleErrorsAreNotNewNavigationFailures()throws Exception{emit("Runtime.consoleAPICalled","{type:'error',args:[{value:'old'}]}");long cursor=session.diagnosticCursor();assertEquals("passed",session.diagnosticsSince(cursor).get("status").getAsString());}
    @Test public void failedRequestAndUncaughtExceptionAreEvidence()throws Exception{long c=session.diagnosticCursor();emit("Network.loadingFailed","{requestId:'1',errorText:'net::ERR_CONNECTION_REFUSED'}");emit("Runtime.exceptionThrown","{exceptionDetails:{text:'ReferenceError'}}");assertEquals("failed",session.diagnosticsSince(c).get("status").getAsString());}
    @Test public void eventBufferOverflowIsUnknownNotCleanConsole()throws Exception{long c=session.diagnosticCursor();for(int i=0;i<505;i++)client.onMessage(null,"{\"method\":\"Runtime.consoleAPICalled\",\"params\":{\"type\":\"log\",\"args\":[]}}");JsonObject d=session.diagnosticsSince(c);assertFalse(d.get("eventsComplete").getAsBoolean());assertEquals("unknown",d.get("status").getAsString());}
    @Test public void downloadSetupAndExistingFileDoNotProveCompletion()throws Exception{Path dir=tmp.newFolder("downloads").toPath();Path file=dir.resolve("a.bin");Files.write(file,new byte[]{1,2,3});assertTrue(session.configureDownloads(dir.toString()).contains("不表示已下载"));JsonObject r=session.downloadStatus("g",file,1000,x->true);assertEquals("unknown",r.get("status").getAsString());}
    private void completed(String name,int received)throws Exception{emit("Browser.downloadWillBegin","{guid:'g',suggestedFilename:'"+name+"',url:'http://127.0.0.1/file'}");emit("Browser.downloadProgress","{guid:'g',state:'completed',receivedBytes:"+received+",totalBytes:"+received+"}");}
    @Test public void downloadRequiresCompletedGuidStableFileSizeAndHash()throws Exception{Path dir=tmp.newFolder("downloads").toPath();session.configureDownloads(dir.toString());Path f=dir.resolve("a.bin");Files.write(f,new byte[]{1,2,3});completed("a.bin",3);JsonObject r=session.downloadStatus("g",f,2000,x->true);assertEquals("passed",r.get("status").getAsString());assertEquals(ArtifactVerifier.sha256(f),r.getAsJsonObject("file").get("sha256").getAsString());assertFalse(r.getAsJsonObject("file").get("runtimeVerified").getAsBoolean());}
    @Test public void wrongDownloadSizeCannotPass()throws Exception{Path d=tmp.newFolder("downloads").toPath();session.configureDownloads(d.toString());Path f=d.resolve("a.bin");Files.write(f,new byte[]{1});completed("a.bin",3);try{session.downloadStatus("g",f,1000,x->true);fail();}catch(IOException e){assertTrue(e.getMessage().contains("字节"));}}
    @Test public void unchangedPreexistingDownloadIsRejected()throws Exception{Path d=tmp.newFolder("downloads").toPath(),f=d.resolve("a.bin");Files.write(f,new byte[]{1});session.configureDownloads(d.toString());completed("a.bin",1);try{session.downloadStatus("g",f,1000,x->true);fail();}catch(IOException e){assertTrue(e.getMessage().contains("已有文件"));}}
    @Test public void mismatchedGuidFileNameCannotBorrowAnotherArtifact()throws Exception{Path d=tmp.newFolder("downloads").toPath();session.configureDownloads(d.toString());Path f=d.resolve("other.bin");Files.write(f,new byte[]{1});completed("a.bin",1);try{session.downloadStatus("g",f,1000,x->true);fail();}catch(IOException e){assertTrue(e.getMessage().contains("不匹配"));}}
    @Test public void canceledDownloadFailsAndUnknownGuidIsUnknown()throws Exception{Path d=tmp.newFolder("downloads").toPath();session.configureDownloads(d.toString());Path f=d.resolve("a.bin");emit("Browser.downloadProgress","{guid:'g',state:'canceled'}");assertEquals("failed",session.downloadStatus("g",f,1000,x->true).get("status").getAsString());assertEquals("unknown",session.downloadStatus("other",f,1000,x->true).get("status").getAsString());}
    @Test public void workspaceDownloadBoundaryIsNotRelaxed()throws Exception{Path d=tmp.newFolder("downloads").toPath();session.configureDownloads(d.toString());try{session.downloadStatus("g",tmp.getRoot().toPath().resolve("outside.bin"),1000,x->true);fail();}catch(IOException e){assertTrue(e.getMessage().contains("不在本次下载目录"));}}
    @Test public void realHttpPlusBrowserProtocolAndInteractionProduceScopedEvidence()throws Exception{try(MockWebServer site=new MockWebServer()){site.enqueue(new MockResponse().setBody("READY"));site.start();JsonObject a=args("{action:'webpage',checks:[{selector:'#ok',expectedText:'READY'}],interaction:{selector:'#go',receiptSelector:'#done',receiptText:'saved'},observeMs:100}");a.addProperty("url",site.url("/app").newBuilder().host("127.0.0.1").build().toString());ToolResult r=new ArtifactVerificationTool(new Workspace(tmp.getRoot().toString()),()->session).execute(a);assertTrue(r.output,r.ok);JsonObject f=JsonParser.parseString(r.output).getAsJsonObject().getAsJsonObject("facts");assertTrue(f.get("browserHttpResponseObserved").getAsBoolean());assertTrue(f.get("interactionVerified").getAsBoolean());assertEquals(1,clicks.get());assertEquals(1,site.getRequestCount());}}
    @Test public void browserConsoleFailureFailsEvenIfJavaHttpAndVisibleAnchorPass()throws Exception{consoleFailure=true;try(MockWebServer site=new MockWebServer()){site.enqueue(new MockResponse().setBody("READY"));site.start();JsonObject a=args("{action:'webpage',checks:[{selector:'#ok'}],observeMs:100}");a.addProperty("url",site.url("/app").newBuilder().host("127.0.0.1").build().toString());ToolResult r=new ArtifactVerificationTool(new Workspace(tmp.getRoot().toString()),()->session).execute(a);assertFalse(r.ok);assertTrue(r.output.contains("script failed"));}}
}
