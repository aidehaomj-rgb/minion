package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.Assert.*;

public class GitReviewSnapshotFTest {
    private Path repo()throws Exception{Path p=Files.createTempDirectory("minion-f-review-");git(p,"init");return p;}
    private void git(Path p,String...args)throws Exception{GitCommand.Result r=GitCommand.run(p,Arrays.asList(args),10000,100000);assertEquals(r.text(),0,r.exitCode);}
    private void write(Path p,String text)throws Exception{Files.createDirectories(p.getParent());Files.write(p,text.getBytes(StandardCharsets.UTF_8));}
    private ToolResult snapshot(Path p,String paths)throws Exception{JsonObject a=new JsonObject();a.addProperty("action","review_snapshot");if(paths!=null)a.addProperty("paths",paths);GitTool tool=new GitTool(new Workspace(p.toString()));assertFalse(tool.isHighRisk(a));return tool.execute(a);}
    @Test public void includesThreeLayersWithoutIndexOrWorktreeMutation()throws Exception{
        Path p=repo();write(p.resolve("a.txt"),"staged\n");git(p,"add","--","a.txt");write(p.resolve("a.txt"),"unstaged\n");write(p.resolve("b.txt"),"untracked\n");
        byte[] index=Files.readAllBytes(p.resolve(".git/index"));ToolResult r=snapshot(p,null);assertTrue(r.output,r.ok);
        JsonObject j=JsonParser.parseString(r.output).getAsJsonObject();assertEquals("minion.git-review.v1",j.get("type").getAsString());assertFalse(j.get("truncated").getAsBoolean());String diff=j.get("diffText").getAsString();
        assertTrue(diff.contains("staged"));assertTrue(diff.contains("unstaged"));assertTrue(diff.contains("untracked"));assertEquals(2,j.getAsJsonArray("files").size());
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(diff.getBytes(StandardCharsets.UTF_8)))hash.append(String.format("%02x",b&255));assertEquals(hash.toString(),j.get("diffHash").getAsString());
        assertArrayEquals(index,Files.readAllBytes(p.resolve(".git/index")));assertEquals("unstaged\n",new String(Files.readAllBytes(p.resolve("a.txt")),StandardCharsets.UTF_8));
    }
    @Test public void filtersArtifactsAndMarksBinary()throws Exception{
        Path p=repo();write(p.resolve("src/ok.txt"),"source\n");write(p.resolve("target/skip.txt"),"artifact");write(p.resolve(".minion/skip.txt"),"private");write(p.resolve("node_modules/n/a.txt"),"module");Files.write(p.resolve("raw.bin"),new byte[]{0,1,2});
        ToolResult r=snapshot(p,null);assertTrue(r.output,r.ok);JsonObject j=JsonParser.parseString(r.output).getAsJsonObject();assertEquals(2,j.getAsJsonArray("files").size());assertTrue(j.get("diffText").getAsString().contains("binary"));assertFalse(j.get("diffText").getAsString().contains("artifact"));
        ToolResult filtered=snapshot(p,"src");assertTrue(filtered.output,filtered.ok);assertEquals(1,JsonParser.parseString(filtered.output).getAsJsonObject().getAsJsonArray("files").size());assertFalse(snapshot(p,"../escape").ok);
    }
    @Test public void largeFileRequiresNarrowingAndHashChangesWithContent()throws Exception{
        Path p=repo();write(p.resolve("small.txt"),"one");JsonObject first=JsonParser.parseString(snapshot(p,null).output).getAsJsonObject();write(p.resolve("small.txt"),"two");JsonObject second=JsonParser.parseString(snapshot(p,null).output).getAsJsonObject();assertNotEquals(first.get("diffHash"),second.get("diffHash"));
        Files.write(p.resolve("large.txt"),new byte[140000]);JsonObject large=JsonParser.parseString(snapshot(p,null).output).getAsJsonObject();assertTrue(large.get("truncated").getAsBoolean());assertTrue(large.get("guidance").getAsString().contains("收窄"));
    }
}
