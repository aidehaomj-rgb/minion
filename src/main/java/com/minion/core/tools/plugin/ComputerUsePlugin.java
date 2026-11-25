package com.minion.core.tools.plugin;

import com.minion.core.tools.Tool;
import com.minion.core.tools.computeruse.ComputerUseConfig;
import com.minion.core.tools.computeruse.KeyboardTool;
import com.minion.core.tools.computeruse.MouseTool;
import com.minion.core.tools.computeruse.ScreenTool;
import com.minion.core.tools.computeruse.WindowTool;

import java.util.Arrays;
import java.util.List;

/** 计算机使用插件：Screen（截图/窗口清单）+ Mouse + Keyboard（中文走剪贴板）+ Window（激活/关闭）。
 *  面向无调试端口的原生桌面软件（政务微信/关信等）；Chromium 系（Chrome/奇安信浏览器）优先用内置
 *  浏览器 CDP 工具。截图自动附入模型视觉上下文；点击/键盘输入/关窗走高危确认。 */
public final class ComputerUsePlugin implements ToolPlugin {
    private final ComputerUseConfig config;
    private final Runnable saver;

    public ComputerUsePlugin(ComputerUseConfig config, Runnable saver) {
        this.config = config;
        this.saver = saver;
    }

    @Override public String id() { return "computerUse"; }

    @Override public String displayName() { return "计算机使用（屏幕/鼠标/键盘/窗口）"; }

    @Override public String statusText() {
        return config.maxScreenshotEdgeSafe() + "px 截图；点击/输入需确认";
    }

    @Override public boolean canEnable() { return true; }   // 无前置配置，随时可启用

    @Override public boolean enabled() { return config.enabled; }

    @Override public void setEnabled(boolean on) {
        config.enabled = on;
        saver.run();
    }

    @Override public void onConfigChanged() { /* 配置仅影响执行参数，无需重建实例 */ }

    @Override public List<Tool> createTools(com.minion.core.tools.plugin.ToolContext ctx) {
        return Arrays.<Tool>asList(
                new ScreenTool(config),
                new MouseTool(config),
                new KeyboardTool(new com.minion.core.tools.computeruse.AwtClipboard(), config.clipboardRestoreDelayMsSafe()),
                new com.minion.core.tools.computeruse.ClipboardTool(),
                new WindowTool());
    }
}
