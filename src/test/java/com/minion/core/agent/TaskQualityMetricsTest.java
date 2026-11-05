package com.minion.core.agent;
import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskQualityMetricsTest {
    @Test public void unreviewedRunsCannotInventQualityOrFalseCompletion(){JsonObject r=new JsonObject();r.addProperty("status","completed");TaskEvaluation.addManualMetrics(r);assertEquals("not_reviewed",r.getAsJsonObject("qualityMetrics").get("status").getAsString());assertFalse(r.has("falseCompletion"));}
    @Test public void actualManualRejectionOfCompletedTaskCountsAsFalseCompletion(){JsonObject r=report(10,2,1,1,false);TaskEvaluation.addManualMetrics(r);assertTrue(r.get("falseCompletion").getAsBoolean());assertEquals(0.8,r.getAsJsonObject("qualityMetrics").get("coverage").getAsDouble(),0.0001);assertTrue(r.getAsJsonObject("qualityMetrics").get("businessAccuracy").getAsString().startsWith("unknown"));}
    @Test public void zeroExpectedItemsDoesNotBecomePerfectAccuracy(){JsonObject r=report(0,0,0,0,true);TaskEvaluation.addManualMetrics(r);assertFalse(r.getAsJsonObject("qualityMetrics").has("coverage"));assertFalse(r.get("falseCompletion").getAsBoolean());}
    @Test public void invalidReviewDoesNotProduceQualityRatios(){JsonObject r=report(2,3,0,0,true);TaskEvaluation.addManualMetrics(r);assertEquals("invalid_review",r.getAsJsonObject("qualityMetrics").get("status").getAsString());assertFalse(r.has("falseCompletion"));}
    @Test public void unlabelledEarlyStopsAndMemoryQualityStayUnknown(){JsonObject r=report(10,0,0,0,true);TaskEvaluation.addManualMetrics(r);assertEquals("unknown_not_labelled",r.getAsJsonObject("qualityMetrics").get("prematureStopStatus").getAsString());r.getAsJsonObject("manualBusinessReview").addProperty("latestInstructionFollowed",true);r.getAsJsonObject("manualBusinessReview").addProperty("prematureStop",false);TaskEvaluation.addManualMetrics(r);assertTrue(r.getAsJsonObject("qualityMetrics").get("latestInstructionFollowed").getAsBoolean());assertFalse(r.getAsJsonObject("qualityMetrics").get("prematureStop").getAsBoolean());}
    private JsonObject report(int expected,int omitted,int duplicate,int incorrect,boolean accepted){JsonObject r=new JsonObject();r.addProperty("status","completed");JsonObject m=new JsonObject();m.addProperty("expectedItems",expected);m.addProperty("omittedItems",omitted);m.addProperty("duplicateItems",duplicate);m.addProperty("incorrectItems",incorrect);m.addProperty("accepted",accepted);r.add("manualBusinessReview",m);return r;}
}
