package com.minion.core.agent;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolReadOnlyPolicyTest {
    private static JsonObject action(String action) {
        JsonObject args=new JsonObject();
        args.addProperty("action",action);
        return args;
    }

    @Test
    public void networkAndFileReadsAreNotMutationEvidence() {
        assertTrue(ToolExecutionGuard.readOnly("WebFetch",new JsonObject()));
        assertTrue(ToolExecutionGuard.readOnly("Files",action("search")));
        assertTrue(ToolExecutionGuard.readOnly("Files",action("read_chunk")));
        assertTrue(ToolExecutionGuard.readOnly("BrowserAction",action("text")));
        assertTrue(ToolExecutionGuard.readOnly("BrowserDebug",action("network")));
    }

    @Test
    public void writesClicksAndUnknownActionsRemainSideEffecting() {
        assertFalse(ToolExecutionGuard.readOnly("Files",action("delete")));
        assertFalse(ToolExecutionGuard.readOnly("BrowserAction",action("click")));
        assertFalse(ToolExecutionGuard.readOnly("BrowserAction",action("upload")));
        assertFalse(ToolExecutionGuard.readOnly("UnknownTool",action("read")));
    }
}
