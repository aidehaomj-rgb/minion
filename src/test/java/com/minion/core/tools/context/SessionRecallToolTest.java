package com.minion.core.tools.context;

import com.google.gson.JsonObject;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.ToolResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.*;

/** 第三批 B3：会话历史主动召回。只读、当前项目隔离、排序=匹配分×新近度、read 不泄系统提示。 */
public class SessionRecallToolTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private SessionStore store;
    private Path projectA;
    private Path projectB;

    private void setUpProjects() throws Exception {
        projectA = tmp.newFolder("project-a").toPath();
        projectB = tmp.newFolder("project-b").toPath();
        store = new SessionStore(tmp.newFolder("sessions").toPath());
    }

    private Session session(Path workDir, long ts, String... userTexts) throws Exception {
        Session s = Session.create(workDir.toString(), "test-model");
        for (String text : userTexts) {
            Message m = Message.realUser(text);
            m.ts = ts;
            s.messages.add(m);
            Message reply = Message.assistant("收到：" + text);
            reply.ts = ts + 1;
            s.messages.add(reply);
        }
        store.save(s);
        return s;
    }

    private static JsonObject args(String action) {
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        return o;
    }

    /** 排序=匹配分×新近度：同等匹配下新会话在前 */
    @Test public void recencyBreaksTies() throws Exception {
        setUpProjects();
        long now = System.currentTimeMillis();
        Session old = session(projectA, now - 90L * 24 * 3600 * 1000, "税则调整讨论");
        Session fresh = session(projectA, now - 1000, "税则更新说明");
        SessionRecallTool tool = new SessionRecallTool(store, projectA.toString());
        JsonObject search = args("search");
        search.addProperty("query", "税则");
        ToolResult result = tool.execute(search);
        assertTrue(result.output, result.ok);
        int freshAt = result.output.indexOf(fresh.id);
        int oldAt = result.output.indexOf(old.id);
        assertTrue(freshAt >= 0 && oldAt >= 0);
        assertTrue("新会话应排前: " + result.output, freshAt < oldAt);
    }

    /** 中文 bigram 召回 */
    @Test public void chineseBigramRecall() throws Exception {
        setUpProjects();
        Session s = session(projectA, System.currentTimeMillis(), "进口税则第一版发布");
        SessionRecallTool tool = new SessionRecallTool(store, projectA.toString());
        JsonObject search = args("search");
        search.addProperty("query", "税则");
        ToolResult result = tool.execute(search);
        assertTrue(result.output, result.output.contains(s.id));
        assertTrue(result.output.contains("around="));
    }

    /** 跨项目隔离：其它 workDir 的会话不可见（search 与 read 都拒绝） */
    @Test public void crossProjectIsolation() throws Exception {
        setUpProjects();
        Session other = session(projectB, System.currentTimeMillis(), "别的项目的税则机密");
        SessionRecallTool tool = new SessionRecallTool(store, projectA.toString());
        JsonObject search = args("search");
        search.addProperty("query", "税则");
        ToolResult result = tool.execute(search);
        assertTrue(result.ok);
        assertFalse(result.output.contains(other.id));
        JsonObject read = args("read");
        read.addProperty("sessionId", other.id);
        assertFalse(tool.execute(read).ok);
    }

    /** 空存储：可操作提示而非异常 */
    @Test public void emptyStore() throws Exception {
        setUpProjects();
        SessionRecallTool tool = new SessionRecallTool(store, projectA.toString());
        JsonObject search = args("search");
        search.addProperty("query", "任何");
        ToolResult result = tool.execute(search);
        assertTrue(result.ok);
        assertTrue(result.output.contains("未找到"));
    }

    /** read 边界：around 夹取、≤50 条、不泄系统提示/工具回执/压缩摘要/控制消息 */
    @Test public void readProjectionAndBounds() throws Exception {
        setUpProjects();
        Session s = Session.create(projectA.toString(), "test-model");
        s.messages.add(Message.system("系统提示机密"));
        s.messages.add(Message.taskControl("[系统当前工作区与任务控制] 内部"));
        s.messages.add(Message.historyRecall("召回注入内容"));
        Message summary = Message.user("压缩摘要内容");
        summary.summary = true;
        s.messages.add(summary);
        Message toolResult = Message.toolResult("call-1", "SomeTool", "工具输出机密");
        s.messages.add(toolResult);
        for (int i = 0; i < 120; i++) {
            Message m = Message.realUser("问题" + i + " 税则");
            m.ts = System.currentTimeMillis();
            s.messages.add(m);
        }
        store.save(s);
        SessionRecallTool tool = new SessionRecallTool(store, projectA.toString());
        JsonObject read = args("read");
        read.addProperty("sessionId", s.id);
        read.addProperty("around", 99999);   // 越界 → 夹到末尾
        ToolResult result = tool.execute(read);
        assertTrue(result.output, result.ok);
        assertFalse(result.output.contains("系统提示机密"));
        assertFalse(result.output.contains("工具输出机密"));
        assertFalse(result.output.contains("压缩摘要内容"));
        assertFalse(result.output.contains("内部"));
        assertFalse(result.output.contains("召回注入内容"));
        assertTrue(result.output.contains("问题119"));
        int shown = result.output.split("\n\n\\[").length - 1;
        assertTrue("最多 50 条，实际 " + shown, shown <= 50);
        JsonObject bad = args("read");
        bad.addProperty("sessionId", "no-such-session");
        assertFalse(tool.execute(bad).ok);
    }

    /** 只读声明：任何 action 都不高危、只读 */
    @Test public void readOnlyMetadata() {
        SessionRecallTool tool = new SessionRecallTool(null, null);
        JsonObject search = args("search");
        assertFalse(tool.isHighRisk(search));
        assertTrue(tool.isReadOnly(search));
        assertFalse(tool.isDestructive(search));
    }
}