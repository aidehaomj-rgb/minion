package com.minion.core.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ToolArgumentsTest {
    @Test public void repairsFenceUnquotedKeyAndTrailingComma() {
        assertEquals("a.txt", ToolArguments.parseObject("```json\n{path:\"a.txt\",}\n```").get("path").getAsString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonObjectGarbage() { ToolArguments.parseObject("not-json"); }

    @Test public void repairsNestedArgumentsMissingBrace() {
        com.google.gson.JsonObject o = ToolArguments.parseObject("{\"arguments\":{\"question\":\"hello?\"}");
        assertEquals("hello?", AskUserQuestionTool.normalize(o).question);
    }
    @Test public void repairsNestedArgumentsMissingQuote() {
        assertEquals("hello", ToolArguments.parseObject("{\"arguments\":{\"question\":\"hello")
                .getAsJsonObject("arguments").get("question").getAsString());
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsOtherTruncation() {
        ToolArguments.parseObject("{\"input\":{\"question\":\"hello");
    }
    @Test public void rejectsMismatchedBracketRepair() {
        org.junit.Assert.assertNull(ToolArguments.closeUnterminated("{\"arguments\":[}"));
    }
}
