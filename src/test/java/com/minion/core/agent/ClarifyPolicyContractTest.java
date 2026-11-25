package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.config.FeatureFlags;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** 三批 D1 澄清策略契约：conservative 默认与旧文本逐字一致；proactive 仅替换规则 2 段；
 *  规则 3/7 两态不变；replica 开/关四组合拼接快照一致。 */
public class ClarifyPolicyContractTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 旧规则 2 原文（特征化锚点，逐字） */
    private static final String RULE2_LEGACY =
            "2.仅当缺失信息确实阻塞执行、存在重大范围分歧或缺少真实授权时，调用 AskUserQuestion 等待用户回答；"
          + "已授权范围内可采用合理默认值并简短说明，不因非关键歧义反复停工。提问逐一进行，每次一个问题，选择题优先，最后留其他补充。\n";

    private static final String RULE3 =
            "3.用户或已加载技能明确要求审查确认、破坏性操作、重大范围变更仍须真实用户批准；"
          + "内部工作计划和进度登记不等于人类审批，不要求每份内部计划都确认，不得伪造用户或技能要求的批准。\n";

    private static final String RULE7 = "7.涉及删除/覆盖等破坏性操作时，等待用户确认（系统会拦截）。\n";

    private static SystemPromptBuilder builder() {
        return new SystemPromptBuilder(null, null, null);
    }

    @Test public void conservativeDefaultIsVerbatimLegacy() {
        String unset = builder().buildStable();
        String explicit = builder().setClarifyPolicy("conservative").buildStable();
        String nullPolicy = builder().setClarifyPolicy(null).buildStable();
        String emptyPolicy = builder().setClarifyPolicy("  ").buildStable();
        assertEquals("默认必须与显式 conservative 逐字一致", unset, explicit);
        assertEquals(unset, nullPolicy);
        assertEquals(unset, emptyPolicy);
        assertTrue("conservative 必须含旧规则 2 原文", unset.contains(RULE2_LEGACY));
        assertFalse("conservative 不得出现 proactive 文本", unset.contains("主动澄清（首个副作用前）"));
    }

    @Test public void proactiveReplacesOnlyRule2AndKeepsRules3And7() {
        String conservative = builder().buildStable();
        String proactive = builder().setClarifyPolicy("proactive").buildStable();
        assertFalse("proactive 不再含旧规则 2 原句", proactive.contains("仅当缺失信息确实阻塞执行"));
        assertTrue(proactive.contains("主动澄清（首个副作用前）"));
        assertTrue(proactive.contains("一次最多 3 个相关问题"));
        assertTrue(proactive.contains("推荐项放第一并标注"));
        assertTrue(proactive.contains("本会话已答过的问题不重复问"));
        assertTrue(proactive.contains("无人可问"));
        assertTrue(proactive.contains("只读探索能查到答案就不问"));
        assertTrue(conservative.contains(RULE3));
        assertTrue(proactive.contains(RULE3));
        assertTrue(conservative.contains(RULE7));
        assertTrue(proactive.contains(RULE7));
    }

    /** replica 开/关 × conservative/proactive 四组合：除规则 2 段外逐字一致 */
    @Test public void replicaOnOffSnapshotsDifferOnlyInRule2() {
        for (boolean replica : new boolean[]{false, true}) {
            String conservative = builder().setReplicaLayout(replica).buildStable();
            String proactive = builder().setReplicaLayout(replica).setClarifyPolicy("proactive").buildStable();
            assertTrue(conservative.contains(RULE2_LEGACY));
            assertTrue(conservative.contains(RULE3));
            assertTrue(conservative.contains(RULE7));
            assertTrue(proactive.contains(RULE3));
            assertTrue(proactive.contains(RULE7));
            assertEquals("replica=" + replica + " 时 replica 段 presence 一致",
                    conservative.contains(ReplicaPromptTemplate.replicaSections()),
                    proactive.contains(ReplicaPromptTemplate.replicaSections()));
            // 把 proactive 的规则 2 段换回保守原文 → 与 conservative 全文逐字一致
            String proactiveRule2 = proactive.substring(proactive.indexOf("2.主动澄清"), proactive.indexOf(RULE3));
            assertEquals("replica=" + replica + " 除规则 2 外必须逐字一致",
                    conservative, proactive.replace(proactiveRule2, RULE2_LEGACY));
        }
    }

    @Test public void configDefaultIsConservativeAndFlagRegistered() throws Exception {
        Config config = Config.load(tmp.newFolder("cfg-clarify").toPath());
        assertEquals("conservative", config.intentClarifyPolicy());
        assertTrue(FeatureFlags.known("intent.clarify.policy"));
        config.set("intent.clarify.policy", "proactive");
        assertEquals("proactive", config.intentClarifyPolicy());
    }
}