package com.minion.core.tools;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class FullReadMergeTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void fullKeepsLongLineAndRegularReadStaysBounded() throws Exception {
        String text=new String(new char[45000]).replace('\0','文');Path p=tmp.getRoot().toPath().resolve("long.md");
        Files.write(p,text.getBytes(StandardCharsets.UTF_8));ReadTool read=new ReadTool(new Workspace(tmp.getRoot().getPath()));
        JsonObject args=new JsonObject();args.addProperty("path","long.md");
        assertTrue(read.execute(args).output.length()<5000);
        args.addProperty("full",true);assertEquals(text+"\n",read.execute(args).output);
        assertEquals("boolean",read.schema().getAsJsonObject("properties").getAsJsonObject("full").get("type").getAsString());
    }
    @Test public void extremeLineHasActionableHintAndNoSelfOffsetLoop() throws Exception {
        Path p=tmp.getRoot().toPath().resolve("long.md");
        Files.write(p,new String(new char[120000]).replace('\0','x').getBytes(StandardCharsets.UTF_8));
        JsonObject args=new JsonObject();args.addProperty("path","long.md");args.addProperty("full",true);
        String out=new ReadTool(new Workspace(tmp.getRoot().getPath())).execute(args).output;
        assertTrue(out.length()<=ReadTool.FULL_MAX_OUTPUT_CHARS);assertTrue(out.contains("请用 Bash"));assertFalse(out.contains("offset=0"));
    }
    @Test public void parallelAppendPreservesEveryChunk() throws Exception {
        final WriteTool write=new WriteTool(new Workspace(tmp.getRoot().getPath()));ExecutorService pool=Executors.newFixedThreadPool(4);
        java.util.List<Future<ToolResult>> results=new java.util.ArrayList<Future<ToolResult>>();
        try {
            for(int i=0;i<40;i++){final int n=i;results.add(pool.submit(()->{JsonObject a=new JsonObject();a.addProperty("path","append.md");a.addProperty("mode","append");a.addProperty("content","片段-"+n+"\n");return write.execute(a);}));}
            for(Future<ToolResult> f:results)assertTrue(f.get(20,TimeUnit.SECONDS).ok);
        }finally{pool.shutdownNow();}
        java.util.List<String> lines=Files.readAllLines(tmp.getRoot().toPath().resolve("append.md"),StandardCharsets.UTF_8);
        assertEquals(40,lines.size());assertEquals(40,new java.util.HashSet<String>(lines).size());
    }
}
