package com.minion.core.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ToolArgumentsTest {
    @Test public void repairsFenceUnquotedKeyAndTrailingComma() {
        assertEquals("a.txt", ToolArguments.parseObject("```json\n{path:\"a.txt\",}\n```").get("path").getAsString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonObjectGarbage() { ToolArguments.parseObject("not-json"); }
}
