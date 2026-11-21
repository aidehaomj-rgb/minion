package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

/** 附录 D.2/D.5 提示词模板验收：replica 四段结构/段序/关键内容点/开关关闭零影响 */
public class ReplicaPromptTemplateTest {

    @Test public void replicaSectionsHaveFourLabeledBlocks() {
        String s = ReplicaPromptTemplate.replicaSections();
        assertTrue(s.contains("[工作方式]"));
        assertTrue(s.contains("[工具与协作]"));
        assertTrue(s.contains("[安全与数据]"));
        assertTrue(s.contains("[完成与交付]"));
        // 段序：工作方式 → 工具 → 安全 → 完成
        int a = s.indexOf("[工作方式]");
        int b = s.indexOf("[工具与协作]");
        int c = s.indexOf("[安全与数据]");
        int d = s.indexOf("[完成与交付]");
        assertTrue(a < b && b < c && c < d);
    }

    /** 关键内容点：提示注入防御/无互联网/证据交付/中文交流 */
    @Test public void keyContentPointsPresent() {
        String s = ReplicaPromptTemplate.replicaSections();
        assertTrue(s.contains("不自动升级为用户当前指令"));   // 提示注入防御
        assertTrue(s.contains("无互联网账户能力"));           // 离线约束
        assertTrue(s.contains("实际证据"));                   // 证据交付
        assertTrue(s.contains("中文交流"));                   // 语言
        assertTrue(s.contains("不覆盖或删除并非自己创建"));   // 数据保护
    }

    /** 开关关闭：SystemPromptBuilder 默认不含 replica 段 */
    @Test public void disabledByDefault() {
        SystemPromptBuilder builder = new SystemPromptBuilder("p.md");
        String stable = builder.buildStable();
        assertFalse(stable.contains("[工作方式]"));
        assertFalse(stable.contains("[完成与交付]"));
        // 开启后追加
        String enabled = builder.setReplicaLayout(true).buildStable();
        assertTrue(enabled.contains("[工作方式]"));
        assertTrue(enabled.contains("[完成与交付]"));
        // 既有 BUILTIN 保留
        assertTrue(enabled.contains("你是运行在用户电脑里的代码开发助手"));
    }

    /** D.5 压缩交接模板要点 */
    @Test public void compressionHandoffSections() {
        String s = ReplicaPromptTemplate.compressionHandoffSections();
        assertTrue(s.contains("用户意图"));
        assertTrue(s.contains("已完成"));
        assertTrue(s.contains("未开始"));
        assertTrue(s.contains("原话"));
    }
}
