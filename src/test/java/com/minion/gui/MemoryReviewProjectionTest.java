package com.minion.gui;
import org.junit.Test;
import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import com.minion.core.agent.TaskState;
public class MemoryReviewProjectionTest {
 @Test public void previewRequiresFullHashAndConfiguration(){
  JsonObject o=new JsonObject();o.addProperty("path","project/.minion/hooks.json");o.addProperty("config","{commands}");o.addProperty("hash",new String(new char[64]).replace('\0','a'));
  assertEquals(64,HooksPreviewHash.parse(o.toString()).length());o.remove("config");
  try{HooksPreviewHash.parse(o.toString());fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void oldRevisionDoesNotBecomeReviewed(){
  TaskState s=new TaskState();s.begin("task",true);TaskState.Child c=new TaskState.Child();c.id="child";c.taskId=s.id;c.revision=s.revision()+1;c.status="returned";s.children.add(c);
  String report=AgentReviewReport.render(s,"workspace");assertTrue(report.contains("不得复用验收"));assertTrue(report.contains("attempt=unknown"));assertEquals("returned",c.status);assertTrue(s.evidence.isEmpty());
 }
 @Test public void largeReviewIsBoundedAndRetainsPathsBeforeBodies(){
  TaskState s=new TaskState();s.begin("task",true);
  String huge=repeat("巨量😀",20000);
  for(int n=0;n<70;n++){
   TaskState.Child c=new TaskState.Child();c.id="child-"+n;c.taskId=s.id;c.revision=s.revision();c.result=huge;c.status="returned";c.transcript="/raw/transcript-"+n+".json";s.children.add(c);
   for(int k=0;k<20;k++){TaskState.Evidence e=new TaskState.Evidence();e.id="receipt-"+n+"-"+k;e.taskId=s.id;e.scope=c.id;e.result=huge;e.file=new com.minion.core.agent.FileEvidence();e.file.path="/raw/evidence-"+n+"-"+k;e.revision=s.revision();s.evidence.add(e);}
  }
  String report=AgentReviewReport.render(s,"workspace");
  assertTrue(report.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=AgentReviewReport.MAX_BYTES);
  assertTrue(report.contains("子任务总数=70；展示最近登记=64；省略=6"));
  assertTrue(report.contains("展示最近=16；省略=4"));assertTrue(report.contains("/raw/transcript-69.json"));
  assertTrue(report.contains("/raw/evidence-69-19"));assertFalse(report.contains("/raw/evidence-69-3\n"));
  assertTrue(report.contains("[已截断"));assertTrue(report.contains("绝不构成完成证明"));
  assertEquals(70,s.children.size());assertEquals(1400,s.evidence.size());assertEquals("returned",s.children.get(69).status);
 }
 @Test public void oversizedPathsStillRespectUtf8BudgetAndDiscloseOmission(){
  TaskState s=new TaskState();s.begin("task",true);
  for(int n=0;n<64;n++){TaskState.Child c=new TaskState.Child();c.id="child-"+n;c.taskId=s.id;c.transcript=repeat("路径😀",10000);s.children.add(c);}
  String report=AgentReviewReport.render(s,repeat("根",20000));
  assertTrue(report.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=AgentReviewReport.MAX_BYTES);
  assertTrue(report.contains("部分路径也可能未展示"));assertTrue(report.endsWith("本页不核验、不更新验收。"));
  for(int i=0;i<report.length();i++)if(Character.isHighSurrogate(report.charAt(i))){assertTrue(i+1<report.length()&&Character.isLowSurrogate(report.charAt(++i)));}
 }
 private static String repeat(String text,int count){StringBuilder b=new StringBuilder();for(int i=0;i<count;i++)b.append(text);return b.toString();}
}
