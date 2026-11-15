package com.minion.core.agent;

import com.minion.core.llm.*;
import com.minion.core.tools.ToolResult;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ToolSchedulingPolicyTest {
    @Test public void verifiedSingleCallCanTrySmallReadBatchButDispatchRemainsSerial() {
        ToolSchedulingPolicy trial=ToolSchedulingPolicy.trialReadBatch(profile(),profile().fingerprint,100);
        assertEquals(2,trial.maxGeneratedCalls);assertEquals(1,trial.maxParallelReads);
        assertTrue(trial.promptRules().contains("本地先串行"));
        assertEquals(1,ToolSchedulingPolicy.trialReadBatch(profile(),"wrong",100).maxGeneratedCalls);
        assertEquals(1,ToolSchedulingPolicy.trialReadBatch(profile(),profile().fingerprint,1000).maxGeneratedCalls);
    }
    @Test public void writeGrowthRequiresObservedPayloadAndFiniteReservedOutputBudget() {
        ToolSchedulingPolicy p=ToolSchedulingPolicy.conservative();
        assertEquals(1000,p.afterSuccessfulWrite(999,8192).writeChunkCharacters);
        assertEquals(1000,p.afterSuccessfulWrite(1000,2048).writeChunkCharacters);
        p=p.afterSuccessfulWrite(1000,8192);assertEquals(2000,p.writeChunkCharacters);
        p=p.afterSuccessfulWrite(2000,8192);assertEquals(3840,p.writeChunkCharacters);
        assertEquals(1,p.maxParallelReads);
    }
    static ModelCapabilityProfile profile() {
        EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State> states =
                new EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State>(ModelCapabilityProfile.Capability.class);
        states.put(ModelCapabilityProfile.Capability.TOOLS, ModelCapabilityProfile.State.SUPPORTED);
        return new ModelCapabilityProfile("current-model-and-mode", "probe", 10, 1000, 0, states);
    }
    static ToolCall call(String id, String name, String args) {
        ToolCall call = new ToolCall(); call.id = id; call.name = name; call.arguments = args; return call;
    }
    static ToolSchedulingPolicy parallel(int width) {
        List<ToolCall> calls=new ArrayList<ToolCall>(reads());
        List<ToolResult> results=new ArrayList<ToolResult>(successes());
        if(width>2){calls.add(call("3","Read","{\"path\":\"c\"}"));calls.add(call("4","Read","{\"path\":\"d\"}"));
            results.add(ToolResult.success("c"));results.add(ToolResult.success("d"));}
        return observed(profile(), "tool_calls", calls, results, width, 100);
    }
    private static List<ToolCall> reads() {
        return Arrays.asList(call("1","Read","{\"path\":\"a\"}"),call("2","Read","{\"path\":\"b\"}"));
    }
    private static List<ToolResult> successes() {
        return Arrays.asList(ToolResult.success("a"),ToolResult.success("b"));
    }
    private static ToolSchedulingPolicy observed(ModelCapabilityProfile p, String finish,
            List<ToolCall> calls, List<ToolResult> results, int concurrency, long now) {
        return ToolSchedulingPolicy.observeSuccessfulBatch(p,"current-model-and-mode",finish,calls,results,concurrency,now);
    }
    @Test public void singleToolSupportAloneCannotEnableBatching() {
        assertEquals(1,ToolSchedulingPolicy.fromEvidence(profile(),profile().fingerprint,false,false,8192,4,100).maxParallelReads);
        assertEquals(1000,ToolSchedulingPolicy.conservative().writeChunkCharacters);
        assertEquals(1,ToolSchedulingPolicy.fromEvidence(null,"some-famous-model",true,true,8192,4,100).maxParallelReads);
    }
    @Test public void actualSuccessEnablesOnlyTwoToFourReadsNotLargeWritePayloads() {
        assertEquals(2,parallel(2).maxParallelReads);
        assertEquals(2,observed(profile(),"tool_calls",reads(),successes(),4,100).maxParallelReads);
        assertEquals(4,parallel(100).maxParallelReads);
        assertEquals(1,parallel(0).maxParallelReads);
        assertEquals(1000,parallel(4).writeChunkCharacters);
    }
    @Test public void staleInvalidatedUnknownAndWrongModelFallBack() {
        assertEquals(1,observed(profile(),"tool_calls",reads(),successes(),4,1000).maxParallelReads);
        ModelCapabilityProfile invalid = new ModelCapabilityProfile(profile().fingerprint,"probe",10,1000,20,null);
        assertEquals(1,observed(invalid,"tool_calls",reads(),successes(),4,100).maxParallelReads);
        assertEquals(1,observed(ModelCapabilityProfile.unknown(profile().fingerprint),"tool_calls",reads(),successes(),4,100).maxParallelReads);
        assertEquals(1,ToolSchedulingPolicy.fromEvidence(profile(),"other-model",true,true,8192,4,100).maxParallelReads);
    }
    @Test public void incompleteFailedAndDuplicateResponsesAreNotEvidence() {
        assertEquals(1,observed(profile(),"length",reads(),successes(),4,100).maxParallelReads);
        assertEquals(1,observed(profile(),"stop",reads(),successes(),4,100).maxParallelReads);
        assertEquals(1,observed(profile(),"tool_calls",reads(),Collections.singletonList(ToolResult.success("x")),4,100).maxParallelReads);
        assertEquals(1,observed(profile(),"tool_calls",reads(),Arrays.asList(ToolResult.success("a"),ToolResult.error("bad")),4,100).maxParallelReads);
        List<ToolCall> calls=reads();calls.get(1).id="1";
        assertEquals(1,observed(profile(),"tool_calls",calls,successes(),4,100).maxParallelReads);
        calls=reads();calls.get(1).arguments="{\"path\":";
        assertEquals(1,observed(profile(),"tool_calls",calls,successes(),4,100).maxParallelReads);
    }
    @Test public void mutationsAndSharedBrowsersDoNotProveParallelReadSupport() {
        for(String name:Arrays.asList("Write","Bash","BrowserAction","BrowserScreenshot","Skill","AskUserQuestion")) {
            List<ToolCall> calls=Arrays.asList(reads().get(0),call("2",name,"{}"));
            assertEquals(name,1,observed(profile(),"tool_calls",calls,successes(),4,100).maxParallelReads);
        }
    }
    @Test public void separatelyVerifiedWriteBudgetIsBoundedAndConservativeAfterFailure() {
        ToolSchedulingPolicy policy=ToolSchedulingPolicy.fromEvidence(profile(),profile().fingerprint,true,true,8192,4,100);
        assertTrue(policy.writeChunkCharacters>1000);
        assertTrue(policy.writeChunkCharacters<8192);
        assertEquals(1000,ToolSchedulingPolicy.fromEvidence(profile(),profile().fingerprint,true,true,1024,4,100).writeChunkCharacters);
        assertEquals(16000,ToolSchedulingPolicy.fromEvidence(profile(),profile().fingerprint,true,true,Integer.MAX_VALUE,4,100).writeChunkCharacters);
        assertEquals(1,ToolSchedulingPolicy.conservative().maxParallelReads);
    }
}
