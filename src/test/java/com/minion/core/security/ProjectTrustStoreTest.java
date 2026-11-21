package com.minion.core.security;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * §10.9 ProjectTrustStore 验收：指纹变化即失效（命令/参数/env/cwd/作用域/引用脚本内容）、
 * 符号链接替换不复用、损坏 fail-closed、replaceType 清旧、MISSING 脚本语义。
 */
public class ProjectTrustStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static TrustedExecutable spec(String command) {
        return TrustedExecutable.of(TrustedExecutable.TYPE_HOOK, "id1", "project", command,
                Collections.<String>emptyList(), Collections.<String, String>emptyMap(),
                "C:/w", Collections.<Path>emptyList());
    }

    /** 批准 → 信任；撤销 → 失效 */
    @Test public void approveThenTrustedRevokeClears() throws Exception {
        Path root = tmp.newFolder().toPath();
        ProjectTrustStore store = ProjectTrustStore.load(root);
        assertFalse(store.corrupted());
        assertFalse(store.isTrusted(spec("cmd /c echo hi")));
        store.approve(spec("cmd /c echo hi"));
        assertTrue(store.isTrusted(spec("cmd /c echo hi")));
        String id = store.entries().get("hook").keySet().iterator().next();
        assertTrue(store.remove("hook", id));
        assertFalse(store.isTrusted(spec("cmd /c echo hi")));
        // 重载后仍撤销（持久化生效）
        assertFalse(ProjectTrustStore.load(root).isTrusted(spec("cmd /c echo hi")));
    }

    /** git pull 后单项变化：命令/参数/env/cwd/作用域任一变化 → 指纹变化 → 旧批准失效 */
    @Test public void anyFieldChangeInvalidates() throws Exception {
        Path root = tmp.newFolder().toPath();
        ProjectTrustStore store = ProjectTrustStore.load(root);
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("A", "1");
        TrustedExecutable base = TrustedExecutable.of(TrustedExecutable.TYPE_MCP, "srv", "user",
                "run.exe", Arrays.asList("-x", "in.txt"), env, "D:/w", Collections.<Path>emptyList());
        store.approve(base);
        assertTrue(store.isTrusted(base));

        assertFalse("命令变化", store.isTrusted(TrustedExecutable.of(TrustedExecutable.TYPE_MCP,
                "srv", "user", "run2.exe", Arrays.asList("-x", "in.txt"), env, "D:/w",
                Collections.<Path>emptyList())));
        assertFalse("参数变化", store.isTrusted(TrustedExecutable.of(TrustedExecutable.TYPE_MCP,
                "srv", "user", "run.exe", Arrays.asList("-x", "in2.txt"), env, "D:/w",
                Collections.<Path>emptyList())));
        Map<String, String> env2 = new LinkedHashMap<String, String>();
        env2.put("A", "2");
        assertFalse("env 变化", store.isTrusted(TrustedExecutable.of(TrustedExecutable.TYPE_MCP,
                "srv", "user", "run.exe", Arrays.asList("-x", "in.txt"), env2, "D:/w",
                Collections.<Path>emptyList())));
        assertFalse("cwd 变化", store.isTrusted(TrustedExecutable.of(TrustedExecutable.TYPE_MCP,
                "srv", "user", "run.exe", Arrays.asList("-x", "in.txt"), env, "E:/w",
                Collections.<Path>emptyList())));
        assertFalse("作用域变化", store.isTrusted(TrustedExecutable.of(TrustedExecutable.TYPE_MCP,
                "srv", "local", "run.exe", Arrays.asList("-x", "in.txt"), env, "D:/w",
                Collections.<Path>emptyList())));
        // 未变化项不受影响：另一条目独立批准
        store.approve(spec("other"));
        assertTrue(store.isTrusted(base));
        assertTrue(store.isTrusted(spec("other")));
    }

    /** 引用脚本内容变化 → 失效（内容哈希入指纹）；恢复原内容 → 复用旧批准 */
    @Test public void referencedScriptContentChangeInvalidates() throws Exception {
        Path root = tmp.newFolder().toPath();
        Path script = root.resolve("run.bat");
        Files.write(script, "echo v1".getBytes(StandardCharsets.UTF_8));
        TrustedExecutable spec = TrustedExecutable.of(TrustedExecutable.TYPE_HOOK, "h1", "project",
                "cmd /c run.bat", Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(), root.toString(),
                TrustedExecutable.referencedScripts("cmd /c run.bat", root));
        assertEquals(1, spec.referencedScripts.size());
        ProjectTrustStore store = ProjectTrustStore.load(root);
        store.approve(spec);
        assertTrue(store.isTrusted(spec));

        Files.write(script, "echo v2-evil".getBytes(StandardCharsets.UTF_8));   // git pull 换了脚本内容
        assertFalse("脚本内容变化须失效", store.isTrusted(spec));

        Files.write(script, "echo v1".getBytes(StandardCharsets.UTF_8));        // 还原 → 旧批准复用
        assertTrue(store.isTrusted(spec));
    }

    /** 符号链接替换不能复用旧信任：指向变化 → realpath 变化 → 指纹变化（无符号链接权限时跳过） */
    @Test public void symlinkSwapNotReused() throws Exception {
        Path root = tmp.newFolder().toPath();
        Path targetA = root.resolve("a.sh");
        Path targetB = root.resolve("b.sh");
        Files.write(targetA, "same content".getBytes(StandardCharsets.UTF_8));
        Files.write(targetB, "same content".getBytes(StandardCharsets.UTF_8));   // 内容相同也不行
        Path link = root.resolve("entry.sh");
        try {
            Files.createSymbolicLink(link, targetA);
        } catch (Exception noPrivilege) {
            System.out.println("[skip] 本机无符号链接创建权限，跳过符号链接替换用例");
            return;
        }
        TrustedExecutable spec = TrustedExecutable.of(TrustedExecutable.TYPE_HOOK, "h2", "project",
                "sh entry.sh", Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(), root.toString(),
                TrustedExecutable.referencedScripts("sh entry.sh", root));
        ProjectTrustStore store = ProjectTrustStore.load(root);
        store.approve(spec);
        assertTrue(store.isTrusted(spec));
        Files.delete(link);
        Files.createSymbolicLink(link, targetB);   // 换指向（内容相同）
        assertFalse("符号链接替换不能复用旧信任", store.isTrusted(spec));
    }

    /** 损坏 fail-closed：垃圾文件 → 全不信任；approve 重建修复 */
    @Test public void corruptedStoreFailsClosedThenRepairs() throws Exception {
        Path root = tmp.newFolder().toPath();
        Path file = root.resolve(".minion").resolve("trust.json");
        Files.createDirectories(file.getParent());
        Files.write(file, "{{{ not json".getBytes(StandardCharsets.UTF_8));
        ProjectTrustStore store = ProjectTrustStore.load(root);
        assertTrue(store.corrupted());
        assertFalse(store.isTrusted(spec("cmd /c echo hi")));
        ProjectTrustStore.CheckResult c = store.check(spec("cmd /c echo hi"));
        assertFalse(c.trusted);
        assertTrue(c.reason.contains("损坏"));
        // 批准 = 重建修复（旧记录作废）
        store.approve(spec("cmd /c echo hi"));
        assertFalse(store.corrupted());
        assertTrue(store.isTrusted(spec("cmd /c echo hi")));
        // 重载后仍有效
        assertTrue(ProjectTrustStore.load(root).isTrusted(spec("cmd /c echo hi")));
    }

    /** 畸形记录（指纹非 64hex）丢弃：该条不信任，其余条目不受影响 */
    @Test public void malformedRecordDroppedOthersUnaffected() throws Exception {
        Path root = tmp.newFolder().toPath();
        TrustedExecutable good = spec("good");
        Path file = root.resolve(".minion").resolve("trust.json");
        Files.createDirectories(file.getParent());
        // 手改 trust.json：一条畸形（指纹非 hex64）+ 一条合法（good 的真实指纹）
        Files.write(file, ("{\"hook\":{"
                + "\"bad1\":{\"fingerprint\":\"zz\",\"approvedAt\":\"t\",\"preview\":\"p\"},"
                + "\"bad2\":{\"approvedAt\":\"t\",\"preview\":\"p\"},"
                + "\"id1\":{\"fingerprint\":\"" + good.fingerprint()
                + "\",\"approvedAt\":\"t\",\"preview\":\"good\"}}}").getBytes(StandardCharsets.UTF_8));
        ProjectTrustStore reloaded = ProjectTrustStore.load(root);
        assertFalse(reloaded.corrupted());
        assertTrue("未变化项不受影响", reloaded.isTrusted(good));
        assertFalse(reloaded.entries().get("hook").containsKey("bad1"));
        assertFalse(reloaded.entries().get("hook").containsKey("bad2"));
    }

    /** replaceType：整组替换清旧（配置删除的条目旧信任一并移除），其他类型保留 */
    @Test public void replaceTypePurgesStaleKeepsOthers() throws Exception {
        Path root = tmp.newFolder().toPath();
        ProjectTrustStore store = ProjectTrustStore.load(root);
        store.approve(spec("old-cmd"));
        store.approve(TrustedExecutable.of(TrustedExecutable.TYPE_MCP, "srv", "user", "run.exe",
                Collections.<String>emptyList(), Collections.<String, String>emptyMap(), "",
                Collections.<Path>emptyList()));
        store.replaceType(TrustedExecutable.TYPE_HOOK, Arrays.asList(spec("new-cmd")));
        assertFalse(store.isTrusted(spec("old-cmd")));
        assertTrue(store.isTrusted(spec("new-cmd")));
        assertTrue("其他类型不受影响", store.isTrusted(TrustedExecutable.of(
                TrustedExecutable.TYPE_MCP, "srv", "user", "run.exe",
                Collections.<String>emptyList(), Collections.<String, String>emptyMap(), "",
                Collections.<Path>emptyList())));
    }

    /** 批准时脚本缺失（MISSING）→ 之后创建脚本 → 指纹变化须失效 */
    @Test public void missingScriptThenCreatedInvalidates() throws Exception {
        Path root = tmp.newFolder().toPath();
        TrustedExecutable spec = TrustedExecutable.of(TrustedExecutable.TYPE_HOOK, "h3", "project",
                "cmd /c late.bat", Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(), root.toString(),
                TrustedExecutable.referencedScripts("cmd /c late.bat", root));
        assertTrue("缺失脚本不进引用表（命令串整体已入指纹）", spec.referencedScripts.isEmpty());
        ProjectTrustStore store = ProjectTrustStore.load(root);
        store.approve(spec);
        // 脚本出现后：命令未变，但显式传入引用时须识别为变化（审批时无此脚本）
        Path late = root.resolve("late.bat");
        Files.write(late, "echo payload".getBytes(StandardCharsets.UTF_8));
        TrustedExecutable withScript = TrustedExecutable.of(TrustedExecutable.TYPE_HOOK, "h3", "project",
                "cmd /c late.bat", Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(), root.toString(),
                TrustedExecutable.referencedScripts("cmd /c late.bat", root));
        assertEquals(1, withScript.referencedScripts.size());
        assertFalse(store.isTrusted(withScript));
    }
}
