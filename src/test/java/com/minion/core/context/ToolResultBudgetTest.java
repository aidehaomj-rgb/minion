package com.minion.core.context;

import com.minion.core.llm.Message;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** §5.6 工具结果预算验收：保护窗原样、旧超限折叠、原文不动、开关关闭零影响 */
public class ToolResultBudgetTest {

    private static ContextManager cm() {
        return new ContextManager(131072, 0.8, 10, null, 0);
    }

    private static List<Message> history() {
        List<Message> messages = new ArrayList<Message>();
        messages.add(Message.user("开始"));
        for (int i = 0; i < 15; i++) {
            Message assistant = Message.assistant("步骤 " + i);
            assistant.toolCalls = new ArrayList<>();
            messages.add(assistant);
            Message tool = Message.toolResult("id" + i, "Read", "内容" + i);
            messages.add(tool);
        }
        messages.add(Message.user("继续"));
        return messages;
    }

    @Test public void budgetFoldsOldOversizedToolResultsOnly() {
        ContextManager cm = cm();
        cm.setToolResultBudget(true, 2000);
        List<Message> source = history();
        // 手工放超限内容：第 2 个工具结果（index 4 = tool#1，保护窗 12 条之外）3000 字符
        StringBuilder big = new StringBuilder("BIG:");
        for (int i = 0; i < 3000; i++) big.append('x');
        assertEquals(Message.Role.TOOL, source.get(4).role);
        source.get(4).content = big.toString();
        String original = source.get(4).content;
        List<Message> projected = cm.prepareForRequest(new ArrayList<>(source));
        // 请求副本中被折叠
        boolean folded = false;
        for (Message m : projected)
            if (m.role == Message.Role.TOOL && m.content.contains("已折叠")) folded = true;
        assertTrue("旧超限结果应折叠", folded);
        // 会话原文不动
        assertEquals(original, source.get(4).content);
        // 保护窗内（最后 12 条）即使超限也不折叠
        StringBuilder tail = new StringBuilder("TAIL:");
        for (int i = 0; i < 3000; i++) tail.append('y');
        int lastTool = -1;
        for (int i = source.size() - 1; i >= 0; i--)
            if (source.get(i).role == Message.Role.TOOL) { lastTool = i; break; }
        source.get(lastTool).content = tail.toString();
        List<Message> again = cm.prepareForRequest(new ArrayList<>(source));
        for (Message m : again)
            if (m.role == Message.Role.TOOL && m.content.startsWith("TAIL:"))
                assertTrue("保护窗结果不折叠", m.content.length() == tail.length());
    }

    /** 开关关闭：零影响（逐字节同现状） */
    @Test public void disabledBudgetKeepsEverything() {
        ContextManager cm = cm();   // 默认未开启
        List<Message> source = history();
        StringBuilder big = new StringBuilder("BIG:");
        for (int i = 0; i < 3000; i++) big.append('x');
        source.get(4).content = big.toString();
        List<Message> projected = cm.prepareForRequest(new ArrayList<>(source));
        for (Message m : projected)
            if (m.role == Message.Role.TOOL && m.content.startsWith("BIG:"))
                assertEquals(big.length(), m.content.length());
    }
}
