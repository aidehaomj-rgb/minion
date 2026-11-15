package com.minion.gui.input;

import com.minion.core.agent.Session;
import com.minion.core.config.*;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.session.*;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.TextArea;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real FX queue/undo tests; fixture handles have no model client or executable loop. */
public class InputViewDraftFxTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    SessionManager manager;InputView view;SessionHandle a,b;TextArea input;
    @BeforeClass public static void toolkit()throws Exception {javax.swing.SwingUtilities.invokeAndWait(()->new JFXPanel());}
    @Before public void setup()throws Exception {
        Path root=temp.getRoot().toPath();Files.write(root.resolve("workspace.json"),"{\"workspaces\":[]}".getBytes(StandardCharsets.UTF_8));
        Config config=Config.load(root);
        manager=new SessionManager(request->ConfirmUi.Decision.REJECT,config,root,WorkspaceManager.load(root),ModelManager.load(root),Collections.emptyList(),null,null){
            @Override public String currentWorkspaceDir(){return null;}
        };
        a=handle("项目甲");b=handle("项目乙");view=fx(()->new InputView(manager,config));input=(TextArea)field("input");
        fx(()->{view.bindSession(a);return null;});flush();
    }
    SessionHandle handle(String project){Session s=Session.create(".","no-model");s.id="duplicate-id";return new SessionHandle(s.id,project,s,null,new SessionController(),project,false,null);}
    @After public void close()throws Exception {
        if(view!=null){fx(()->{view.bindSession(null);return null;});((ExecutorService)field("filePool")).shutdownNow();}
        if(a!=null)a.pool.shutdownNow();if(b!=null)b.pool.shutdownNow();if(manager!=null)manager.shutdown();
    }
    <T>T fx(Callable<T> c)throws Exception {FutureTask<T> f=new FutureTask<T>(c);Platform.runLater(f);return f.get(10,TimeUnit.SECONDS);}
    void flush()throws Exception {fx(()->null);fx(()->null);}
    Object field(String name)throws Exception {Field f=InputView.class.getDeclaredField(name);f.setAccessible(true);return f.get(view);}
    Object call(String name,Class<?>[] types,Object... args)throws Exception {Method m=InputView.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(view,args);}
    void chip(InputChip c)throws Exception {call("addChip",new Class<?>[]{InputChip.class},c);}
    List<?> chips()throws Exception {return (List<?>)field("chips");}
    @Test public void mixedClipboardPastesTextAtSelectionWithoutReadingImageOrFiles()throws Exception {
        fx(()->{
            input.setText("前缀旧值后缀");input.selectRange(2,4);
            view.pasteFrom("甲\t乙\n丙",()->{throw new AssertionError("files requested for text");},
                    ()->{throw new AssertionError("bitmap decoded for text");});
            assertEquals("前缀甲\t乙\n丙后缀",input.getText());
            assertTrue(chips().isEmpty());
            return null;
        });
    }
    @Test public void longMixedClipboardUsesTextBlockAndComposesFullOriginal()throws Exception {
        String original=String.join("",Collections.nCopies(300,"甲\t乙\r\n"));
        fx(()->{
            input.setText("前后");input.positionCaret(1);
            view.pasteFrom(original,()->{throw new AssertionError("files requested for text");},
                    ()->{throw new AssertionError("bitmap decoded for text");});
            assertEquals("前[粘贴块1]后",input.getText());
            assertEquals(InputChip.Type.PASTE,((InputChip)chips().get(0)).type);
            assertEquals("前"+original+"后",call("composedText",new Class<?>[0]));
            javafx.scene.layout.FlowPane row=(javafx.scene.layout.FlowPane)field("chipRow");
            javafx.scene.layout.HBox block=(javafx.scene.layout.HBox)row.getChildren().get(0);
            assertTrue(((javafx.scene.control.Button)block.getChildren().get(0)).getText().contains("查看全文"));
            view.bindSession(b);
            assertTrue(chips().isEmpty());
            view.bindSession(a);
            assertEquals("前"+original+"后",call("composedText",new Class<?>[0]));
            return null;
        });
    }
    @Test public void imageOnlyAndFileOnlyClipboardStillAttach()throws Exception {
        Path file=temp.newFile("clipboard.txt").toPath();
        Files.write(file,"文件文本".getBytes(StandardCharsets.UTF_8));
        // 附件/剪贴板图片改为后台解析（attachmentPool 单线程 + runLater 回 FX 添加块）：
        // 每步粘贴后等待后台任务排空并冲刷 FX 队列，再断言块已出现（语义不变，仅同步→异步）
        fx(()->{view.pasteFrom(null,Collections::emptyList,()->new javafx.scene.image.WritableImage(2,2));return null;});
        awaitAttachments();
        fx(()->{assertEquals(InputChip.Type.IMAGE,((InputChip)chips().get(0)).type);return null;});
        fx(()->{view.pasteFrom("",()->Collections.singletonList(file.toFile()),()->{throw new AssertionError("bitmap requested for files");});return null;});
        awaitAttachments();
        fx(()->{
            assertEquals(InputChip.Type.FILE,((InputChip)chips().get(1)).type);
            assertEquals("",input.getText());
            return null;
        });
    }
    /** 等待附件后台线程排空（单线程 FIFO：空任务完成=此前任务已跑完其 runLater 提交）并冲刷 FX 队列 */
    void awaitAttachments()throws Exception {
        ((ExecutorService)field("attachmentPool")).submit(()->{}).get(10,TimeUnit.SECONDS);
        flush();
    }
    @Test public void keyboardAndNativePasteActionUseSameTextFirstPath()throws Exception {
        fx(()->{
            InputView composer=new InputView(manager,Config.load(temp.getRoot().toPath())){
                @Override void pasteFromSystemClipboard(){
                    pasteFrom("文字",Collections::emptyList,()->{throw new AssertionError("unexpected image");});
                }
            };
            try{
                Field f=InputView.class.getDeclaredField("input");f.setAccessible(true);
                TextArea box=(TextArea)f.get(composer);
                box.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",
                        javafx.scene.input.KeyCode.V,false,true,false,false));
                box.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",
                        javafx.scene.input.KeyCode.INSERT,true,false,false,false));
                box.paste(); // JavaFX's native paste action calls this method.
                assertEquals("文字文字文字",box.getText());
            }finally{
                Field pool=InputView.class.getDeclaredField("filePool");pool.setAccessible(true);
                ((ExecutorService)pool.get(composer)).shutdownNow();
            }
            return null;
        });
    }
    @Test public void guidanceReceiptsDoNotAddAComposerSummaryOrButton()throws Exception {
        com.minion.core.agent.SteeringReceipt receipt=new com.minion.core.agent.SteeringReceipt();
        receipt.id="guide";receipt.text="不应显示在输入框的引导原文";
        a.session.steeringReceipts=Collections.singletonList(receipt);
        fx(()->{
            view.bindSession(a);
            new javafx.scene.Scene(view,640,320);view.applyCss();view.layout();
            for(javafx.scene.Node n:view.lookupAll(".button"))if(n instanceof javafx.scene.control.Button)
                assertNotEquals("查看引导",((javafx.scene.control.Button)n).getText());
            for(javafx.scene.Node n:view.lookupAll(".label"))if(n instanceof javafx.scene.control.Label)
                assertFalse(((javafx.scene.control.Label)n).getText().contains(receipt.text));
            return null;
        });
    }
    @Test public void focusInputActivatesNativeEditorAndKeepsCaretValid()throws Exception {
        fx(()->{
            input.setText("输入内容");
            input.positionCaret(2);
            javafx.scene.control.Button other=new javafx.scene.control.Button("其他控件");
            javafx.scene.layout.VBox root=new javafx.scene.layout.VBox(other,view);
            new javafx.scene.Scene(root,640,320);
            root.applyCss();root.layout();
            assertTrue(input.isFocusTraversable());
            view.focusInput();
            assertEquals(2,input.getCaretPosition());
            return null;
        });
    }
    @Test public void switchingIsAtomicOnFxAndRestoresTextSelectionAndDifferentDrafts()throws Exception {
        fx(()->{input.setText("甲的未发送要求");input.selectRange(1,5);view.bindSession(b);
            assertEquals("",input.getText());assertSame(b,field("current"));input.setText("乙的要求");view.bindSession(a);
            assertEquals("甲的未发送要求",input.getText());assertEquals(1,input.getAnchor());assertEquals(5,input.getCaretPosition());
            view.bindSession(b);assertEquals("乙的要求",input.getText());return null;});
    }
    @Test public void attachmentsImagesPastesAndCountersTravelOnlyWithTheirConversation()throws Exception {
        fx(()->{
            chip(InputChip.attachmentChip("独立文件内容","a.txt"));chip(InputChip.imageChip("image/png","iVBORw0KGgo=","a.png"));
            chip(InputChip.pasteChip("甲的长文本","[粘贴块7]"));input.setText("核对 [粘贴块7]");
            Field seq=InputView.class.getDeclaredField("pasteSeq");seq.setAccessible(true);seq.set(view,7);
            ((Map<String,InputChip>)field("droppedPastes")).put("[粘贴块6]",InputChip.pasteChip("只属于甲的撤销内容","[粘贴块6]"));
            view.bindSession(b);assertTrue(chips().isEmpty());assertEquals(0,field("pasteSeq"));assertTrue(((Map<?,?>)field("droppedPastes")).isEmpty());
            chip(InputChip.attachmentChip("乙文件","b.txt"));view.bindSession(a);
            assertEquals(3,chips().size());assertEquals(7,field("pasteSeq"));assertTrue(((Map<?,?>)field("droppedPastes")).containsKey("[粘贴块6]"));
            String text=(String)call("composedText",new Class<?>[0]);assertTrue(text.contains("甲的长文本"));assertFalse(text.contains("乙文件"));
            view.bindSession(b);assertEquals(1,chips().size());assertEquals("b.txt",((InputChip)chips().get(0)).display.substring(3));return null;
        });
    }
    @Test public void undoAndRedoCannotRevealAnotherConversationsText()throws Exception {
        fx(()->{input.setText("甲的私密草稿");input.appendText("尾部");view.bindSession(b);
            input.undo();input.redo();assertEquals("",input.getText());input.appendText("乙自己的草稿");view.bindSession(a);
            assertEquals("甲的私密草稿尾部",input.getText());input.undo();input.redo();assertEquals("甲的私密草稿尾部",input.getText());
            view.bindSession(b);input.undo();assertEquals("乙自己的草稿",input.getText());return null;});
    }
    @Test public void lateCompletionCannotEnterNewChatOrSameChatAfterRoundTrip()throws Exception {
        fx(()->{input.setText("/he");CompletionParser.Token t=CompletionParser.parse("/he",3);
            call("confirmApply",new Class<?>[]{CompletionParser.Token.class,String.class},t,"/help");view.bindSession(b);input.setText("乙的草稿");return null;});flush();
        fx(()->{assertTrue(chips().isEmpty());assertEquals("乙的草稿",input.getText());view.bindSession(a);
            call("confirmApply",new Class<?>[]{CompletionParser.Token.class,String.class},CompletionParser.parse("/he",3),"/help");
            view.bindSession(b);view.bindSession(a);return null;});flush();
        fx(()->{assertEquals("/he",input.getText());assertTrue(chips().isEmpty());return null;});
    }
    @Test public void queuedOffThreadBindingUsesDisplayedOwnerNotRequestedOwner()throws Exception {
        fx(()->{input.setText("甲");Thread t=new Thread(()->{view.bindSession(b);view.bindSession(a);});t.start();t.join();
            assertSame(a,field("current"));input.appendText("仍属于甲");return null;});flush();
        fx(()->{assertEquals("甲仍属于甲",input.getText());view.bindSession(b);assertEquals("",input.getText());view.bindSession(a);
            assertEquals("甲仍属于甲",input.getText());return null;});
    }
    @Test public void clearingOneDraftDoesNotClearOthersOrResurrectSentText()throws Exception {
        fx(()->{input.setText("甲");view.bindSession(b);input.setText("乙");view.bindSession(a);
            call("clearComposer",new Class<?>[0]);view.bindSession(b);assertEquals("乙",input.getText());view.bindSession(a);
            assertEquals("",input.getText());input.undo();assertEquals("",input.getText());return null;});
    }
    @Test public void sameHandleRebindPreservesComposerAndDeletedHandleDoesNotRecoverDraft()throws Exception {
        fx(()->{input.setText("甲");input.appendText("未发送");view.bindSession(a);assertEquals("甲未发送",input.getText());assertTrue(input.isUndoable());
            a.deleted=true;view.bindSession(b);view.bindSession(a);assertEquals("",input.getText());return null;});
    }
    @Test public void nullBindingDoesNotExportDraftIntoNewConversations()throws Exception {
        fx(()->{input.setText("甲未发");view.bindSession(null);assertEquals("",input.getText());input.setText("未选会话临时文字");
            view.bindSession(b);assertEquals("",input.getText());view.bindSession(a);assertEquals("甲未发",input.getText());return null;});
    }
    @Test public void staleContextAndCompressionCallbacksAreBoundToConversationGeneration()throws Exception {
        fx(()->{view.onContextStats(a,123,1000);view.setCompressing(a,true);view.bindSession(b);return null;});flush();
        fx(()->{assertFalse(((ContextRing)field("contextRing")).isVisible());assertSame(b,field("current"));return null;});
    }
}
