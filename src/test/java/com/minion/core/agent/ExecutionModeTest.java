package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExecutionModeTest {
    @Test
    public void defaultAndUnknownModesRemainNormal() {
        assertEquals(ExecutionMode.NORMAL, ExecutionMode.parse(null));
        assertEquals(ExecutionMode.NORMAL, ExecutionMode.parse(""));
        assertEquals(ExecutionMode.NORMAL, ExecutionMode.parse("unknown"));
        assertFalse(ExecutionMode.NORMAL.readOnlyTurn());
        assertFalse(ExecutionMode.NORMAL.requiresCompletionReview());
    }

    @Test
    public void onlyPlanAndReviewAreReadOnlyAndOnlyGoalReviewRequireVerification() {
        assertTrue(ExecutionMode.PLAN.readOnlyTurn());
        assertTrue(ExecutionMode.REVIEW.readOnlyTurn());
        assertFalse(ExecutionMode.GOAL.readOnlyTurn());
        assertTrue(ExecutionMode.GOAL.requiresCompletionReview());
        assertTrue(ExecutionMode.REVIEW.requiresCompletionReview());
        assertFalse(ExecutionMode.PLAN.requiresCompletionReview());
    }
}
