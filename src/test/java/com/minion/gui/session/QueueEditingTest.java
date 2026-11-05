package com.minion.gui.session;

import com.minion.core.agent.Session;
import com.minion.core.llm.ImagePart;
import com.google.gson.Gson;
import com.minion.core.config.*;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class QueueEditingTest {
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 @Test public void editingUsesStableRevisionAndRetainsImageWithoutSending() {
  Session old=Session.create(".","model");old.queuedTasks=new ArrayList<Session.QueuedTask>();Session.QueuedTask legacy=new Session.QueuedTask();legacy.id=null;legacy.revision=0;legacy.text="旧文本";legacy.guidance=true;ImagePart image=new ImagePart();image.name="证据图.png";legacy.images.add(image);old.queuedTasks.add(legacy);
  Session loaded=new Gson().fromJson(new Gson().toJson(old),Session.class);
  SessionHandle h=new SessionHandle(loaded.id,"default",loaded,null,null,"test",false,null);
  try{SessionHandle.QueuedMessage snapshot=h.pendingGuide();assertNotNull(snapshot.id);assertEquals(1,snapshot.revision);assertTrue(h.editQueued(snapshot.id,1,"补充内容"));assertFalse(h.editQueued(snapshot.id,1,"过期覆盖"));
   assertEquals("补充内容",h.pendingGuide().text);assertEquals("证据图.png",h.pendingGuide().images.get(0).name);assertTrue(h.pendingGuide().guidance);
   assertFalse(h.removeQueued(snapshot.id,1));assertTrue(h.removeQueued(snapshot.id,2));assertNull(h.pendingGuide());
  }finally{h.pool.shutdownNow();}
 }
 @Test public void runningGuidanceEditPersistsAcrossRestartAndDoesNotDispatch()throws Exception {
  java.nio.file.Path jar=tmp.newFolder("queue-store").toPath();
  SessionManager m=new SessionManager(req->ConfirmUi.Decision.APPROVE,Config.load(jar),jar,WorkspaceManager.load(jar),ModelManager.load(jar),Collections.emptyList(),null,null);
  String id;try{SessionHandle h=m.createSession("队列编辑");id=h.id;h.running=true;ImagePart image=new ImagePart();image.name="photo.jpg";image.mime="image/jpeg";image.base64="AA==";
   m.stageGuidance(h,"原引导",Collections.singletonList(image));SessionHandle.QueuedMessage original=h.pendingGuide();assertNotNull(original);
   m.editQueuedTask(h,original,"补充后的引导");assertEquals(1,h.queuedSnapshot().size());assertEquals(0,h.session.messages.size());
   try{m.editQueuedTask(h,original,"陈旧覆盖");fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("已开始"));}
   h.running=false;
  }finally{m.shutdown();}
  SessionManager restored=new SessionManager(req->ConfirmUi.Decision.APPROVE,Config.load(jar),jar,WorkspaceManager.load(jar),ModelManager.load(jar),Collections.emptyList(),null,null);
  try{SessionHandle h=restored.findSession(id);assertNotNull(h);assertTrue(restored.ensureLoaded(h));SessionHandle.QueuedMessage current=h.pendingGuide();assertEquals("补充后的引导",current.text);assertEquals(2,current.revision);assertEquals("photo.jpg",current.images.get(0).name);}finally{restored.shutdown();}
 }
}
