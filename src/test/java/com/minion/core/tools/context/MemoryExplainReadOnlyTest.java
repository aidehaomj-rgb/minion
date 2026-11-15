package com.minion.core.tools.context;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import com.google.gson.JsonObject;
import com.minion.core.context.ProjectMemoryStore;
import com.minion.core.tools.Workspace;
public class MemoryExplainReadOnlyTest {
 @Test public void explainIsReadOnlyAndProjectLocal() throws Exception {
  Path root=Files.createTempDirectory("tool-explain");ProjectMemoryStore store=new ProjectMemoryStore(root);
  ProjectMemoryStore.Entry entry=store.remember("project","private project fact","user request");
  JsonObject args=new JsonObject();args.addProperty("action","explain");args.addProperty("id",entry.id);
  MemoryTool tool=new MemoryTool(new Workspace(root.toString()),null);assertFalse(tool.isHighRisk(args));
  byte[] before=Files.readAllBytes(Paths.get(store.read(entry.id).location));assertTrue(tool.execute(args).output.contains("pending"));assertArrayEquals(before,Files.readAllBytes(Paths.get(store.read(entry.id).location)));
  MemoryTool foreign=new MemoryTool(new Workspace(Files.createTempDirectory("foreign-tool").toString()),null);
  try{foreign.execute(args);fail();}catch(java.io.IOException expected){}
 }
}
