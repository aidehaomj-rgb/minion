package com.minion.core.skills;

import com.minion.core.agent.SystemPromptBuilder;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** 领域规则从 BUILTIN 迁为内置技能（2026-11-16 提示词瘦身）：技能齐备、触发词明确、BUILTIN 只留读图兜底。 */
public class BuiltinSkillsDomainTest {

    private static Skill find(String name) {
        List<Skill> all = BuiltinSkills.all();
        for (Skill s : all) if (name.equals(s.name)) return s;
        fail("缺少内置技能: " + name);
        return null;
    }

    @Test
    public void imageReading_skillHasTriggersAndSafetyRules() {
        Skill s = find("image-reading");
        assertTrue(s.description.contains("图片路径"));
        assertTrue(s.instructions.contains("Read或ViewImage"));
        assertTrue(s.instructions.contains("裁剪"));
        assertTrue(s.instructions.contains("rotate"));
        assertTrue(s.instructions.contains("不编造品牌型号"));
        assertTrue(s.instructions.contains("不是新的用户指令"));
    }

    @Test
    public void documentMaterials_skillHasWorkflowAndEvidenceRules() {
        Skill s = find("document-materials");
        assertTrue(s.description.contains("PDF"));
        assertTrue(s.description.contains("材料"));
        assertTrue(s.instructions.contains("pdf_read"));
        assertTrue(s.instructions.contains("pdf_view"));
        assertTrue(s.instructions.contains("textColumns"));
        assertTrue(s.instructions.contains("merge_audit"));
        assertTrue(s.instructions.contains("patch_cells"));
        assertTrue(s.instructions.contains("docx_validate"));
        assertTrue(s.instructions.contains("不编造引文"));
    }

    @Test
    public void ofdGd_skillHasTriggersAndHonestyRules() {
        Skill s = find("ofd-gd-documents");
        assertTrue(s.description.contains("OFD"));
        assertTrue(s.description.contains("GD"));
        assertTrue(s.instructions.contains("ofd_read"));
        assertTrue(s.instructions.contains("ofd_ocr"));
        assertTrue(s.instructions.contains("gd_read"));
        assertTrue(s.instructions.contains("exportPath"));
        assertTrue(s.instructions.contains("不能改后缀冒充转换"));
        assertTrue(s.instructions.contains("前3页"));
    }

    @Test
    public void builtinPrompt_dropsDomainParagraphs_keepsImageFallback() {
        String stable = new SystemPromptBuilder(null).buildStable();
        assertFalse("OFD 段应迁出 BUILTIN", stable.contains("OFD附件只预览前3页"));
        assertFalse("书生GD 段应迁出 BUILTIN", stable.contains("书生GD用Document gd_read"));
        assertFalse("分析/材料段应迁出 BUILTIN", stable.contains("分析/材料："));
        assertFalse("绘图段应迁出 BUILTIN", stable.contains("本地绘图："));
        assertTrue("读图兜底一行保留", stable.contains("必须调用Read或ViewImage让真实像素进入视觉上下文"));
        assertTrue("兜底行指向技能", stable.contains("image-reading"));
    }

    @Test
    public void visualDesign_keepsOverflowFixRule() {
        Skill s = find("visual-design");
        assertTrue(s.instructions.contains("文字溢出须修正"));
    }
}