package com.minion.core.hooks;

import com.minion.core.security.ProjectTrustStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * §10.9 Hook 持续信任验收：approve 落盘指纹、跨会话 tryResumeFromTrust 免提示续用、
 * git pull 后变化须重新批准（未变化项不受影响）、会话中脚本内容变化跳过执行（零子进程）。
 */
public class HookSystemTrustTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String CFG_HEAD = "{\"hooks\":{\"Stop\":[{\"matcher\":\"\",\"hooks\":[";
    private static final String CFG_TAIL = "]}]}}";

    private static String cfg(String... commands) {
        StringBuilder sb = new StringBuilder(CFG_HEAD);
        for (int i = 0; i < commands.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"command\",\"command\":\"")
              .append(commands[i].replace("\\", "\\\\").replace("\"", "\\\"")).append("\"}");
        }
        return sb.append(CFG_TAIL).toString();
    }

    private static String quote(String s) {
        return new com.google.gson.Gson().toJson(s);
    }

    private Path writeConfig(String json) throws Exception {
        Path root = tmp.newFolder().toPath();
        Files.createDirectories(root.resolve(".minion"));
        Files.write(root.resolve(".minion").resolve("cc-hooks.json"),
                json.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /** 批准 → 指纹落盘 → 新会话（新 HookSystem 实例 + 新 store 装载）免提示恢复 */
    @Test public void approvePersistsAndResumesAcrossSessions() throws Exception {
        Path root = writeConfig(cfg("echo hello"));
        HookSystem first = new HookSystem(root);
        ProjectTrustStore store = ProjectTrustStore.load(root);
        first.attachTrustStore(store);
        assertFalse(first.enabled());
        String hash = com.minion.core.security.TrustedExecutable.sha256Hex(
                Files.readAllBytes(root.resolve(".minion").resolve("cc-hooks.json")));
        first.approve(hash);
        assertTrue(first.enabled());
        assertFalse("批准须落盘指纹", store.entries().get("hook").isEmpty());

        // 新会话：全新实例 + 重载信任库 → 未变化配置免提示恢复
        HookSystem second = new HookSystem(root);
        assertFalse(second.enabled());
        second.attachTrustStore(ProjectTrustStore.load(root));
        assertTrue("未变化 Hook 应免提示恢复", second.tryResumeFromTrust());
        assertTrue(second.enabled());
    }

    /** git pull 后配置变化（增/改命令）→ 恢复失败，须重新批准；旧命令批准不被复用 */
    @Test public void configChangeRequiresReapproval() throws Exception {
        Path root = writeConfig(cfg("echo one"));
        HookSystem first = new HookSystem(root);
        first.attachTrustStore(ProjectTrustStore.load(root));
        first.approve(com.minion.core.security.TrustedExecutable.sha256Hex(
                Files.readAllBytes(root.resolve(".minion").resolve("cc-hooks.json"))));

        // git pull：命令变化
        Files.write(root.resolve(".minion").resolve("cc-hooks.json"),
                cfg("echo changed").getBytes(StandardCharsets.UTF_8));
        HookSystem after = new HookSystem(root);
        after.attachTrustStore(ProjectTrustStore.load(root));
        assertFalse("变化后不得自动恢复", after.tryResumeFromTrust());
        assertFalse(after.enabled());

        // 新增 hook 同样阻断
        Files.write(root.resolve(".minion").resolve("cc-hooks.json"),
                cfg("echo one", "echo two").getBytes(StandardCharsets.UTF_8));
        HookSystem added = new HookSystem(root);
        added.attachTrustStore(ProjectTrustStore.load(root));
        assertFalse("新增条目不得自动恢复", added.tryResumeFromTrust());
    }

    /**
     * 会话中引用脚本内容变化 → 该 hook 跳过（不执行新内容、零子进程）；
     * 未变化 hook 照常执行；脚本还原后旧批准复用。
     */
    @Test public void midSessionScriptChangeSkipsOnlyChangedHook() throws Exception {
        Path root = tmp.newFolder().toPath();
        Files.createDirectories(root.resolve(".minion"));
        Path scriptA = root.resolve("hook-a.cmd");
        Path scriptB = root.resolve("hook-b.cmd");
        Files.write(scriptA, "cmd /c echo a > a-marker.txt".getBytes(StandardCharsets.UTF_8));
        Files.write(scriptB, "cmd /c echo b > b-marker.txt".getBytes(StandardCharsets.UTF_8));
        // Windows 下命令引用本地脚本；非 Windows 无法执行 cmd 脚本，跳过本用例
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!win) { System.out.println("[skip] 非 Windows：cmd 脚本用例跳过"); return; }

        Files.write(root.resolve(".minion").resolve("cc-hooks.json"),
                cfg("hook-a.cmd", "hook-b.cmd").getBytes(StandardCharsets.UTF_8));
        HookSystem system = new HookSystem(root);
        ProjectTrustStore store = ProjectTrustStore.load(root);
        system.attachTrustStore(store);
        system.approve(com.minion.core.security.TrustedExecutable.sha256Hex(
                Files.readAllBytes(root.resolve(".minion").resolve("cc-hooks.json"))));

        // 基线：两个 hook 都执行
        HookSystem.Outcome base = system.run(HookSystem.STOP, "", null);
        assertFalse(base.blocked);
        assertTrue(waitForFile(root.resolve("a-marker.txt")));
        assertTrue(waitForFile(root.resolve("b-marker.txt")));

        // git pull：仅脚本 A 内容变化（B 不动 = 未变化项）
        Path aStamp = root.resolve("a-second.txt");
        Files.write(scriptA, ("cmd /c echo a2 > " + aStamp).getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.delete(root.resolve("b-marker.txt"));   // 删除后由未变化的 B 重建
        HookSystem.Outcome after = system.run(HookSystem.STOP, "", null);
        assertFalse(after.blocked);
        assertTrue("未变化 hook 照常执行（b-marker 重建）", waitForFile(root.resolve("b-marker.txt")));
        assertFalse("变化脚本不得执行（零子进程）", Files.exists(aStamp));
        assertTrue("变化 hook 须反馈 systemMessage", after.systemMessage != null
                && after.systemMessage.contains("不执行"));

        // 还原脚本 A 为批准时内容 → 旧批准复用，恢复执行；再改新内容 → 仍不执行
        Files.write(scriptA, "cmd /c echo a > a-marker.txt".getBytes(StandardCharsets.UTF_8));
        HookSystem.Outcome restored = system.run(HookSystem.STOP, "", null);
        assertFalse(restored.blocked);
        Files.write(scriptA, ("cmd /c echo a3 > " + aStamp).getBytes(StandardCharsets.UTF_8));
        HookSystem.Outcome reChanged = system.run(HookSystem.STOP, "", null);
        assertFalse(reChanged.blocked);
        assertFalse("批准时内容才复用（a3 是未批准内容）", Files.exists(aStamp));
    }

    /** 撤销条目 → 执行前复核失败 → 跳过（不执行） */
    @Test public void revokedEntrySkipped() throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!win) { System.out.println("[skip] 非 Windows：cmd 用例跳过"); return; }
        Path root = tmp.newFolder().toPath();
        Files.createDirectories(root.resolve(".minion"));
        Path stamp = root.resolve("revoked-stamp.txt");
        Files.write(root.resolve(".minion").resolve("cc-hooks.json"),
                cfg("cmd /c echo x > " + stamp).getBytes(StandardCharsets.UTF_8));
        HookSystem system = new HookSystem(root);
        ProjectTrustStore store = ProjectTrustStore.load(root);
        system.attachTrustStore(store);
        system.approve(com.minion.core.security.TrustedExecutable.sha256Hex(
                Files.readAllBytes(root.resolve(".minion").resolve("cc-hooks.json"))));
        system.run(HookSystem.STOP, "", null);
        assertTrue(waitForFile(stamp));

        // 撤销全部 hook 信任 → 再触发不执行（stamp 不更新；用第二标记验证）
        java.util.Map<String, java.util.Map<String, ProjectTrustStore.TrustRecord>> entries =
                store.entries();
        for (java.util.Map.Entry<String, java.util.Map<String, ProjectTrustStore.TrustRecord>> t
                : entries.entrySet())
            for (String id : new java.util.HashSet<String>(t.getValue().keySet()))
                store.remove(t.getKey(), id);
        Path stamp2 = root.resolve("revoked-stamp2.txt");
        Files.write(root.resolve(".minion").resolve("cc-hooks.json"),
                cfg("cmd /c echo y > " + stamp2).getBytes(StandardCharsets.UTF_8));
        HookSystem revoked = new HookSystem(root);
        revoked.attachTrustStore(store);
        // 重新批准旧配置已不可能（配置变了）；手工把新配置指纹当作未批准处理 → run 前未 approve
        assertFalse(revoked.tryResumeFromTrust());
        revoked.approve(com.minion.core.security.TrustedExecutable.sha256Hex(
                Files.readAllBytes(root.resolve(".minion").resolve("cc-hooks.json"))));
        // 撤销 store 中该条目（模拟 UI 取消信任）→ 执行前复核拦截
        for (String id : new java.util.HashSet<String>(store.entries().get("hook").keySet()))
            store.remove("hook", id);
        revoked.run(HookSystem.STOP, "", null);
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !Files.exists(stamp2))
            Thread.sleep(100);
        assertFalse("撤销后不得执行", Files.exists(stamp2));
    }

    /** 未附加信任库（旧装配）：approve/run 行为保持 V68 会话内模型不变 */
    @Test public void legacyNoStoreBehaviorUnchanged() throws Exception {
        Path root = writeConfig(cfg("echo legacy"));
        HookSystem system = new HookSystem(root);
        system.approve(com.minion.core.security.TrustedExecutable.sha256Hex(
                Files.readAllBytes(root.resolve(".minion").resolve("cc-hooks.json"))));
        assertTrue(system.enabled());
        HookSystem.Outcome o = system.run(HookSystem.STOP, "", null);
        assertFalse(o.blocked);
        assertTrue(o.executed >= 1);   // 无信任库 → 不做指纹复核，照常执行
    }

    private static boolean waitForFile(Path p) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(p)) return true;
            Thread.sleep(100);
        }
        return Files.exists(p);
    }
}
