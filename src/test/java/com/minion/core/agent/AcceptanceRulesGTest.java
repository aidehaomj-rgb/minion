package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.*;
import com.minion.core.tools.dev.ArtifactVerificationGTest;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.Assert.*;

public class AcceptanceRulesGTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private JsonArray rules(String s){JsonArray a=JsonParser.parseString(s).getAsJsonArray();AcceptanceRules.validate(a);return a;}
    private JsonObject evaluate(JsonArray a){return AcceptanceRules.evaluate(a,new Workspace(tmp.getRoot().toString()),new TaskState(),p->PathsGuard.inside(tmp.getRoot().toString(),p));}
    @Test public void decimalSummaryUsesStoredValuesNotFloatingPointAndNotFormulaClaim()throws Exception{Path p=tmp.newFile("a.csv").toPath();Files.write(p,"id,amount\nA,0.1\nB,0.2\n".getBytes(StandardCharsets.UTF_8));JsonObject r=evaluate(rules("[{type:'table_rows',path:'a.csv',count:2},{type:'table_columns',path:'a.csv',columns:['id','amount']},{type:'unique_column',path:'a.csv',column:'id'},{type:'table_sum',path:'a.csv',column:'amount',sum:'0.3'}]"));assertEquals("passed",r.get("status").getAsString());JsonObject sum=r.getAsJsonArray("results").get(3).getAsJsonObject();assertEquals("0.3",sum.get("actualSum").getAsString());assertFalse(sum.get("formulaCalculationVerified").getAsBoolean());}
    @Test public void incorrectSummaryFails()throws Exception{Files.write(tmp.newFile("a.csv").toPath(),"id,amount\nA,10\nB,20\n".getBytes(StandardCharsets.UTF_8));assertEquals("failed",evaluate(rules("[{type:'table_sum',path:'a.csv',column:'amount',sum:'99'}]")).get("status").getAsString());}
    @Test public void missingOrNonNumericSummaryCellIsUnknownNotPassed()throws Exception{Files.write(tmp.newFile("a.csv").toPath(),"id,amount\nA,not-a-number\n".getBytes(StandardCharsets.UTF_8));assertEquals("unknown",evaluate(rules("[{type:'table_sum',path:'a.csv',column:'amount',sum:'0'}]")).get("status").getAsString());}
    @Test public void fileExistenceOnlyProvesExistence()throws Exception{tmp.newFile("a.txt");JsonObject result=evaluate(rules("[{type:'file_exists',path:'a.txt'}]")).getAsJsonArray("results").get(0).getAsJsonObject();assertEquals("passed",result.get("status").getAsString());assertFalse(result.get("runtimeVerified").getAsBoolean());assertTrue(result.get("claimBoundary").getAsString().contains("不是业务正确"));}
    @Test public void imageAndStableFileAreSeparateRules()throws Exception{ImageIO.write(new BufferedImage(12,7,BufferedImage.TYPE_INT_RGB),"png",tmp.newFile("a.png"));assertEquals("passed",evaluate(rules("[{type:'file_stable',path:'a.png',stableMs:100},{type:'image_dimensions',path:'a.png',width:12,height:7}]")).get("status").getAsString());assertEquals("failed",evaluate(rules("[{type:'image_dimensions',path:'a.png',width:13,height:7}]")).get("status").getAsString());}
    @Test public void peTargetRuleExplicitlyDoesNotProveRuntime()throws Exception{ArtifactVerificationGTest.pe(tmp.newFile("a.exe").toPath());JsonObject result=evaluate(rules("[{type:'executable_target',path:'a.exe',platform:'windows',architecture:'x64'}]")).getAsJsonArray("results").get(0).getAsJsonObject();assertEquals("passed",result.get("status").getAsString());assertFalse(result.get("runtimeVerified").getAsBoolean());assertEquals("target_header_only_not_executable_runtime",result.get("scope").getAsString());}
    @Test public void deniedPathIsNotRead()throws Exception{tmp.newFile("a.txt");JsonObject r=AcceptanceRules.evaluate(rules("[{type:'file_stable',path:'a.txt'}]"),new Workspace(tmp.getRoot().toString()),new TaskState(),p->false);assertEquals("unknown",r.get("status").getAsString());}
    @Test public void ruleValidationRejectsNonPositiveDimensionsAndInvalidTargets(){for(String rule:new String[]{"{type:'image_dimensions',path:'a',width:0,height:1}","{type:'image_dimensions',path:'a',width:1.5,height:1}","{type:'executable_target',path:'a',platform:'windows',architecture:'unknown'}","{type:'table_sum',path:'a',column:'x',sum:'1',tolerance:'-1'}"})try{rules("["+rule+"]");fail(rule);}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
}
