package com.minion.core.context;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public class ProjectMemoryExplanationAuditTest {
 @Test public void explanationDoesNotReverifyChangedSource() throws Exception {
  Path root=Files.createTempDirectory("memory-explain");Files.write(root.resolve("proof.txt"),"old".getBytes(StandardCharsets.UTF_8));
  ProjectMemoryStore store=new ProjectMemoryStore(root);ProjectMemoryStore.Entry e=store.remember("project","fact","proof.txt");store.setVerified(e.id,true);
  byte[] before=Files.readAllBytes(Paths.get(store.read(e.id).location));Files.write(root.resolve("proof.txt"),"changed".getBytes(StandardCharsets.UTF_8));
  assertTrue(store.explain(e.id,new ProjectMemoryStore.Scope()).contains("来源变更"));assertArrayEquals(before,Files.readAllBytes(Paths.get(store.read(e.id).location)));assertFalse(store.read(e.id).verified);
  try{new ProjectMemoryStore(Files.createTempDirectory("other-project")).explain(e.id,null);fail();}catch(java.io.IOException expected){}
 }
 @Test public void editsAndEnableCannotRefreshVerification() throws Exception {
  ProjectMemoryStore store=new ProjectMemoryStore(Files.createTempDirectory("memory-audit"));ProjectMemoryStore.Entry e=store.remember("user","use concise output","user request");store.setVerified(e.id,true);
  store.edit(e.id,"use detailed output","user correction");assertTrue(store.explain(e.id,null).contains("pending"));store.setDisabled(e.id,true);store.setDisabled(e.id,false);assertFalse(store.read(e.id).verified);
 }
 @Test public void scopeAndConflictReasonsAreIndependentOfReading() throws Exception {
  ProjectMemoryStore store=new ProjectMemoryStore(Files.createTempDirectory("memory-scoped"));
  ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.conditions=new ProjectMemoryStore.Conditions();d.conditions.taskId="task";d.conditions.revision=2L;
  ProjectMemoryStore.Entry e=store.remember("decision","scoped fact","user confirmation",d);store.setVerified(e.id,true);
  ProjectMemoryStore.Scope scope=new ProjectMemoryStore.Scope();scope.taskId="task";scope.revision=1;
  assertTrue(store.explain(e.id,scope).contains("not_applicable"));scope.revision=2;assertTrue(store.explain(e.id,scope).contains("状态: verified"));
  ProjectMemoryStore.Entry other=store.remember("decision","conflicting fact","user correction");
  ProjectMemoryStore.Details relation=new ProjectMemoryStore.Details();relation.conflicts=java.util.Collections.singletonList(e.id);store.setDetails(other.id,relation);store.setVerified(other.id,true);
  assertTrue(store.explain(e.id,scope).contains("状态: conflict"));assertTrue(store.explain(other.id,scope).contains("状态: conflict"));
 }
}
