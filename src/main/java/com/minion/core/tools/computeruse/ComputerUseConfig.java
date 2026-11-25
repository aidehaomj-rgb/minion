package com.minion.core.tools.computeruse;

/** 计算机使用插件配置（tools.json 的 computerUse 段）。 */
public class ComputerUseConfig {
    /** 启用开关 = 工具描述注入开关（ToolRegistry gate 拉模式），默认关闭 */
    public boolean enabled;
    /** 截图长边上限（等比缩放，控制视觉 token；0/非法回退 1280） */
    public int maxScreenshotEdge = 1280;
    /** 鼠标移动到点击的间隔毫秒（给应用 hover 反应时间） */
    public int clickDelayMs = 150;
    /** 三批 C2：粘贴后恢复剪贴板前的缓冲毫秒（Robot Ctrl+V 异步投递，立即写回会让目标读到旧值） */
    public int clipboardRestoreDelayMs = 120;

    public int maxScreenshotEdgeSafe() { return maxScreenshotEdge >= 400 && maxScreenshotEdge <= 4000 ? maxScreenshotEdge : 1280; }
    public int clickDelayMsSafe() { return clickDelayMs >= 0 && clickDelayMs <= 2000 ? clickDelayMs : 150; }
    /** 合法区间 80–150ms，越界回退默认 120 */
    public int clipboardRestoreDelayMsSafe() { return clipboardRestoreDelayMs >= 80 && clipboardRestoreDelayMs <= 150 ? clipboardRestoreDelayMs : 120; }
}
