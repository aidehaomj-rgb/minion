package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import org.junit.Test;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** 计算机使用纯逻辑测试：键码映射/组合键解析/scale 换算/直接可键入判定/配置取值范围。
 *  不触发真实鼠标键盘与截图（Robot 集成路径需真实桌面，不在单测范围）。 */
public class ComputerUseLogicTest {

    // ---- KeyboardTool ----

    @Test public void asciiTextIsDirectTypeableButChineseIsNot() {
        assertTrue(KeyboardTool.isDirectTypeable("hello world 123 !@#"));
        assertFalse(KeyboardTool.isDirectTypeable("包含中文"));
        assertFalse(KeyboardTool.isDirectTypeable("mixed 中 English"));
        // \t/\n 是控制字符：逐键输入会触发控件行为（Tab 切焦点/Enter 提交），统一走剪贴板
        assertFalse(KeyboardTool.isDirectTypeable("\t\n"));
    }

    @Test public void comboParsingCoversModifiersAndMainKeys() {
        int[] enter = KeyboardTool.parseCombo("enter");
        assertEquals(1, enter.length);
        assertEquals(KeyEvent.VK_ENTER, enter[0]);

        int[] ctrlS = KeyboardTool.parseCombo("ctrl+s");
        assertEquals(2, ctrlS.length);
        assertEquals(KeyEvent.VK_CONTROL, ctrlS[0]);
        assertEquals(KeyEvent.VK_S, ctrlS[1]);

        int[] ccs = KeyboardTool.parseCombo("ctrl+shift+s");
        assertEquals(3, ccs.length);
        assertEquals(KeyEvent.VK_SHIFT, ccs[1]);
        assertEquals(KeyEvent.VK_S, ccs[2]);

        int[] altF4 = KeyboardTool.parseCombo("alt+f4");
        assertEquals(KeyEvent.VK_F4, altF4[1]);
        assertArrayEquals(new int[]{KeyEvent.VK_WINDOWS, KeyEvent.VK_R}, KeyboardTool.parseCombo("win+r"));

        assertNull(KeyboardTool.parseCombo("ctrl"));        // 修饰键不能单独
        assertNull(KeyboardTool.parseCombo("a+b"));          // 两个主键非法
        assertNull(KeyboardTool.parseCombo("ctrl+"));        // 空
        assertNull(KeyboardTool.parseCombo("ctrl+xyz"));     // 未知主键
    }

    @Test public void asciiKeymapCoversAllDirectTypeableChars() throws Exception {
        // 映射完整性：反射读 ASCII_KEYS，每个 32-126 可打印字符都必须有键码（typeAscii 不丢字符）
        java.lang.reflect.Field f = KeyboardTool.class.getDeclaredField("ASCII_KEYS");
        f.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) f.get(null);
        for (char c = 32; c <= 126; c++) {
            assertTrue("可打印字符缺键码映射: '" + c + "'(" + (int) c + ")",
                    map.containsKey((int) c));
        }
    }

    @Test public void shiftCharsMapToBaseKeys() {
        // JDK8 无 VK_PIPE 等常量：Shift 标点统一映射基础键，NEEDS_SHIFT 覆盖
        assertTrue(KeyboardTool.NEEDS_SHIFT.indexOf('|') >= 0);
        assertTrue(KeyboardTool.NEEDS_SHIFT.indexOf('{') >= 0);
        assertTrue(KeyboardTool.NEEDS_SHIFT.indexOf('~') >= 0);
    }

    // ---- MouseTool 坐标换算 ----

    @Test public void scaleConversionMultipliesAndClamps() {
        JsonObject args = new JsonObject();
        args.addProperty("x", 100);
        args.addProperty("y", 50);
        int[] p = MouseTool.toScreen(args, "x", "y", 1.5);
        assertEquals(150, p[0]);
        assertEquals(75, p[1]);
        // 负/零/离谱 scale 回退 1.0
        assertEquals(100, MouseTool.toScreen(args, "x", "y", 0)[0]);
        assertEquals(100, MouseTool.toScreen(args, "x", "y", 999)[0]);
        assertEquals(1.0, MouseTool.scale(new JsonObject()), 0.0001);
    }

    @Test public void mouseHighRiskOnlyForClickLikeActions() {
        JsonObject click = new JsonObject(); click.addProperty("action", "click");
        JsonObject move = new JsonObject(); move.addProperty("action", "move");
        Tool mouse = new MouseTool(new ComputerUseConfig());
        assertTrue(mouse.isHighRisk(click));
        assertFalse(mouse.isHighRisk(move));
    }

    // ---- Config 安全取值 ----

    @Test public void configFallsBackToSafeDefaults() {
        ComputerUseConfig c = new ComputerUseConfig();
        assertEquals(1280, c.maxScreenshotEdgeSafe());
        assertEquals(150, c.clickDelayMsSafe());
        c.maxScreenshotEdge = 100; c.clickDelayMs = -5;
        assertEquals(1280, c.maxScreenshotEdgeSafe());
        assertEquals(150, c.clickDelayMsSafe());
        c.maxScreenshotEdge = 2000; c.clickDelayMs = 300;
        assertEquals(2000, c.maxScreenshotEdgeSafe());
        assertEquals(300, c.clickDelayMsSafe());
    }

    // ---- Screen/Window 描述与动作校验（不触发真实截图）----

    @Test public void screenRejectsUnknownAction() {
        ScreenTool screen = new ScreenTool(new ComputerUseConfig());
        JsonObject bad = new JsonObject(); bad.addProperty("action", "nope");
        ToolResult r = screen.execute(bad);
        assertFalse(r.ok);
        assertTrue(r.output.contains("screenshot 或 windows"));
    }

    @Test public void windowRejectsMissingTitleAndUnknownAction() {
        WindowTool window = new WindowTool();
        JsonObject noTitle = new JsonObject(); noTitle.addProperty("action", "activate");
        ToolResult r1 = window.execute(noTitle);
        assertFalse(r1.ok);
        assertTrue(r1.output.contains("title"));
        JsonObject badAction = new JsonObject(); badAction.addProperty("action", "destroy");
        ToolResult r2 = window.execute(badAction);
        assertFalse(r2.ok);
        JsonObject close = new JsonObject(); close.addProperty("action", "close"); close.addProperty("title", "记事本");
        assertTrue(window.isHighRisk(close));
        JsonObject activate = new JsonObject(); activate.addProperty("action", "activate");
        assertFalse(window.isHighRisk(activate));
    }

    @Test public void win32AvailabilityAndWindowListDegradeGracefully() {
        // 本机 Windows 应可用；即使不可用 visibleWindows 也返回空列表而非抛异常
        assertTrue(Win32Gui.available());
        assertNotNull(Win32Gui.visibleWindows());
        // 找不到的标题返回 null 而非异常
        assertNull(Win32Gui.findWindow("绝对不存在的窗口标题__xyz__"));
    }
}
