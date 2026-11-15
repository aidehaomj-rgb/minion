package com.minion.core.agent;

import com.minion.core.skills.Skill;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class SystemPromptBuilderSeparationTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void oldBuildComposesStableThenDynamicAndKeepsConservativeDefault() {
        SystemPromptBuilder builder=new SystemPromptBuilder(null,"project","tmp",true,"skills");
        assertEquals(builder.buildStable()+builder.buildDynamic(null),builder.build(null));
        assertTrue(builder.build(null).contains("每轮最多生成一个工具调用"));
        assertTrue(builder.build(null).contains("不超过1000字符"));
        assertTrue(builder.build(null).contains("输出内容为空"));assertEquals("tmp",builder.tempDir());
    }
    @Test public void projectTaskAndDiscoveryChangesCannotChangeStablePrefix() throws Exception {
        Path project=temp.newFile("project.md").toPath();Files.write(project,"old project".getBytes(StandardCharsets.UTF_8));
        SystemPromptBuilder builder=new SystemPromptBuilder(project.toString(),"work","tmp");String stable=builder.buildStable();
        String before=builder.buildDynamic(null,"revision=1","Read");
        Files.write(project,"new project".getBytes(StandardCharsets.UTF_8));
        String after=builder.buildDynamic(null,"revision=2","Excel");
        assertEquals(stable,builder.buildStable());assertNotEquals(before,after);
        assertTrue(after.contains("new project"));assertTrue(after.contains("revision=2"));assertFalse(stable.contains("old project"));
        assertEquals(stable,new SystemPromptBuilder(null,"another-project","other-temp").buildStable());
    }
    @Test public void skillOrderIsDeterministicWithoutMutatingCatalogue() {
        Skill b=new Skill("b","B","body","/b"),a=new Skill("a","A","body","/a");
        List<Skill> skills=new ArrayList<Skill>(Arrays.asList(b,a));SystemPromptBuilder builder=new SystemPromptBuilder(null);
        assertEquals(builder.buildDynamic(skills),builder.buildDynamic(Arrays.asList(a,b)));
        assertSame(b,skills.get(0));assertFalse(builder.buildStable().contains("/a"));
    }
    @Test public void verifiedPolicyChangesDispatchGuidanceButNeverAuthorizesWritesOrApprovals() {
        SystemPromptBuilder builder=new SystemPromptBuilder(null);
        String fast=builder.buildStable(ToolSchedulingPolicyTest.parallel(4));
        assertTrue(fast.contains("每轮最多生成4个"));assertFalse(fast.contains("每轮最多生成一个工具调用"));
        assertTrue(fast.contains("用户或已加载技能明确要求审查确认"));assertTrue(fast.contains("仍须真实用户批准"));
        assertTrue(fast.contains("内部工作计划和进度登记不等于人类审批"));assertTrue(fast.contains("破坏性操作"));
        assertTrue(fast.contains("写入、Shell、用户确认、共享浏览器动作不同时派发"));
    }
    @Test public void noUnsupportedProviderCachingHintsAreAdded() {
        SystemPromptBuilder builder=new SystemPromptBuilder(null);
        assertFalse(builder.build(null).contains("cacheControl"));assertFalse(builder.build(null).contains("cache_control"));
        assertEquals("",builder.buildDynamic(Collections.<Skill>emptyList()));
    }
}
