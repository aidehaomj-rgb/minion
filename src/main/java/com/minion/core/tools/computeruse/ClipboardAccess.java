package com.minion.core.tools.computeruse;

/** 剪贴板文本访问小接口：AWT 实现 + 测试 fake，headless 可测。
 *  readText 无文本内容返回 null；访问失败抛异常，由调用方决定降级。 */
public interface ClipboardAccess {
    String readText();
    void writeText(String text);
}