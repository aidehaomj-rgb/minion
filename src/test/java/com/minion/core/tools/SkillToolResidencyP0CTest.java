package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.RecordingUi;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.context.SkillResidency;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.skills.Skill;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class SkillToolResidencyP0CTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private AgentLoop loop;
    private ContextManager cm;
    private Skill current;

    @Before public void setup() {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        cm = new ContextManager(1000000, .9, 12, llm, 0);
        cm.setTaskScope("current", 2, "当前要求", "当前要求");
        loop = new AgentLoop(llm, new ToolRegistry(), new SystemPromptBuilder(tmp.getRoot() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)), new RecordingUi(), cm,
                new Workspace(tmp.getRoot().getPath()), Session.create(tmp.getRoot().getPath(), "parent-model"));
        current = new Skill("review", "当前技能", "当前版本技能规则", "skills/review/SKILL.md");
        loop.setAllSkills(Collections.singletonList(current));
    }
    private Message loaded(Skill skill, String task, long revision) {
        Message m = Message.skill("<skill name=\"" + skill.name + "\">\n" + skill.instructions + "\n</skill>",
                skill.name, skill.file, SkillResidency.contentVersion(skill.instructions));
        m.taskId = task; m.taskRevision = revision; return m;
    }
    private ToolResult load() {
        JsonObject args = new JsonObject(); args.addProperty("name", current.name);
        return new SkillTool(loop).execute(args);
    }

    @Test public void obsoleteTaskAndRevisionDoNotSuppressRequiredReload() {
        loop.messages().add(loaded(current, "old", 1));
        loop.messages().add(loaded(current, "current", 1));
        ToolResult result = load();
        assertTrue(result.ok); assertTrue(result.output.contains("已加载技能"));
        assertFalse(result.output.contains("已在上下文中"));
        assertEquals(2, loop.messages().size()); // queued, not mutating old immutable skill events
    }

    @Test public void sameNameChangedBodyOrExplicitUnloadCanBeLoadedAgain() {
        Skill previous = new Skill("review", "旧技能", "旧正文不能冒充新正文", current.file);
        Message old = loaded(previous, "current", 2); loop.messages().add(old);
        assertTrue(load().output.contains("已加载技能"));
        assertEquals(previous.instructions, old.content.substring(old.content.indexOf('\n') + 1, old.content.indexOf("\n</skill>")));
        loop.messages().clear(); loop.messages().add(loaded(current, "current", 2));
        cm.setActiveSkills(Collections.<String>emptyList());
        assertTrue(load().output.contains("已加载技能"));
    }

    @Test public void onlyActualResidentSameVersionReportsAlreadyLoaded() {
        loop.messages().add(loaded(current, "current", 2));
        ToolResult result = load(); assertTrue(result.ok); assertTrue(result.output.contains("已在上下文中"));
        assertEquals(1, loop.messages().size());
    }

    @Test public void quotedSkillTagInRealUserTextDoesNotCountAsLoadedSkill() {
        loop.messages().add(Message.realUser("<skill name=\"review\">用户引文，不是已加载正文</skill>"));
        assertTrue(load().output.contains("已加载技能"));
    }

    @Test public void oversizeBodyFailsBeforeQueueingRatherThanClaimingSuccessfulResidency() {
        char[] chars = new char[SkillResidency.MAX_RESIDENT_CHARS + 1]; Arrays.fill(chars, 'X');
        current = new Skill("review", "超大技能", new String(chars), "large/SKILL.md");
        loop.setAllSkills(Collections.singletonList(current));
        ToolResult result = load(); assertFalse(result.ok); assertTrue(result.output.contains("24000"));
        assertTrue(result.output.contains("large/SKILL.md")); assertEquals(0, loop.messages().size());
    }
}
