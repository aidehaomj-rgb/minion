package com.minion.core.agent;
import com.minion.core.llm.LlmException;
import org.junit.Test;
import static org.junit.Assert.*;
public class TurnSemanticsTest {
    @Test public void rootAndChildUseSameVisibleOutputBoundary(){
        assertFalse(TurnSemantics.visible(" \n\u200B\uFEFF"));assertFalse(TurnSemantics.visible(null));
        assertTrue(TurnSemantics.noOutput(" ","\u200B"));
        assertFalse(TurnSemantics.noOutput("部分正文",null));assertFalse(TurnSemantics.noOutput(null,"部分思考"));
        assertEquals(TurnSemantics.visible("正文"),AgentLoop.hasVisibleText("正文"));
    }
    @Test public void permanentFailuresNeverBecomeTransient(){
        assertFalse(TurnSemantics.transientFailure(LlmException.of(401,"bad key")));
        assertFalse(TurnSemantics.transientFailure(LlmException.of(400,"bad arguments")));
        assertFalse(TurnSemantics.transientFailure(new LlmException(LlmException.Type.NETWORK,"DNS",false)));
    }
    @Test public void sharedTransientClassificationPreservesExistingRetryRules(){
        assertTrue(TurnSemantics.transientFailure(LlmException.of(429,"busy")));
        assertTrue(TurnSemantics.transientFailure(LlmException.of(500,"busy")));
        assertTrue(TurnSemantics.transientFailure(LlmException.of(502,"busy")));
        assertTrue(TurnSemantics.transientFailure(new LlmException(LlmException.Type.NETWORK,"reset",true)));
    }
}
