package com.minion.core.agent;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class InstructionContractTest {
    @Test public void explicitReadOnlyCorrectionDoesNotDemandObsoleteMutation(){CompletionContract c=CompletionContract.from("修改代码并生成程序",Arrays.asList("不要修改了，只分析现有文件"));assertTrue(c.action);assertFalse(c.mutation);}
    @Test public void explanationSupersedesDevelopmentButAdditiveConstraintDoesNot(){assertFalse(CompletionContract.from("开发程序",Arrays.asList("这次只回答原因")).action);assertTrue(CompletionContract.from("开发程序",Arrays.asList("另外保留原始文件")).mutation);}
    @Test public void laterAuthorizedWorkIsNotIgnoredBecauseOriginalWasAQuestion(){CompletionContract c=CompletionContract.from("这个功能是什么？",Arrays.asList("请实现这个功能"));assertTrue(c.action);assertTrue(c.mutation);}
    @Test public void analysisThenFixStillNeedsRealMutation(){assertTrue(CompletionContract.from("解释错误",Arrays.asList("先只分析原因，然后修复代码")).mutation);}
    @Test public void changingFieldValueIsNotTreatedAsReplacingWholeTask(){assertFalse(CompletionContract.explicitScopeReplacement("把金额改为数字"));}
    @Test public void ledgerKeepsOriginalAndExplicitReplacementSource(){TaskState s=new TaskState();s.begin("开发程序",true);s.addGuidance("这次只回答原因");assertEquals("开发程序",s.objective);assertEquals("replace",s.instructionChanges.get(1).kind);assertEquals(s.latestRequirement(),s.instructionChanges.get(1).text);assertEquals(2,s.instructionChanges.get(1).revision);}
}
