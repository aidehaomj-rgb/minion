package com.minion.gui.chat;

import org.junit.Test;
import static org.junit.Assert.*;

/** 分页覆盖全部字符，包括恰好跨界的 emoji；不启动 JavaFX。 */
public class MessageTextPaneTest {
    @Test public void everyPageReassemblesFullText() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 100000; i++) source.append(i % 30 == 0 ? '\n' : '数');
        String text = source.toString();
        StringBuilder actual = new StringBuilder();
        for (int page = 0; page < MessageTextPane.pageCount(text); page++) {
            String part = MessageTextPane.pageText(text, page);
            assertTrue(part.length() <= MessageTextPane.PAGE_CHARS + 1);
            actual.append(part);
        }
        assertEquals(text, actual.toString());
    }
    @Test public void pageBoundaryNeverSplitsSurrogatePair() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < MessageTextPane.PAGE_CHARS - 1; i++) source.append('a');
        source.append("😀结束");
        String first = MessageTextPane.pageText(source.toString(), 0);
        String second = MessageTextPane.pageText(source.toString(), 1);
        assertFalse(Character.isHighSurrogate(first.charAt(first.length()-1)));
        assertTrue(second.startsWith("😀"));
        assertEquals(source.toString(), first + second);
    }
    @Test public void nullEmptyAndExactBoundary() {
        assertEquals(1, MessageTextPane.pageCount(null));
        assertEquals("", MessageTextPane.pageText(null, 0));
        char[] chars = new char[MessageTextPane.PAGE_CHARS];
        java.util.Arrays.fill(chars, 'a');
        assertEquals(1, MessageTextPane.pageCount(new String(chars)));
        assertEquals(new String(chars), MessageTextPane.pageText(new String(chars), 5));
    }
}
