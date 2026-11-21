package com.minion.core.notify;

import com.minion.core.config.Config;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * v2.4 §10.8 通知通道验收（headless）：Notification hook 先行、channel 路由
 * （tray/sound/disabled）、通知失败不影响主任务（全路径吞异常）。
 * 真实托盘/声音归 Win7 实机 POC 与人工验收批。
 */
public class NotifierTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Config config(String kv) throws Exception {
        Path dir = tmp.newFolder().toPath();
        Config c = Config.load(dir);
        if (kv != null) {
            String[] pair = kv.split("=", 2);
            c.set(pair[0], pair[1]);
        }
        return c;
    }

    /** disabled：不发送、不抛异常 */
    @Test public void disabledChannelSilent() throws Exception {
        Notifier n = new Notifier(config("notify.channel=disabled"));
        assertEquals("disabled", n.channel());
        n.notify(null, "标题", "内容");   // 无异常即通过（AWT 路径完全跳过）
    }

    /** sound 通道：beep 发送（无音频环境 Toolkit 自吞，不抛异常） */
    @Test public void soundChannelBeeps() throws Exception {
        Notifier n = new Notifier(config("notify.channel=sound"));
        assertEquals("sound", n.channel());
        n.notify(null, "标题", "完成");
    }

    /** 默认 tray：headless 测试环境自动降级为 beep，不抛异常（通知失败不影响主任务） */
    @Test public void trayChannelDegradesSafely() throws Exception {
        Notifier n = new Notifier(null);   // null config → 默认 tray
        assertEquals("tray", n.channel());
        n.notify(null, "标题", "完成");
    }

    /** Notification hook 先行：hook 收到事件且 message 字段正确（hook 异常也不拦发送） */
    @Test public void notificationHookRunsFirst() throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!win) { System.out.println("[skip] 非 Windows：hook 内容断言跳过"); return; }
        java.nio.file.Path root = tmp.newFolder().toPath();
        java.nio.file.Path cfg = root.resolve(".minion").resolve("cc-hooks.json");
        java.nio.file.Files.createDirectories(cfg.getParent());
        // hook：把 stdin JSON 原样落盘（收到即证明先行于通道）
        java.nio.file.Path hookOut = root.resolve("hook-received.json");
        String command = "cmd /c findstr . > \"" + hookOut + "\"";
        String json = "{\"hooks\":{\"Notification\":[{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\",\"command\":"
                + new com.google.gson.Gson().toJson(command) + ",\"timeout\":10000}]}]}}";
        java.nio.file.Files.write(cfg, json.getBytes("UTF-8"));
        com.minion.core.hooks.HookSystem hooks = new com.minion.core.hooks.HookSystem(root);
        String hash = com.minion.core.security.TrustedExecutable.sha256Hex(
                java.nio.file.Files.readAllBytes(cfg));
        hooks.approve(hash);
        assertTrue(hooks.enabled());

        Notifier n = new Notifier(config("notify.channel=sound"));
        n.notify(hooks, "标题", "hello-notify");
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && !java.nio.file.Files.exists(hookOut))
            Thread.sleep(200);
        assertTrue("Notification hook 应先于通道执行（stdin 落盘）", java.nio.file.Files.exists(hookOut));
        String received = new String(java.nio.file.Files.readAllBytes(hookOut), "GBK");
        assertTrue(received.contains("hello-notify"));
        assertTrue(received.contains("Notification"));
    }
}
