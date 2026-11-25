package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ToolCall;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static org.junit.Assert.*;

/**
 * F2 迟到采纳（late-adopt）：展示登记与门禁证据分离。
 * 同会话 generation 推进（插话）→ 产物照常登记 late=true + UI 事件照发 + 证据计数不变；
 * 会话对象变更（/new、restore 新建 loop）→ 仍旧丢弃；late 打标随快照持久化回环。
 * 门禁逐用例一致性另由 8 套件 surefire A/B 特征化保证。
 */
public class LateAdoptTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static class Ui extends RecordingUi {
        final List<String> paths = new ArrayList<String>();
        @Override public void onArtifactCreated(String path) { paths.add(path); }
    }

    private AgentLoop loop(Path root, Session s, Ui ui) throws Exception {
        return new AgentLoop(new FakeLlmClient(), new ToolRegistry(), new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(root), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)), ui, null,
                new Workspace(root.toString()), s);
    }

    private static Class<?> ownerClass() throws Exception {
        return Class.forName("com.minion.core.agent.AgentLoop$ToolOwner");
    }
    private static Object newOwner(AgentLoop loop) throws Exception {
        Constructor<?> ctor = ownerClass().getDeclaredConstructor(AgentLoop.class);
        ctor.setAccessible(true);
        return ctor.newInstance(loop);
    }
    @SuppressWarnings("unchecked")
    private static void putCwd(Object owner, ToolCall call, Path cwd) throws Exception {
        Field f = owner.getClass().getDeclaredField("toolCwds");
        f.setAccessible(true);
        ((ConcurrentMap<ToolCall, Path>) f.get(owner)).put(call, cwd);
    }
    private static void adopt(AgentLoop loop, ToolCall call, ToolResult result, Object owner) throws Exception {
        Method m = AgentLoop.class.getDeclaredMethod("adoptToolArtifacts", ToolCall.class, ToolResult.class, ownerClass());
        m.setAccessible(true);
        m.invoke(loop, call, result, owner);
    }
    private static void advance(AgentLoop loop) throws Exception {
        Method m = AgentLoop.class.getDeclaredMethod("advanceExecutionGeneration");
        m.setAccessible(true);
        m.invoke(loop);
    }
    private static ToolCall writeCall(String name) {
        ToolCall c = new ToolCall();
        c.id = "w"; c.name = "Write";
        c.arguments = "{\"path\":\"" + name + "\"}";
        return c;
    }

    /** 插话推进 generation：迟到产物登记 late=true、UI 照发、taskState.evidence 计数不变 */
    @Test public void staleSameSessionAdoptsLateWithoutEvidenceChange() throws Exception {
        Path root = temp.newFolder().toPath();
        Files.write(root.resolve("交付.docx"), new byte[]{1});
        Session s = Session.create(root.toString(), "fixture");
        Ui ui = new Ui();
        AgentLoop loop = loop(root, s, ui);
        try {
            Object owner = newOwner(loop);
            ToolCall call = writeCall("交付.docx");
            putCwd(owner, call, root);
            advance(loop);   // 模拟工具执行中用户插话
            int evidenceBefore = s.taskState.evidence.size();
            adopt(loop, call, ToolResult.success("ok"), owner);
            GeneratedArtifact a = s.artifactsSnapshot().stream()
                    .filter(x -> x.path.endsWith("交付.docx")).findFirst().orElse(null);
            assertNotNull("同会话迟到产物应登记", a);
            assertTrue("迟到打标", a.late);
            assertEquals(GeneratedArtifact.DELIVERABLE, a.kind);
            assertEquals(1, ui.paths.size());
            assertEquals("证据计数不变", evidenceBefore, s.taskState.evidence.size());
        } finally { loop.shutdown(); }
    }

    /** 未失效 owner：正常登记且 late=false（旧语义零变化） */
    @Test public void freshOwnerAdoptsNotLate() throws Exception {
        Path root = temp.newFolder().toPath();
        Files.write(root.resolve("正常.md"), new byte[]{1});
        Session s = Session.create(root.toString(), "fixture");
        Ui ui = new Ui();
        AgentLoop loop = loop(root, s, ui);
        try {
            Object owner = newOwner(loop);
            ToolCall call = writeCall("正常.md");
            putCwd(owner, call, root);
            adopt(loop, call, ToolResult.success("ok"), owner);
            GeneratedArtifact a = s.artifactsSnapshot().get(0);
            assertFalse(a.late);
            assertEquals(1, ui.paths.size());
        } finally { loop.shutdown(); }
    }

    /** 会话对象变更（/new、restore 新建 loop 的等价场景）：仍旧丢弃，不污染新会话登记表 */
    @Test public void sessionChangedStillDrops() throws Exception {
        Path root = temp.newFolder().toPath();
        Files.write(root.resolve("旧任务.docx"), new byte[]{1});
        Session oldSession = Session.create(root.toString(), "fixture");
        Session newSession = Session.create(root.toString(), "fixture");
        Ui uiOld = new Ui(), uiNew = new Ui();
        AgentLoop oldLoop = loop(root, oldSession, uiOld);
        AgentLoop newLoop = loop(root, newSession, uiNew);
        try {
            Object owner = newOwner(oldLoop);
            ToolCall call = writeCall("旧任务.docx");
            putCwd(owner, call, root);
            adopt(newLoop, call, ToolResult.success("ok"), owner);
            assertTrue("新会话登记表不被污染", newSession.artifactsSnapshot().isEmpty());
            assertTrue(uiNew.paths.isEmpty());
            assertTrue("旧会话也不登记（采纳发生在 newLoop）", oldSession.artifactsSnapshot().isEmpty());
        } finally { oldLoop.shutdown(); newLoop.shutdown(); }
    }

    /** late 打标随 SessionStore 快照持久化回环；旧 JSON 无 late 字段缺省 false */
    @Test public void lateFlagSurvivesPersistenceRoundTrip() throws Exception {
        Path root = temp.newFolder().toPath();
        Files.write(root.resolve("持久.docx"), new byte[]{1});
        Session s = Session.create(root.toString(), "fixture");
        Ui ui = new Ui();
        AgentLoop loop = loop(root, s, ui);
        SessionStore store = new SessionStore(root.resolve("sessions"));
        loop.setSessionStore(store);
        try {
            Object owner = newOwner(loop);
            ToolCall call = writeCall("持久.docx");
            putCwd(owner, call, root);
            advance(loop);
            adopt(loop, call, ToolResult.success("ok"), owner);
            Session restored = new SessionStore(root.resolve("sessions")).load(s.id);
            GeneratedArtifact a = restored.artifactsSnapshot().stream()
                    .filter(x -> x.path.endsWith("持久.docx")).findFirst().orElse(null);
            assertNotNull(a);
            assertTrue("late 随快照持久化", a.late);
            Session legacy = new com.google.gson.Gson().fromJson("{}", Session.class);
            assertFalse("旧快照缺省 false", legacy.artifactsSnapshot().stream().anyMatch(x -> x.late));
        } finally { loop.shutdown(); }
    }
}