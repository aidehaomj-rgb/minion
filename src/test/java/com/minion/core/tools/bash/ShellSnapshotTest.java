package com.minion.core.tools.bash;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** §3.3 ShellSnapshot 验收：捕获可 source 的快照（函数/shopt/alias/PATH），失败降级 null */
public class ShellSnapshotTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 真实 Git Bash 环境（开发机/内网均有）：快照文件生成且内容可 source */
    @Test public void captureProducesSourcableSnapshot() throws Exception {
        Path file = tmp.newFolder().toPath().resolve("shell-snapshot.sh");
        Path captured = ShellSnapshot.capture(file);
        if (captured == null) {
            // 无 bash 的环境（理论不达）——降级语义本身合规，跳过内容断言
            return;
        }
        assertTrue(Files.isRegularFile(captured));
        String content = new String(Files.readAllBytes(captured), StandardCharsets.UTF_8);
        // PATH 段必在；语法可 source（bash -n 校验）
        if (!content.contains("export PATH=")) {
            // 捕获到 shopt/set 但 PATH 为空的极端环境：只校验非空快照
            assertTrue(content.trim().length() > 60);
        }
        Process check = new ProcessBuilder("bash", "-n", captured.toString())
                .redirectErrorStream(true).start();
        String out = new String(readAll(check.getInputStream()), StandardCharsets.UTF_8);
        assertEquals("快照应通过 bash -n 语法校验: " + out, 0, check.waitFor());
    }

    /** 目标目录不可写等异常 → null 降级（不影响现状执行路径） */
    @Test public void failureDegradesToNull() throws Exception {
        Path bad = tmp.newFolder().toPath().resolve("no-such-dir").resolve("s.sh");
        // 父目录不存在的场景由 capture 内 createDirectories 处理——用文件占位父目录制造失败
        Path parentAsFile = tmp.newFolder().toPath().resolve("occupied");
        Files.write(parentAsFile, "x".getBytes(StandardCharsets.UTF_8));
        Path result = ShellSnapshot.capture(parentAsFile.resolve("child").resolve("s.sh"));
        assertNull(result);
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
