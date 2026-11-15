package com.minion.core.context;

import com.minion.core.llm.Message;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class StructuredCheckpointTest {
    @Test public void requiresEveryOrderedNonemptyField() {
        Message summary = Message.user("【历史对话摘要】\n【历史背景】\n"
                + "【最新用户要求】只处理新表\n【已完成及证据】读取第1页，见 a.pdf\n"
                + "【未完成】第2页\n【关键决定及原因】原文件不覆盖\n【证据位置】a.pdf 第1页\n"
                + "【失败尝试】无\n【下一步】读取第2页\n【不确定事项】金额待核验");
        summary.summary = true;
        assertNull(StructuredCheckpoint.validate(Collections.singletonList(summary)));
        summary.content = summary.content.replace("【证据位置】a.pdf 第1页\n", "");
        assertTrue(StructuredCheckpoint.validate(Collections.singletonList(summary)).contains("证据位置"));
        summary.content = "【最新用户要求】只处理新表\n【已完成及证据】\n【未完成】第2页";
        assertTrue(StructuredCheckpoint.validate(Collections.singletonList(summary)).contains("已完成及证据"));
    }
}
