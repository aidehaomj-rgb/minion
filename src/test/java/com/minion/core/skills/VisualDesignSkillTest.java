package com.minion.core.skills;
import org.junit.Test;
import static org.junit.Assert.*;
public class VisualDesignSkillTest {
    @Test public void builtinDistinguishesLocalGraphicsFromImageModelsAndRequiresHonestReview() {
        Skill s=BuiltinSkills.all().stream().filter(x->"visual-design".equals(x.name)).findFirst().get();
        assertTrue(s.instructions.contains("Artwork"));assertTrue(s.instructions.contains("Matplotlib"));
        assertTrue(s.instructions.contains("不自动切换"));assertTrue(s.instructions.contains("未视觉核验"));
        assertTrue(s.instructions.contains("真实数据"));assertTrue(s.instructions.contains("不是专用图像生成模型"));
    }
}
