package com.minion.gui.chat;

import com.minion.core.agent.SteeringReceipt;
import com.minion.gui.session.EventList;
import com.minion.gui.session.SessionController;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ChatViewSteeringReceiptTest {
    @BeforeClass public static void startFx() { new JFXPanel(); Platform.setImplicitExit(false); }

    private static void fx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<Void>(action, null);
        Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
    }

    private static SteeringReceipt receipt(String id, String text, String state, long updatedAt) {
        SteeringReceipt r = new SteeringReceipt();
        r.id = id; r.text = text; r.state = state; r.updatedAt = updatedAt;
        if ("read".equals(state)) r.readAt = updatedAt;
        return r;
    }

    private static List<HBox> rows(ChatView view, String tagPrefix) {
        List<HBox> found = new ArrayList<HBox>();
        for (int i = 1; i < view.getChildren().size(); i++) {
            Node node = view.getChildren().get(i);
            if (!(node instanceof HBox)) continue;
            HBox row = (HBox) node;
            if (!row.getChildren().isEmpty() && row.getChildren().get(0) instanceof Label
                    && ((Label) row.getChildren().get(0)).getText().startsWith(tagPrefix)) found.add(row);
        }
        return found;
    }

    private static Label tag(HBox row) { return (Label) row.getChildren().get(0); }
    private static MessageTextPane text(HBox row) { return (MessageTextPane) row.getChildren().get(1); }
    private static void press(ChatView view, String id) {
        for (Node node : ((HBox) view.getChildren().get(0)).getChildren()) {
            if (node instanceof Button && id.equals(node.getId()) && node.isVisible() && !node.isDisabled()) {
                ((Button) node).fire(); return;
            }
        }
        fail("找不到可用的历史按钮 " + id);
    }

    @Test public void onlyLongIntermediateRepliesFoldAndOriginalTextCanBeExpanded()throws Exception {
        fx(()->{
            String longText=String.join("",Collections.nCopies(200,"执行计划。"));
            SessionController c=new SessionController();
            c.onUserMessage("执行");
            c.onContent(longText);c.onResponseEnd();c.onToolCall("Read",new com.google.gson.JsonObject());
            c.onContent("简短进度");c.onResponseEnd();c.onToolCall("Grep",new com.google.gson.JsonObject());
            c.onContent(longText+"最终答复");c.onResponseEnd();
            c.onTaskCompletionTime("完成时间：2026-11-09 18:06:47（北京时间）");
            ChatView view=new ChatView(c.eventList(),null);view.bind(true);
            try{
                List<HBox> replies=rows(view,"【回复】");
                assertEquals(3,replies.size());
                CollapsibleText folded=(CollapsibleText)replies.get(0).getChildren().get(1);
                assertFalse(folded.isExpanded());
                folded.setExpanded(true);assertEquals(longText,folded.contentArea().getText());
                assertEquals("简短进度",text(replies.get(1)).fullText());
                assertEquals(longText+"最终答复",text(replies.get(2)).fullText());
            }finally{view.bind(false);}
        });
    }

    @Test public void statusUpdatesSameOriginalRowWithColorAndAccurateTooltip() throws Exception {
        SessionController c = new SessionController(); ChatView[] view = {null};
        String full = String.join("", Collections.nCopies(7000, "完整原文"));
        MessageTextPane[] original = {null};
        fx(() -> {
            c.onSteeringReceipt(receipt("g", full, "unread", 1));
            view[0] = new ChatView(c.eventList(), null); view[0].bind(true);
            HBox row = rows(view[0], "【引导·").get(0);
            assertEquals("【引导·未读】", tag(row).getText());
            assertTrue(tag(row).getStyle().contains("#9da7b5"));
            original[0] = text(row); original[0].contentArea().selectAll();
            c.onSteeringReceipt(receipt("g", "节选…", "read", 2));
        });
        try {
            fx(() -> {
                assertEquals(1, rows(view[0], "【引导·").size());
                HBox row = rows(view[0], "【引导·").get(0);
                assertSame(original[0], text(row));
                assertEquals(full, text(row).fullText());
                assertTrue(text(row).contentArea().getText().length() <= MessageTextPane.PAGE_CHARS + 1);
                assertEquals(text(row).contentArea().getText(), text(row).contentArea().getSelectedText());
                assertEquals("【引导·已读】", tag(row).getText());
                assertTrue(tag(row).getStyle().contains("#3fb950"));
                String tip = tag(row).getTooltip().getText();
                assertTrue(tip.contains("确实包含该引导的模型请求已收到响应"));
                assertTrue(tip.contains("不代表模型已理解、已执行或任务已完成"));
                assertEquals(2, c.eventList().size());
            });
        } finally { fx(() -> view[0].bind(false)); }
    }

    @Test public void unconfirmedAndQueuedTaskDraftNeverAppearAsReadGuidance() throws Exception {
        fx(() -> {
            SessionController c = new SessionController();
            for (String state : new String[]{"unread", "inserted", "sending", "unconfirmed"})
                c.onSteeringReceipt(receipt(state, "相同文字", state, 1));
            c.onUserSupplement("普通提问回答"); c.onUserMessage("下一任务草稿文字");
            ChatView view = new ChatView(c.eventList(), null); view.bind(true);
            try {
                assertEquals(4, rows(view, "【引导·").size());
                for (HBox row : rows(view, "【引导·")) {
                    assertEquals("【引导·未读】",tag(row).getText());
                    assertTrue(tag(row).getStyle().contains("#9da7b5"));
                }
                assertEquals(2, rows(view, "【输入】").size());
                assertFalse(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.STEERING_UPDATE));
            } finally { view.bind(false); }
        });
    }

    @Test public void metadataDoesNotFinalizeThinkingOrSplitLiveReply() throws Exception {
        SessionController c = new SessionController(); ChatView[] view = {null};
        CollapsibleText[] thinking = {null}; MessageTextPane[] reply = {null};
        fx(() -> {
            c.onSteeringReceipt(receipt("g", "引导", "unread", 1));
            view[0] = new ChatView(c.eventList(), null); view[0].bind(true); c.onThinking("思考");
        });
        try {
            fx(() -> {
                thinking[0] = (CollapsibleText) rows(view[0], "【思考】").get(0).getChildren().get(1);
                assertTrue(thinking[0].isExpanded());
                c.onSteeringReceipt(receipt("g", "节选", "inserted", 2));
            });
            fx(() -> {
                assertTrue(thinking[0].isExpanded());
                c.onThinking("续"); c.onContent("回复A");
            });
            fx(() -> {
                assertFalse(thinking[0].isExpanded());
                reply[0] = text(rows(view[0], "【回复】").get(0));
                c.onSteeringReceipt(receipt("g", "节选", "read", 3)); c.onContent("回复B");
            });
            fx(() -> {
                assertEquals(1, rows(view[0], "【回复】").size());
                assertSame(reply[0], text(rows(view[0], "【回复】").get(0)));
                assertEquals("回复A回复B", reply[0].fullText());
                assertEquals(1, rows(view[0], "【引导·").size());
                c.onResponseEnd();
            });
        } finally { fx(() -> view[0].bind(false)); }
    }

    @Test public void visibleOldPageReceivesReadStatusWithoutJumpingOrAppendingBody() throws Exception {
        SessionController c = new SessionController(); ChatView[] view = {null}; HBox[] original = {null};
        fx(() -> {
            c.onSteeringReceipt(receipt("g", "旧页完整引导", "unread", 1));
            for (int i = 0; i < 310; i++) c.onUserMessage("历史" + i);
            view[0] = new ChatView(c.eventList(), null); view[0].bind(true);
            assertTrue(rows(view[0], "【引导·").isEmpty());
            press(view[0], "history-older"); press(view[0], "history-older");
            original[0] = rows(view[0], "【引导·").get(0);
            assertEquals("旧页完整引导", text(original[0]).fullText());
            c.onSteeringReceipt(receipt("g", "节选", "read", 2)); c.onUserMessage("新消息不打断旧页");
        });
        try {
            fx(() -> {
                assertSame(original[0], rows(view[0], "【引导·").get(0));
                assertEquals("【引导·已读】", tag(original[0]).getText());
                assertEquals("旧页完整引导", text(original[0]).fullText());
                assertEquals(10, rows(view[0], "【输入】").size());
                view[0].bind(false); view[0].bind(true);
                assertEquals("【引导·已读】", tag(rows(view[0], "【引导·").get(0)).getText());
                assertEquals(10, rows(view[0], "【输入】").size());
            });
        } finally { fx(() -> view[0].bind(false)); }
    }

    @Test public void queuedOldBindingDoesNotLeakIntoAnotherSessionAndRebindRestoresState() throws Exception {
        SessionController a = new SessionController(), b = new SessionController(); ChatView[] views = {null, null};
        fx(() -> {
            a.onSteeringReceipt(receipt("same", "会话A引导", "unread", 1));
            b.onSteeringReceipt(receipt("same", "会话B引导", "unread", 1));
            views[0] = new ChatView(a.eventList(), null); views[0].bind(true);
            views[1] = new ChatView(b.eventList(), null);
            a.onSteeringReceipt(receipt("same", "A节选", "read", 2));
            views[0].bind(false); views[1].bind(true);
        });
        try {
            fx(() -> {
                assertEquals("【引导·未读】", tag(rows(views[0], "【引导·").get(0)).getText());
                assertEquals("【引导·未读】", tag(rows(views[1], "【引导·").get(0)).getText());
                assertEquals("会话B引导", text(rows(views[1], "【引导·").get(0)).fullText());
                views[1].bind(false); views[0].bind(true);
                assertEquals("【引导·已读】", tag(rows(views[0], "【引导·").get(0)).getText());
                assertEquals("会话A引导", text(rows(views[0], "【引导·").get(0)).fullText());
                assertEquals(1, rows(views[0], "【引导·").size());
            });
        } finally { fx(() -> { views[0].bind(false); views[1].bind(false); }); }
    }

    @Test public void orphanUpdateNeverSynthesizesANewUserBody() throws Exception {
        fx(() -> {
            EventList events = new EventList();
            events.add(new EventList.Ev(EventList.Kind.STEERING_UPDATE, null, receipt("orphan", "节选", "read", 2)));
            ChatView view = new ChatView(events, null); view.bind(true);
            try {
                assertTrue(rows(view, "【引导·").isEmpty());
                assertTrue(rows(view, "【输入】").isEmpty());
                assertEquals(1, events.size());
            } finally { view.bind(false); }
        });
    }
}
