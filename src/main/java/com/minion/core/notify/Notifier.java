package com.minion.core.notify;

import com.minion.core.config.Config;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.hooks.HookSystem;

/**
 * v2.4 §10.8 通知通道：先执行 Notification hooks（阻断/异常不拦通知本身），再按
 * notify.channel 发送——tray（java.awt.SystemTray，Win7 JDK8 支持；默认）/ sound /
 * disabled。<b>通知失败不得影响主任务</b>：全部路径吞异常（AWT headless/无托盘安全降级）。
 * Sender 可注入（headless 测试）。
 */
public final class Notifier {

    /** 实际发送通道（tray/sound 实现方；返回 false=通道不可用） */
    public interface Sender {
        boolean send(String title, String message);
    }

    /** 声音通道：Toolkit.beep（Win7 可用；无音频设备时系统自吞） */
    static final Sender SOUND = new Sender() {
        @Override public boolean send(String title, String message) {
            java.awt.Toolkit.getDefaultToolkit().beep();
            return true;
        }
    };

    private final String channel;
    private Sender sender;      // tray 实现（延迟建 TrayIcon；失败置 null 禁用）
    private final boolean soundAlso;

    public Notifier(Config config) {
        String raw = config == null ? "tray" : config.get("notify.channel", "tray");
        this.channel = raw == null || raw.trim().isEmpty() ? "tray" : raw.trim().toLowerCase();
        this.soundAlso = "tray".equals(channel)
                && Boolean.parseBoolean(config == null ? "false" : config.get("notify.traySound", "false"));
    }

    /** 通知入口：hook 先行（可带 payload 字段），再按通道发送 */
    public void notify(HookSystem hooks, String title, String message) {
        try {
            if (hooks != null && hooks.enabled()
                    && hooks.matches(HookSystem.NOTIFICATION, "")) {
                com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
                payload.addProperty("message", message);
                hooks.run(HookSystem.NOTIFICATION, "", payload);   // 阻断=hook 决策，不拦通知
            }
        } catch (Exception hookFailure) {
            DiagnosticLog.warn("notify", "Notification hook 异常（继续发送）: " + hookFailure.getMessage());
        }
        if ("disabled".equals(channel)) return;
        try {
            if ("sound".equals(channel)) {
                SOUND.send(title, message);
                return;
            }
            if (sender == null) sender = buildTraySender();   // tray（含默认分支）
            if (sender == null || !sender.send(title, message)) {
                SOUND.send(title, message);                   // 无托盘降级为声音
                return;
            }
            if (soundAlso) SOUND.send(title, message);
        } catch (Exception failed) {
            DiagnosticLog.warn("notify", "通知发送失败（不影响主任务）: " + failed.getMessage());
        }
    }

    public String channel() { return channel; }

    /** AWT 托盘发送器：TrayIcon 延迟创建一次；headless/不支持托盘返回 null */
    private static Sender buildTraySender() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return null;
        if (!java.awt.SystemTray.isSupported()) return null;
        try {
            final java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();
            // 16x16 纯色占位图标（无资源依赖；气泡消息不受图标内容影响）
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                g.setColor(new java.awt.Color(0x2E, 0x86, 0xDE));
                g.fillRect(0, 0, 16, 16);
            } finally {
                g.dispose();
            }
            final java.awt.TrayIcon icon = new java.awt.TrayIcon(img, "Minion");
            icon.setImageAutoSize(true);
            tray.add(icon);
            return new Sender() {
                @Override public boolean send(String title, String message) {
                    icon.displayMessage(title, message, java.awt.TrayIcon.MessageType.INFO);
                    return true;
                }
            };
        } catch (Exception noTray) {
            return null;
        }
    }
}
