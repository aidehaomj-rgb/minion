package com.minion.core.context;

import com.minion.core.llm.Message;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * §5.1 Microcompact 验收：白名单清理、最近 keepRecent 保留、非白名单不动、
 * keepRecent=0 强制为 1、空历史零效果。
 */
public class MicrocompactTest {

    private static Message tool(String name, String content) {
        Message m = Message.toolResult("id-" + SEQ++, name, content);
        return m;
    }
    private static int SEQ = 0;

    /** 规格用例：10 条白名单 + 2 条非白名单 → 清理 5 条、保留最近 5 条与非白名单 */
    @Test public void sweepsOldRegenerableKeepsRecentFive() {
        List<Message> messages = new ArrayList<Message>();
        for (int i = 0; i < 10; i++) messages.add(tool("Read", "内容" + i));
        messages.add(tool("Memory", "记忆结果保持"));
        messages.add(Message.user("用户消息保持"));
        int replaced = Microcompact.sweep(messages, 5);
        assertEquals(5, replaced);
        // 最近 5 条白名单保留原文
        assertEquals("内容9", messages.get(9).content);
        assertEquals("内容8", messages.get(8).content);
        // 较早 5 条替换为占位
        assertEquals(Microcompact.PLACEHOLDER, messages.get(0).content);
        assertEquals(Microcompact.PLACEHOLDER, messages.get(4).content);
        // 非白名单工具与用户消息不动
        assertEquals("记忆结果保持", messages.get(10).content);
        assertEquals("用户消息保持", messages.get(11).content);
    }

    /** keepRecent=0 → 强制为 1（防 slice(-0) 保留全部的边界 bug） */
    @Test public void keepRecentZeroClampedToOne() {
        List<Message> messages = new ArrayList<Message>();
        for (int i = 0; i < 4; i++) messages.add(tool("Bash", "out" + i));
        int replaced = Microcompact.sweep(messages, 0);
        assertEquals(3, replaced);
        assertEquals("out3", messages.get(3).content);
        assertEquals(Microcompact.PLACEHOLDER, messages.get(2).content);
    }

    /** 白名单按名字大小写无关；Edit/Write/Grep/Glob/WebFetch 均可再生 */
    @Test public void whitelistNames() {
        List<Message> messages = new ArrayList<Message>();
        messages.add(tool("read", "a"));
        messages.add(tool("Edit", "b"));
        messages.add(tool("WebFetch", "c"));
        messages.add(tool("AgentControl", "d"));   // 非白名单
        messages.add(tool("Edit", "e"));
        int replaced = Microcompact.sweep(messages, 1);
        assertEquals(3, replaced);
        assertEquals("e", messages.get(4).content);
        assertEquals("d", messages.get(3).content);
        assertEquals(Microcompact.PLACEHOLDER, messages.get(2).content);
    }

    /** 空结果/空历史：零替换不炸 */
    @Test public void emptyInputsAreNoOps() {
        assertEquals(0, Microcompact.sweep(new ArrayList<Message>(), 5));
        List<Message> messages = new ArrayList<Message>();
        messages.add(tool("Read", null));
        assertEquals(0, Microcompact.sweep(messages, 0));
        assertNull(messages.get(0).content);   // 空内容不写占位
    }
}
