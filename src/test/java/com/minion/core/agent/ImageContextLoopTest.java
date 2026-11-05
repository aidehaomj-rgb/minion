package com.minion.core.agent;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import com.minion.core.config.Config;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public class ImageContextLoopTest {
 @Test public void rootAndChildReceivePixelsAfterPairedToolResult() throws Exception {
  Path dir=Files.createTempDirectory("minion-image-loop-");
  ImageIO.write(new BufferedImage(32,16,BufferedImage.TYPE_INT_RGB),"png",dir.resolve("label.png").toFile());
  Workspace ws=new Workspace(dir.toString()); ToolRegistry registry=new ToolRegistry();
  registry.register(new ReadTool(ws));registry.register(new ViewImageTool(ws,null,null,null));
  ConfirmGate gate=new ConfirmGate(Config.load(dir),new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
  FakeLlmClient rootClient=client();
  AgentLoop root=new AgentLoop(rootClient,registry,new SystemPromptBuilder(null),gate,new AgentUi(){},null,ws,Session.create(ws.workDir(),"fake"));
  try { root.runUserTurn("读取铭牌图片");check(rootClient); } finally {root.shutdown();}
  FakeLlmClient childClient=client();
  SubAgentLoop child=new SubAgentLoop("父任务","读取铭牌图片",dir.toString(),childClient,registry,gate,new AgentUi(){});
  child.run();check(childClient);
  assertEquals(1,root.messages().stream().filter(m->m.toolImage).count());
 }
 private static FakeLlmClient client() {
  FakeLlmClient c=new FakeLlmClient();ToolCall read=new ToolCall();read.id="image";read.name="Read";read.arguments="{\"path\":\"label.png\"}";
  c.addTurnWithTools(Collections.singletonList(read),null);c.addTurn("已读取图片。");return c;
 }
 private static void check(FakeLlmClient c) {
  List<Message> messages=c.requests.get(1).messages;
  int image=-1;
  for(int i=0;i<messages.size();i++)if(messages.get(i).toolImage)image=i;
  assertTrue(image>0);assertEquals(Message.Role.TOOL,messages.get(image-1).role);
  assertEquals("image",messages.get(image-1).toolCallId);
  assertNotNull(messages.get(image).images);assertFalse(messages.get(image).images.isEmpty());
 }
}
