package com.minion.core.agent;
import com.google.gson.JsonObject;
import com.minion.core.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class VerificationExecutionPolicyTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private JsonObject args(String action){JsonObject o=new JsonObject();o.addProperty("action",action);return o;}
    private Tool tool(String name,java.util.concurrent.Callable<ToolResult> body){return new Tool(){public String name(){return name;}public String description(){return "test";}public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject args)throws Exception{return body.call();}};}
    @Test public void readonlyReportVerificationFailureDoesNotCreateUncertainMutation()throws Exception {
        Workspace ws=new Workspace(tmp.getRoot().getPath());ReliabilityService reliability=new ReliabilityService(ws,"test");ToolExecutionGuard guard=new ToolExecutionGuard(ws,false);guard.journal(reliability,()->"task","root");
        assertTrue(ToolExecutionGuard.readOnly("Build",args("verify")));assertTrue(ToolExecutionGuard.readOnly("Build",args("detect")));assertFalse(ToolExecutionGuard.readOnly("Build",args("test")));
        assertFalse(guard.execute(tool("Build",()->ToolResult.error("报告已经过期")),args("verify")).ok);
        assertEquals(0,reliability.unresolved("task").size());
    }
    @Test public void networkVerificationDoesNotHoldWorkspaceFileWriteLock()throws Exception {
        Workspace ws=new Workspace(tmp.getRoot().getPath());CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<ToolResult> network=pool.submit(()->new ToolExecutionGuard(ws,false).execute(tool("ArtifactVerification",()->{entered.countDown();release.await(10,TimeUnit.SECONDS);return ToolResult.success("HTTP检查完成");}),args("http")));
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));Future<ToolResult> read=pool.submit(()->new ToolExecutionGuard(ws,false).execute(tool("Read",()->ToolResult.success("读到项目文件")),new JsonObject()));assertTrue(read.get(2,TimeUnit.SECONDS).ok);Future<ToolResult> otherHttp=pool.submit(()->new ToolExecutionGuard(ws,false).execute(tool("ArtifactVerification",()->ToolResult.success("另一个HTTP检查完成")),args("http")));assertTrue(otherHttp.get(2,TimeUnit.SECONDS).ok);}
        finally{release.countDown();network.get(5,TimeUnit.SECONDS);pool.shutdownNow();}
    }
}
