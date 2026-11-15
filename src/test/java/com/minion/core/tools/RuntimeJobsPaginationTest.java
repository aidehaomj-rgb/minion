package com.minion.core.tools;
import com.google.gson.*;
import com.minion.core.agent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
public class RuntimeJobsPaginationTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void listAndInspectNeverInjectFullAuditBodies()throws Exception {
        Path root=temp.getRoot().toPath(),file=root.resolve("jobs.json");
        try(RuntimeJobRegistry registry=new RuntimeJobRegistry(file)){
            String large=String.join("",Collections.nCopies(12000,"x"))+"ORIGINAL_AUDIT_BODY_ONLY";
            RuntimeJobRegistry.Handle latest=null;
            for(int i=0;i<3;i++){latest=registry.register("task",1,1,"process",large,null,false);registry.publish(latest,RuntimeJobRegistry.Status.RUNNING,"external-"+i,null,null,"running");}
            byte[] before=Files.readAllBytes(file);RuntimeJobsTool tool=new RuntimeJobsTool(new RuntimeJobRecovery(registry,Collections.singletonList(root),root));
            JsonObject args=new JsonObject();args.addProperty("action","list");args.addProperty("limit",2);
            ToolResult result=tool.execute(args);assertTrue(result.output,result.ok);assertTrue(result.output.length()<8000);assertFalse(result.output.contains("ORIGINAL_AUDIT_BODY_ONLY"));
            JsonObject page=JsonParser.parseString(result.output).getAsJsonObject();assertEquals(3,page.get("total").getAsInt());assertEquals(2,page.get("nextOffset").getAsInt());assertEquals(2,page.getAsJsonArray("jobs").size());
            assertEquals(latest.jobId,page.getAsJsonArray("jobs").get(0).getAsJsonObject().get("jobId").getAsString());
            args.addProperty("offset",2);assertEquals(1,JsonParser.parseString(tool.execute(args).output).getAsJsonObject().getAsJsonArray("jobs").size());
            args=new JsonObject();args.addProperty("action","inspect");args.addProperty("jobId",latest.jobId);
            JsonObject inspect=JsonParser.parseString(tool.execute(args).output).getAsJsonObject().getAsJsonObject("job");
            assertFalse(inspect.has("history"));assertTrue(inspect.get("historyEntryCount").getAsInt()>0);assertArrayEquals(before,Files.readAllBytes(file));
        }
    }
    @Test public void invalidPaginationDoesNotOpenLedger(){
        java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();
        RuntimeJobsTool tool=new RuntimeJobsTool(()->{count.incrementAndGet();throw new java.io.IOException("must not be called");});
        for(String json:Arrays.asList("{\"action\":\"list\",\"limit\":51}","{\"action\":\"list\",\"offset\":-1}","{\"action\":\"list\",\"offset\":1.2}"))
            assertFalse(tool.execute(JsonParser.parseString(json).getAsJsonObject()).ok);
        assertEquals(0,count.get());
    }
}
