package com.minion.core.tools.browser;

import com.google.gson.*;
import com.minion.core.tools.*;
import com.minion.core.tools.plugin.BrowserManager;
import org.junit.*;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

/** 固定探针与状态机测试，不冒充真实Chrome DOM测试。 */
public class BrowserReliabilityGTest {
    private static JsonObject args(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static class SimulatedSession extends BrowserSession {
        boolean auth, failAction, noReceipt, clicked; int actions,probes,hiddenProbes; String version="v1",lastExpression;
        SimulatedSession(){super(null,new CdpClient(100,100));}
        @Override synchronized String inspect(String expression)throws IOException{
            lastExpression=expression;
            if(expression.contains("e.click();")||expression.contains("pd.set")){
                actions++;if(failAction)throw new IOException("CDP命令超时，服务端可能已执行");clicked=true;return "{executed:true}";
            }
            probes++;boolean done=expression.contains("d.querySelectorAll(\"#done\")");boolean visible=done?clicked&&!noReceipt:probes>hiddenProbes;
            JsonObject o=new JsonObject();o.addProperty("pageVersion",version);o.addProperty("visible",visible);o.addProperty("interactive",visible);o.addProperty("disabled",false);o.addProperty("matches",visible?1:0);o.addProperty("authRequired",auth);o.addProperty("text",done?"saved":"anchor");o.addProperty("url","http://127.0.0.1/site");return o.toString();
        }
    }
    private JsonObject click(){return args("{action:'click',selector:'#submit',expectedVersion:'v1',receiptSelector:'#done',receiptText:'saved',timeoutMs:100}");}
    @Test public void waitRequeriesUntilVisibleAndInteractive(){SimulatedSession s=new SimulatedSession();s.hiddenProbes=2;ToolResult r=new BrowserActionTool(s).execute(args("{action:'wait_selector',selector:'#submit',timeoutMs:1000}"));assertTrue(r.output,r.ok);assertTrue(s.probes>=3);}
    @Test public void staleVersionRejectsBeforeAnyClick(){SimulatedSession s=new SimulatedSession();s.version="v2";ToolResult r=new BrowserActionTool(s).execute(click());assertFalse(r.ok);assertTrue(r.output.contains("stale_page"));assertEquals(0,s.actions);}
    @Test public void clickRequiresVersionAndReceipt(){SimulatedSession s=new SimulatedSession();JsonObject a=click();a.remove("expectedVersion");assertFalse(new BrowserActionTool(s).execute(a).ok);a=click();a.remove("receiptSelector");assertFalse(new BrowserActionTool(s).execute(a).ok);assertEquals(0,s.actions);}
    @Test public void completedReceiptPreventsResubmission(){SimulatedSession s=new SimulatedSession();s.clicked=true;ToolResult r=new BrowserActionTool(s).execute(click());assertTrue(r.output,r.ok);assertTrue(r.output.contains("already_satisfied"));assertEquals(0,s.actions);}
    @Test public void actionIsExecutedOnceAndRecordsBeforeAfterReceipt(){SimulatedSession s=new SimulatedSession();ToolResult r=new BrowserActionTool(s).execute(click());assertTrue(r.output,r.ok);assertEquals(1,s.actions);assertTrue(r.output.contains("before"));assertTrue(r.output.contains("after"));assertEquals("ready",s.automationState().get("status").getAsString());}
    @Test public void ambiguousCdpTimeoutLatchesAndBlocksAllAutomaticRoutes()throws Exception{
        SimulatedSession s=new SimulatedSession();s.failAction=true;BrowserActionTool tool=new BrowserActionTool(s);assertFalse(tool.execute(click()).ok);assertFalse(tool.execute(click()).ok);assertEquals(1,s.actions);
        assertEquals("waiting_user",s.automationState().get("status").getAsString());
        ToolResult eval=new BrowserEvalTool(BrowserManager.ofSession(s)).execute(args("{expression:'document.querySelector(\"#submit\").click()',awaitPage:false}"));assertFalse(eval.ok);assertEquals(1,s.actions);
        assertFalse(new BrowserTool(BrowserManager.ofSession(s)).execute(args("{action:'refresh'}")).ok);
    }
    @Test public void missingPostconditionDoesNotTriggerBlindRetry(){SimulatedSession s=new SimulatedSession();s.noReceipt=true;BrowserActionTool t=new BrowserActionTool(s);assertFalse(t.execute(click()).ok);assertFalse(t.execute(click()).ok);assertEquals(1,s.actions);}
    @Test public void authLossTriggersTakeoverBeforeClick(){SimulatedSession s=new SimulatedSession();s.auth=true;ToolResult r=new BrowserActionTool(s).execute(click());assertFalse(r.ok);assertEquals(0,s.actions);assertEquals("waiting_user",s.automationState().get("status").getAsString());}
    @Test public void manualTakeoverRequiresTokenHumanConfirmationAndAnchorToResume(){
        SimulatedSession s=new SimulatedSession();BrowserActionTool t=new BrowserActionTool(s);ToolResult pause=t.execute(args("{action:'manual_takeover',value:'登录失效'}"));String token=JsonParser.parseString(pause.output).getAsJsonObject().get("resumeToken").getAsString();
        JsonObject resume=args("{action:'resume',selector:'#loggedIn'}");resume.addProperty("resumeToken",token);assertFalse(t.execute(resume).ok);resume.addProperty("userConfirmed",true);s.auth=true;assertFalse(t.execute(resume).ok);s.auth=false;assertTrue(t.execute(resume).ok);assertFalse(t.execute(resume).ok);
    }
    @Test public void uncertainActionNeedsExplicitReconciliationNotJustResume(){
        SimulatedSession s=new SimulatedSession();s.failAction=true;BrowserActionTool t=new BrowserActionTool(s);t.execute(click());JsonObject a=args("{action:'resume',selector:'#loggedIn',userConfirmed:true}");a.addProperty("resumeToken",s.automationState().get("resumeToken").getAsString());assertFalse(t.execute(a).ok);a.addProperty("resolution","applied");assertTrue(t.execute(a).ok);assertEquals(1,s.actions);
    }
    @Test public void iframeIsExplicitAndNeverFallsBackSilently(){SimulatedSession s=new SimulatedSession();ToolResult r=new BrowserActionTool(s).execute(args("{action:'wait_selector',selector:'#button',frameSelector:'#reportFrame',timeoutMs:100}"));assertTrue(r.output,r.ok);assertTrue(s.lastExpression.contains("document.querySelectorAll(\"#reportFrame\")"));assertTrue(s.lastExpression.contains("跨域或未就绪frame，禁止回退顶层"));}
    private JsonObject page(String marker,String... keys){JsonObject o=new JsonObject();o.addProperty("marker",marker);o.addProperty("url","http://127.0.0.1/p"+marker);JsonArray rows=new JsonArray();for(String key:keys){JsonObject r=new JsonObject();r.addProperty("key",key);r.addProperty("text",key);rows.add(r);}o.add("rows",rows);return o;}
    @Test public void repeatedDataPageWithNewPageMarkerIsNotNewCoverage()throws Exception{PaginationEvidence e=new PaginationEvidence();assertTrue(e.add(page("1","a","b")));assertFalse(e.add(page("2","a","b")));JsonObject r=e.report(true,2,4,1);assertEquals(1,r.get("observedPages").getAsInt());assertEquals("failed",r.get("status").getAsString());}
    @Test public void overlappingKeysAreDeduplicatedAndReportedAsFailure()throws Exception{PaginationEvidence e=new PaginationEvidence();e.add(page("1","a","b"));assertFalse(e.add(page("2","b","c")));JsonObject r=e.report(true,2,4,1);assertEquals(3,r.get("uniqueRecords").getAsInt());assertEquals(1,r.getAsJsonArray("duplicateKeys").size());assertEquals("failed",r.get("status").getAsString());}
    @Test public void skippedPageFailsNumericCoverage()throws Exception{PaginationEvidence e=new PaginationEvidence();e.add(page("1","a"));e.add(page("3","b"));JsonObject r=e.report(true,2,2,1);assertFalse(r.get("numericCoverage").getAsBoolean());assertEquals("failed",r.get("status").getAsString());}
    @Test public void noExpectedScopeIsPartialEvenWhenNextDisappears()throws Exception{PaginationEvidence e=new PaginationEvidence();e.add(page("1","a"));assertEquals("partial",e.report(true,-1,-1,-1).get("status").getAsString());assertEquals("passed",e.report(true,1,1,1).get("status").getAsString());}
    @Test public void paginationValidatesUniqueKeyAndOutputBeforeClick()throws Exception{SimulatedSession s=new SimulatedSession();assertFalse(new BrowserActionTool(s).execute(args("{action:'paginate',selector:'#table',nextSelector:'#next'}")).ok);assertEquals(0,s.actions);}
    private static class PagedSession extends BrowserSession {
        int page=1,clicks;boolean unchanged;
        PagedSession(){super(null,new CdpClient(100,100));}
        @Override synchronized String inspect(String expression){
            if(expression.contains("var cs=d.querySelectorAll"))return "{url:'http://127.0.0.1/p"+page+"',marker:'"+page+"',rows:[{key:'K"+page+"',text:'record"+page+"'}]}";
            if(expression.contains("e.click();")){clicks++;if(!unchanged)page++;return "{executed:true}";}
            boolean next=expression.contains("d.querySelectorAll(\"#next\")");JsonObject o=args("{visible:true,interactive:true,authRequired:false,matches:1,text:'data',disabled:false}");o.addProperty("pageVersion","v"+page);if(next&&page>=2)o.addProperty("disabled",true);return o.toString();
        }
    }
    private JsonObject paginate(){return args("{action:'paginate',selector:'#table',rowSelector:'tr',keyAttribute:'data-id',pageSelector:'#page',nextSelector:'#next',pages:5,expectedPages:2,expectedRecords:2,firstPage:1,timeoutMs:500}");}
    @Test public void semanticPaginationWaitsForChangedStableDataAndProducesCoverage(){PagedSession s=new PagedSession();ToolResult r=new BrowserActionTool(s).execute(paginate());assertTrue(r.output,r.ok);JsonObject out=JsonParser.parseString(r.output).getAsJsonObject();assertEquals(2,out.get("observedPages").getAsInt());assertEquals(2,out.get("uniqueRecords").getAsInt());assertEquals(1,s.clicks);}
    @Test public void unchangedNextPageIsNotClickedTwiceOrCountedTwice(){PagedSession s=new PagedSession();s.unchanged=true;ToolResult r=new BrowserActionTool(s).execute(paginate());assertFalse(r.ok);JsonObject out=JsonParser.parseString(r.output).getAsJsonObject();assertEquals(1,out.get("observedPages").getAsInt());assertEquals(1,s.clicks);assertEquals("waiting_user",s.automationState().get("status").getAsString());}
    @Test public void paginationNeverOverwritesExistingEvidence()throws Exception{java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("g-pages"),p=dir.resolve("evidence.json");try{java.nio.file.Files.write(p,new byte[]{42});PagedSession s=new PagedSession();JsonObject a=paginate();a.addProperty("outputPath","evidence.json");assertFalse(new BrowserActionTool(s,new Workspace(dir.toString())).execute(a).ok);assertEquals(0,s.clicks);assertArrayEquals(new byte[]{42},java.nio.file.Files.readAllBytes(p));}finally{java.nio.file.Files.deleteIfExists(p);java.nio.file.Files.deleteIfExists(dir);}}
}
