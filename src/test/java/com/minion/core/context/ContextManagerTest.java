package com.minion.core.context;

import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ImagePart;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ContextManagerTest {
    @Test public void millionTokenWindowHonorsHighThresholdWithoutHiddenEightyPercentCap() {
        ContextManager cm=new ContextManager(1000000,0.9,50,new FakeLlmClient(),0);
        assertEquals(900000,cm.autoCompressAtTokens());
        List<Message> messages=Collections.singletonList(Message.user("当前任务"));
        cm.observeInputTokens(messages,60000); assertFalse(cm.shouldCompress(messages));
        cm.observeInputTokens(messages,899999); assertFalse(cm.shouldCompress(messages));
        cm.observeInputTokens(messages,900000); assertTrue(cm.shouldCompress(messages));
        cm.update(1000000,0.95,50); assertEquals(950000,cm.autoCompressAtTokens());
        cm.update(1048576,0.9,50); assertEquals(943718,cm.autoCompressAtTokens());
    }
    @Test public void configuredOutputBudgetCapsTriggerOnlyNearWindowEnd() {
        ContextManager cm=new ContextManager(1000000,1,50,new FakeLlmClient(),0);
        cm.setOutputReserveTokens(65536); assertEquals(934464,cm.autoCompressAtTokens());
        cm.update(1000000,0.9,50); assertEquals(900000,cm.autoCompressAtTokens());
        cm.setOutputReserveTokens(8192); cm.update(1000000,1,50); assertEquals(991808,cm.autoCompressAtTokens());
        cm.setInitialSafeMax(60000); assertEquals(51808,cm.autoCompressAtTokens());
    }
    @Test public void legacyEightyPercentAndInvalidRatioFallbackRemainSafe() {
        assertEquals(48000,new ContextManager(60000,0.8,50,new FakeLlmClient(),0).autoCompressAtTokens());
        for(double invalid:new double[]{0,-1,2,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertEquals(800000,new ContextManager(1000000,invalid,50,new FakeLlmClient(),0).autoCompressAtTokens());
        }
    }

    @Test public void explicitTaskAnchorOverridesOldTaskAndSurvivesFailedCompaction() {
        FakeLlmClient llm=new FakeLlmClient(); ContextManager cm=new ContextManager(100,0.8,1,llm,0);
        List<Message> messages=new ArrayList<Message>(Arrays.asList(Message.user("旧任务"),Message.assistant("旧任务已完成"),
                Message.user("继续执行"),Message.assistant("准备")));
        List<Message> compacted=cm.compressForRecovery(messages,"主目标+子Agent分工","新任务仅处理B表");
        assertTrue(compacted.get(0).content.contains("【当前任务原文】\n新任务仅处理B表"));
        assertTrue(llm.completeChatRequests.get(0).contains("主目标+子Agent分工"));
        llm.throwOnCompleteChat=true;
        assertSame(messages,cm.compressForRecovery(messages,"控制记录","主目标"));
        cm.setTaskAnchorTokens(1000);
        assertTrue(cm.estimate(messages)>=1000);
    }

    @Test
    public void repeatedCompressionMergesPriorSummaryAndKeepsCurrentTaskVerbatim() {
        FakeLlmClient firstLlm = new FakeLlmClient();
        firstLlm.compressResult = "第一阶段已完成；下一步生成最终文件";
        ContextManager first = new ContextManager(100, 0.8, 2, firstLlm, 0);
        List<Message> history = new ArrayList<Message>();
        history.add(Message.user("分析A表并生成最终Excel，不能漏行"));
        history.add(assistantWithTools("Read"));
        for (int i = 0; i < 5; i++) {
            history.add(Message.toolResult("c_Read", "Read", "阶段结果" + i));
            history.add(assistantWithTools("Read"));
        }
        List<Message> once = first.compressForRecovery(history, "- [x] 读取A表\n- [ ] 生成最终Excel");
        assertTrue(once.get(0).content.contains("【当前任务原文】\n分析A表并生成最终Excel，不能漏行"));
        assertTrue(once.get(0).content.contains("[ ] 生成最终Excel"));

        once.add(Message.user("继续执行"));
        once.add(Message.assistant("正在生成"));
        once.add(Message.user("为什么又停止了"));
        once.add(Message.assistant("恢复中"));
        FakeLlmClient secondLlm = new FakeLlmClient();
        secondLlm.compressResult = "第一阶段完成；最终Excel仍待生成";
        ContextManager second = new ContextManager(100, 0.8, 1, secondLlm, 0);
        List<Message> twice = second.compressForRecovery(once, "- [x] 读取A表\n- [ ] 生成最终Excel");

        assertTrue(secondLlm.completeChatRequests.get(0).contains("第一阶段已完成；下一步生成最终文件"));
        assertTrue(secondLlm.completeChatRequests.get(0).contains("【当前有效任务，以此为准】"));
        assertTrue(twice.get(0).content.contains("【当前任务原文】\n分析A表并生成最终Excel，不能漏行"));
        assertFalse(twice.get(0).content.contains("【当前任务原文】\n为什么又停止了"));
        assertTrue(twice.get(0).content.contains("最终Excel仍待生成"));
        assertTrue(twice.get(0).content.contains("[ ] 生成最终Excel"));
    }

    @Test
    public void compressionFailureKeepsPriorSummaryAndTaskAnchor() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "";
        ContextManager cm = new ContextManager(100, 0.8, 1, llm, 0);
        Message old = Message.user("【历史对话摘要】\n【当前任务原文】\n核查全部数据\n【历史背景】\n已检查前10行");
        old.summary = true;
        List<Message> messages = new ArrayList<Message>();
        messages.add(old);
        messages.add(Message.user("继续执行"));
        messages.add(Message.assistant("处理中"));
        List<Message> result = cm.compressForRecovery(messages, "- [ ] 检查剩余数据");
        assertNotSame(messages, result);
        assertTrue(result.get(0).summary);
        assertTrue(result.get(0).content.contains("核查全部数据"));
        assertTrue(result.get(0).content.contains("已检查前10行"));
        assertTrue(result.get(0).content.contains("检查剩余数据"));
    }

    @Test
    public void serverUsage_calibratesEstimateAndResetsOnModelChange() {
        ContextManager cm = new ContextManager(131072, 0.8, 10, new FakeLlmClient(), 10);
        List<Message> messages = Collections.singletonList(Message.user("测试内容"));
        int original = cm.estimate(messages);
        cm.observeInputTokens(messages, 110000);
        assertEquals(110000, cm.estimate(messages));
        assertTrue(cm.shouldCompress(messages));
        cm.setLlm(new FakeLlmClient());
        assertEquals(original, cm.estimate(messages));
    }

    @Test
    public void safeWindowCanBeLoweredAndLearnsFromRealFailure() {
        ContextManager cm = new ContextManager(131072, 0.8, 10, new FakeLlmClient(), 0);
        cm.setInitialSafeMax(60000);
        assertEquals(60000, cm.maxTokens());
        assertEquals(48000, cm.autoCompressAtTokens());
        assertEquals(49500, cm.learnFromContextFailure(55000));
        assertEquals(49500, cm.maxTokens());
        cm.update(131072, 0.8, 10);
        assertEquals(131072, cm.maxTokens());
    }

    @Test
    public void prepareForRequestShrinksOldHeavyDataButKeepsCurrentTaskAndOriginal() {
        ContextManager cm = new ContextManager(131072, 0.8, 10, new FakeLlmClient(), 0);
        List<Message> messages = new ArrayList<Message>();
        Message oldUser = Message.userWithImages("旧问题", Collections.singletonList(image("old.png")));
        messages.add(oldUser);
        Message oldCall = assistantWithTools("Write");
        oldCall.reasoningContent = repeat('思', 10000);
        oldCall.toolCalls.get(0).arguments = "{\"text\":\"" + repeat('a', 10000) + "\"}";
        messages.add(oldCall);
        Message oldTool = Message.toolResult("c_Write", "Write", repeat('x', 30000));
        messages.add(oldTool);
        messages.add(Message.assistant("旧任务完成"));
        messages.add(Message.user("当前任务必须完整保留"));
        Message currentCall = assistantWithTools("Read");
        currentCall.reasoningContent = repeat('现', 15000);
        currentCall.toolCalls.get(0).arguments = "{\"path\":\"" + repeat('p', 17000) + "\"}";
        messages.add(currentCall);
        Message currentTool = Message.toolResult("c_Read", "Read", repeat('z', 30000));
        messages.add(currentTool);

        List<Message> prepared = cm.prepareForRequest(messages);
        assertNull(prepared.get(0).images);
        assertNotNull(oldUser.images); // 本地历史未改
        assertTrue(prepared.get(1).reasoningContent.length() < oldCall.reasoningContent.length());
        assertEquals("{\"_minion_compacted\":true,\"_original_chars\":10011}",
                prepared.get(1).toolCalls.get(0).arguments);
        assertTrue(prepared.get(2).content.length() < oldTool.content.length());
        assertEquals("当前任务必须完整保留", prepared.get(4).content);
        assertTrue(prepared.get(5).reasoningContent.length() < currentCall.reasoningContent.length());
        assertEquals(currentCall.toolCalls.get(0).arguments, prepared.get(5).toolCalls.get(0).arguments);
        assertTrue(prepared.get(6).content.length() < currentTool.content.length());
        assertEquals(30000, currentTool.content.length());
    }

    private static ImagePart image(String name) {
        ImagePart p = new ImagePart();
        p.name = name;
        p.mime = "image/png";
        p.base64 = "AAAA";
        return p;
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static Message assistantWithTools(String... names) {
        Message m = Message.assistant(null);
        List<ToolCall> tcs = new ArrayList<ToolCall>();
        for (String n : names) {
            ToolCall tc = new ToolCall();
            tc.id = "c_" + n;
            tc.name = n;
            tc.arguments = "{}";
            tcs.add(tc);
        }
        m.toolCalls = tcs;
        return m;
    }

    private static List<Message> sampleHistory() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("任务1"));
        msgs.add(assistantWithTools("Read"));
        msgs.add(Message.toolResult("c_Read", "Read", "内容1"));
        msgs.add(Message.assistant("任务1完成"));
        msgs.add(Message.user("任务2"));
        msgs.add(Message.assistant("直接完成"));
        return msgs;
    }

    @Test
    public void chunkChains_keepsToolPairingIntact() {
        List<List<Message>> chains = ContextManager.chunkChains(sampleHistory());
        assertEquals(2, chains.size());
        // 链1 = user + assistant(tools) + tool + assistant(无工具)
        assertEquals(4, chains.get(0).size());
        assertEquals("任务1", chains.get(0).get(0).content);
        assertEquals("任务1完成", chains.get(0).get(3).content);
        // 链2 = user + assistant
        assertEquals(2, chains.get(1).size());
    }

    @Test
    public void chunkChains_skipsSummaryMessages() {
        List<Message> msgs = new ArrayList<Message>();
        Message summary = Message.user("【摘要】之前的内容");
        summary.summary = true;
        msgs.add(summary);
        msgs.add(Message.user("新问题"));
        msgs.add(Message.assistant("回答"));
        List<List<Message>> chains = ContextManager.chunkChains(msgs);
        assertEquals(1, chains.size());
        assertFalse(chains.get(0).get(0).summary);
    }

    @Test
    public void chunkChains_skipsSystemMessages() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("你是 minion，一个编码助手"));
        msgs.addAll(sampleHistory());
        // 切块：system 不入链（链1 = 4 条，链2 = 2 条）
        List<List<Message>> chains = ContextManager.chunkChains(msgs);
        assertEquals(2, chains.size());
        assertEquals(4, chains.get(0).size());

        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】完成了任务1和任务2";
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(msgs);
        // system 原样保留在结果最前，不随首链被压缩丢失
        assertEquals(4, result.size()); // system + summary + 链2(2条)
        assertEquals(Message.Role.SYSTEM, result.get(0).role);
        assertEquals("你是 minion，一个编码助手", result.get(0).content);
        assertEquals("任务2", result.get(2).content);
        // 压缩批次（[0]=压缩器 system，[1]=user 批次）不含系统提示词
        String batch = llm.lastRequestMessages.get(1).content;
        assertFalse(batch.contains("你是 minion，一个编码助手"));
    }

    @Test
    public void shouldCompress_overThreshold() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        // 100*0.8=80 token 触发；20 字符 ≈ 5 token
        List<Message> big = new ArrayList<Message>();
        for (int i = 0; i < 30; i++) {
            big.add(Message.user("一二三四五六七八九十"));
            big.add(Message.assistant("abcdefghij"));
        }
        assertTrue(cm.shouldCompress(big));
        List<Message> small = Collections.singletonList(Message.user("hi"));
        assertFalse(cm.shouldCompress(small));
    }

    @Test
    public void compress_replacesOldWithSummary() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】完成了任务1和任务2";
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(sampleHistory());
        // summary 置前
        Message first = result.get(0);
        assertTrue(first.summary);
        assertTrue(first.content.contains("【摘要】"));
        // keepRecent=2 保留最后 2 条原文（链2）
        assertEquals(3, result.size());
        assertEquals("任务2", result.get(1).content);
        // 压缩请求带专用 system
        assertTrue(llm.lastRequestMessages.get(0).content.contains("压缩器"));
    }

    @Test
    public void compress_llmFailure_returnsOriginal() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        // FakeLlmClient.completeChat 不抛异常，这里通过设置 compressResult 为空串模拟
        llm.compressResult = "";
        List<Message> input = sampleHistory();
        List<Message> result = cm.compress(input);
        // 空摘要 → 视为失败，原样返回（失败路径返回同一实例，内容与顺序全等；
        // Message 无 equals 重写，引用相等是最强校验）
        assertSame(input, result);
    }

    @Test
    public void compress_llmThrows_returnsOriginal() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.throwOnCompleteChat = true;
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> input = sampleHistory();
        List<Message> result = cm.compress(input);
        // 压缩请求抛异常 → 打印告警并原样返回，下轮重试
        assertSame(input, result);
    }

    @Test
    public void estimate_includesSystemTokens() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm0 = new ContextManager(100, 0.8, 2, llm, 0);
        ContextManager cm50 = new ContextManager(100, 0.8, 2, llm, 50);
        // systemTokens 计入 estimate
        assertEquals(50, cm50.estimate(sampleHistory()) - cm0.estimate(sampleHistory()));
        // sampleHistory 6 条：内容 2+2+2+4+2+3=15 + 6×4 overhead=24 → 39；+50 → 89
        assertEquals(89, cm50.estimate(sampleHistory()));
    }

    @Test
    public void update_changesCompressionParams() {
        ContextManager cm = new ContextManager(1000, 0.8, 5, new FakeLlmClient(), 10);
        cm.update(100, 0.1, 8);
        assertEquals(100, cm.maxTokens());
        assertTrue(cm.shouldCompress(sampleHistory())); // 新阈值下必压缩
    }

    @Test
    public void setLlm_usedForCompressionRequests() {
        FakeLlmClient a = new FakeLlmClient();
        a.compressResult = "【摘要】A";
        FakeLlmClient b = new FakeLlmClient();
        b.compressResult = "【摘要】B";
        ContextManager cm = new ContextManager(100, 0.1, 0, a, 10); // 阈值极低：必触发压缩
        List<Message> out = cm.compress(sampleHistory());
        assertNotNull(a.lastRequestMessages); // 压缩走旧客户端
        cm.setLlm(b);
        out = cm.compress(sampleHistory());
        assertTrue(out.get(0).content.contains("【摘要】B")); // 换客户端后走新客户端
    }

    /** 压缩豁免：pinned 技能消息不入链（不参与切块/摘要），普通历史照常成链 */
    @Test
    public void chunkChains_skipsPinnedSkillMessages() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("任务1"));
        msgs.add(Message.assistant("完成"));
        msgs.add(Message.skill("<skill name=\"review\">\n审查指令全文\n</skill>"));
        msgs.add(Message.user("任务2"));
        msgs.add(Message.assistant("回答"));
        List<List<Message>> chains = ContextManager.chunkChains(msgs);
        // pinned 不入链：链1 = [任务1, 完成]，链2 = [任务2, 回答]
        assertEquals(2, chains.size());
        assertEquals(2, chains.get(0).size());
        assertEquals(2, chains.get(1).size());
    }

    /** 保留区占比 >50% 时 limit 减半：60 条长消息(每链50token) + 40 条短消息(每链10token)，
     *  keepRecent=50 → 保留区 1250/1700≈73% → 减半 25 → 24 条(12链) 600/1700≈35% 停 */
    @Test
    public void compress_shrinksKeepRegionWhenRatioHigh() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        ContextManager cm = new ContextManager(100000, 0.8, 50, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 20; i++) { // 40 条短消息（旧）
            msgs.add(Message.user("短"));
            msgs.add(Message.assistant("短"));
        }
        for (int i = 0; i < 30; i++) { // 60 条长消息（新）
            msgs.add(Message.user("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九"));
            msgs.add(Message.assistant("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九"));
        }
        List<Message> result = cm.compress(msgs);
        // 压缩后 = summary + 12 链(24 条)保留区
        assertEquals(25, result.size());
        assertTrue(result.get(0).summary);
    }

    /** 下限 12 条：后 24 条长消息(每链148token) + 前 36 条短消息(每链14token)，
     *  50 条保留区占 96% → 25 条仍 88% → 12 条(6长链) 44% 达标即停（不再减半） */
    @Test
    public void compress_keepRegionStopsAtMin() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        ContextManager cm = new ContextManager(100000, 0.8, 50, llm, 0);
        String longText = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 18; i++) { // 36 条短消息（前）
            msgs.add(Message.user("短消息"));
            msgs.add(Message.assistant("短消息"));
        }
        for (int i = 0; i < 12; i++) { // 24 条长消息（后）
            msgs.add(Message.user(longText));
            msgs.add(Message.assistant(longText));
        }
        List<Message> result = cm.compress(msgs);
        // 压缩后 = summary + 6 链(12 条)保留区
        assertEquals(13, result.size());
        assertTrue(result.get(0).summary);
    }

    /** 边界：limit 减半不跌破下限。keepRecent=13（>KEEP_MIN 但 <2×KEEP_MIN），
     *  保留区 300/500=60% >50% 触发减半：13/2=6 会跌破 12 条下限；
     *  修复后 max(12, 6)=12 → 保留 12 条（6 长链）即停 */
    @Test
    public void compress_keepRegionHalvingNeverBelowMin() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        ContextManager cm = new ContextManager(100000, 0.8, 13, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 20; i++) { // 40 条短消息（前，每链 10 token）
            msgs.add(Message.user("短"));
            msgs.add(Message.assistant("短"));
        }
        String longText = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九";
        for (int i = 0; i < 6; i++) { // 12 条长消息（后，每链 50 token）
            msgs.add(Message.user(longText));
            msgs.add(Message.assistant(longText));
        }
        List<Message> result = cm.compress(msgs);
        // 压缩后 = summary + 12 条（6 长链）保留区，不低于 KEEP_MIN
        assertEquals(13, result.size());
        assertTrue(result.get(0).summary);
        // 保留区最后一条是长消息（长链未被打断，仍在保留区内）
        assertEquals(longText, result.get(result.size() - 1).content);
    }

    /** 压缩后 pinned 技能消息原样保留（摘要后、保留链前），且不进入摘要批次 */
    @Test
    public void compress_keepsPinnedSkillMessages() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("任务1"));
        msgs.add(Message.assistant("完成"));
        msgs.add(Message.skill("<skill name=\"review\">\n审查指令全文\n</skill>"));
        msgs.add(Message.user("任务2"));
        msgs.add(Message.assistant("回答"));
        List<Message> result = cm.compress(msgs);
        // summary + pinned + 保留链（链2 = 2 条）
        assertEquals(4, result.size());
        assertTrue(result.get(0).summary);
        assertTrue(result.get(1).pinned);
        assertTrue(result.get(1).content.contains("审查指令全文"));
        assertEquals("任务2", result.get(2).content);
        assertEquals("回答", result.get(3).content);
        // 摘要批次不含技能正文
        String batch = llm.lastRequestMessages.get(1).content;
        assertFalse(batch.contains("审查指令全文"));
    }

    /** 每链 2 条同内容消息（10 中文字 = 11 token/条、22/链恒定），二分点稳定（19 链 → mid=10） */
    private static List<Message> longHistory(int chainsCount) {
        List<Message> msgs = new ArrayList<Message>();
        String base = "一二三四五六七八九十";
        for (int i = 0; i < chainsCount; i++) {
            msgs.add(Message.user(base));
            msgs.add(Message.assistant(base));
        }
        return msgs;
    }

    private static int countUserTags(String batch) {
        int n = 0;
        int idx = 0;
        while ((idx = batch.indexOf("[USER]", idx)) >= 0) { n++; idx += 6; }
        return n;
    }

    /** 整体失败 → 二分：左段(链0..9,20条)成功出摘要A，右段(链10..18)带摘要A 合并成功 → 单个 summary；共 3 次调用 */
    @Test
    public void compress_splitsOnFailure_andMerges() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        llm.failAtCompleteChat.add(1); // 整体失败
        ContextManager cm = new ContextManager(100000, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(longHistory(20)); // 40 条 = 20 链，压 19 链
        // summary + 保留 2 条
        assertEquals(3, result.size());
        assertTrue(result.get(0).summary);
        assertEquals(3, llm.completeChatRequests.size());
        // 第 2 次 = 左段 10 链（20 条，其中 [USER] 10 个）；第 3 次 = 摘要A + 右段 9 链（18 条，[USER] 9 个）
        assertEquals(10, countUserTags(llm.completeChatRequests.get(1)));
        assertEquals(9, countUserTags(llm.completeChatRequests.get(2))); // 摘要A + 右段 9 链
        assertTrue(llm.completeChatRequests.get(2).startsWith("【摘要】历史要点"));
    }

    /** 合并轮失败：左段成功(摘要A)、合并失败 → 降级为「摘要A + 右段18条原文」+ 保留2条 */
    @Test
    public void compress_mergeFailure_keepsPrefixSummary() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        llm.failAtCompleteChat.add(1);
        llm.failAtCompleteChat.add(3); // 整体✗、左✓、合并✗
        ContextManager cm = new ContextManager(100000, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(longHistory(20));
        // summary(摘要A) + 右段 18 条原文 + 保留 2 条
        assertEquals(21, result.size());
        assertTrue(result.get(0).summary);
        assertTrue(result.get(0).content.contains("【摘要】历史要点"));
        assertEquals("一二三四五六七八九十", result.get(1).content); // 右段原文开始
    }

    /** 左段全败、右段成功：摘要B 置前 + 左段20条原文 + 保留2条 */
    @Test
    public void compress_leftFailure_keepsLeftOriginal() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        llm.failAtCompleteChat.add(1);
        llm.failAtCompleteChat.add(2); // 整体✗、左✗、右✓
        ContextManager cm = new ContextManager(100000, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(longHistory(20));
        // summary(摘要B) + 左段 20 条原文 + 保留 2 条
        assertEquals(23, result.size());
        assertTrue(result.get(0).summary);
        assertEquals("一二三四五六七八九十", result.get(1).content);
        assertEquals("一二三四五六七八九十", result.get(2).content);
    }

    /** 全部段失败：原样返回（引用相等，最强校验） */
    @Test
    public void compress_allSegmentsFail_returnsOriginal() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        llm.failAtCompleteChat.add(1);
        llm.failAtCompleteChat.add(2);
        llm.failAtCompleteChat.add(3);
        ContextManager cm = new ContextManager(100000, 0.8, 2, llm, 0);
        List<Message> input = longHistory(20);
        List<Message> result = cm.compress(input);
        assertSame(input, result);
    }

    /** 缺陷A回归：左段部分成功（摘要A+右左原文）、右段整体失败 → 右段原文必须保留（修复前丢失） */
    @Test
    public void compress_partialSuccessRightAllFail_keepsRightOriginal() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        llm.failAtCompleteChat.add(1); // 整体✗（39 链 78 条 → 二分 mid=20）
        llm.failAtCompleteChat.add(3); // 右段带摘要A 合并✗（19 链 38 条 → 二分 mid=30）
        llm.failAtCompleteChat.add(4); // 右左段带摘要A✗（10 链 20 条 ≤ SEGMENT_MIN → 部分成功）
        llm.failAtCompleteChat.add(5); // 右右段独立压✗（9 链 18 条 → 整体失败）
        ContextManager cm = new ContextManager(100000, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(longHistory(40)); // 80 条 = 40 链，压 39 链
        // 摘要A + 右左 20 条原文 + 右右 18 条原文 + 保留 2 条 = 41 条（修复前仅 23 条，右右原文丢失）
        assertEquals(41, result.size());
        assertTrue(result.get(0).summary);
        assertTrue(result.get(0).content.contains("【摘要】历史要点"));
        for (int i = 1; i < result.size(); i++) {
            assertEquals("一二三四五六七八九十", result.get(i).content);
        }
    }

    /** P2 回归：超长链均分切分点不得落在 tool_result 上（子链首条不得为 TOOL），
     *  否则切断 tool_call/tool_result 配对：保留区首条为孤立 TOOL 消息 → 下轮 DeepSeek 400。
     *  41 条单链（user + 20×(A(t)+T)）：mid=20 恰指向 T（修复前子链 [20..41) 首条为 TOOL） */
    @Test
    public void splitOversizedChains_noToolResultAtSubchainHead() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("一二三四五六七八九十"));
        for (int i = 0; i < 20; i++) {
            msgs.add(assistantWithTools("Read"));
            msgs.add(Message.toolResult("c_Read", "Read", "一二三四五六七八九十"));
        }
        List<List<Message>> chains = ContextManager.splitOversizedChains(ContextManager.chunkChains(msgs));
        int total = 0;
        for (List<Message> c : chains) {
            assertNotEquals(Message.Role.TOOL, c.get(0).role); // 子链首条非 TOOL（配对不跨段）
            assertTrue(c.size() <= 30);
            total += c.size();
        }
        assertEquals(41, total); // 消息无丢失、顺序保持
    }

    /** P2 回归（端到端）：超长链压缩成功后，保留区首条不得为 TOOL（孤立 tool_result 下轮 400）。
     *  41 条单链（超长 user 消息占大 token）→ 切分为 2 子链；keepRecent=25 保留最后一子链，
     *  保留区占比 ≤50% 不减半。修复前切分点 mid=20 落在 T 上 → 保留区首条为孤立 TOOL */
    @Test
    public void compress_keepRegionHeadNotToolResult() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        ContextManager cm = new ContextManager(100000, 0.8, 25, llm, 0);
        StringBuilder longUser = new StringBuilder();
        for (int i = 0; i < 100; i++) longUser.append("一二三四五六七八九十");
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user(longUser.toString()));
        for (int i = 0; i < 20; i++) {
            msgs.add(assistantWithTools("Read"));
            msgs.add(Message.toolResult("c_Read", "Read", "短"));
        }
        List<Message> result = cm.compress(msgs);
        // summary + 保留区原文；保留区首条非 TOOL，且其配对（tool_call）紧随其前、配对完整
        assertTrue(result.get(0).summary);
        assertNotEquals(Message.Role.TOOL, result.get(1).role);
        assertEquals(Message.Role.TOOL, result.get(2).role);
        assertEquals("c_Read", result.get(2).toolCallId);
    }

    /** 缺陷B回归：单条超长链（>SEGMENT_MIN 消息）持续失败不得无限递归栈溢出，应原样返回 */
    @Test
    public void compress_singleOversizedChain_noStackOverflow() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = ""; // 恒失败（空摘要）
        ContextManager cm = new ContextManager(100000, 0.8, 2, llm, 0);
        List<Message> input = new ArrayList<Message>();
        input.add(Message.user("一二三四五六七八九十"));
        for (int i = 0; i < 40; i++) { // 40 轮工具调用连续无链结束符 → 与首尾合成单链 82 条
            input.add(assistantWithTools("Read"));
            input.add(Message.toolResult("c_Read", "Read", "一二三四五六七八九十"));
        }
        input.add(Message.assistant("一二三四五六七八九十"));
        List<Message> result = cm.compress(input);
        assertSame(input, result);
    }

    /** 缺陷回归（线上现象：43 轮对话 99% 上下文，点压缩报"暂无可压缩内容"）：
     *  全程使用默认配置（maxContextTokens=131000, threshold=0.8, keepRecent=50），
     *  不依赖用户改小窗口——缺陷本质是"保留区只看消息条数不看 token"：
     *  43 条消息 ≤ 50 条保留上限，但单条超长（长思考 reasoningEffort=max /
     *  大段代码）使 token 已达 99% → take==0 原样返回 → 误报"暂无可压缩内容"。
     *  43 条 × 3014 token ≈ 129.6K / 131K ≈ 99%，超 0.8 阈值，必须能压缩。 */
    @Test
    public void compress_fewMessagesHugeTokens_mustCompress() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        ContextManager cm = new ContextManager(131000, 0.8, 50, llm, 0); // 默认配置
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 430; i++) big.append("一二三四五六七八九十"); // 4300 字 ≈ 3010 token
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 21; i++) { // 21 轮（42 条）
            msgs.add(Message.user(big.toString()));
            msgs.add(Message.assistant(big.toString()));
        }
        msgs.add(Message.user(big.toString())); // 第 43 条
        assertTrue(cm.shouldCompress(msgs)); // 99% 场景前提
        List<Message> result = cm.compress(msgs);
        assertNotSame(msgs, result); // 修复前：take==0 原样返回（线上误报"暂无可压缩内容"）
        assertTrue(result.size() < msgs.size());
        assertTrue(result.get(0).summary);
    }

    /** 压缩尝试状态：区分"无可压缩"（take==0）与"压缩失败"（take>0 但 LLM 调用异常），
     *  供 AgentLoop.compactNow 文案区分（修复前统一误报"暂无可压缩内容"） */
    @Test
    public void compress_attemptedFlag_distinguishesFailure() {
        // 场景1：take==0（消息少且未超阈值）→ 未尝试
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        ContextManager cm = new ContextManager(131000, 0.8, 50, llm, 0);
        List<Message> tiny = new ArrayList<Message>();
        tiny.add(Message.user("hi"));
        tiny.add(Message.assistant("hello"));
        cm.compress(tiny);
        assertFalse(cm.lastCompressAttempted());
        // 场景2：take>0 且压缩成功 → 已尝试
        List<Message> mid = new ArrayList<Message>();
        for (int i = 0; i < 30; i++) { // 60 条 > 50 → take > 0
            mid.add(Message.user("短消息"));
            mid.add(Message.assistant("短消息"));
        }
        cm.compress(mid);
        assertTrue(cm.lastCompressAttempted());
        // 场景3：take>0 但 LLM 调用失败 → 已尝试（与"无可压缩"区分开）
        FakeLlmClient llm2 = new FakeLlmClient();
        llm2.throwOnCompleteChat = true;
        ContextManager cm2 = new ContextManager(131000, 0.8, 50, llm2, 0);
        cm2.compress(sampleHistory()); // 6 条 ≤ 50 → take==0 → 未尝试
        assertFalse(cm2.lastCompressAttempted());
        List<Message> big = new ArrayList<Message>();
        for (int i = 0; i < 30; i++) { // 60 条 > 50 → take > 0
            big.add(Message.user("短消息"));
            big.add(Message.assistant("短消息"));
        }
        List<Message> bigResult = cm2.compress(big);
        assertTrue(cm2.lastCompressAttempted());
        assertSame(big, bigResult); // 失败原样返回
    }

    /** 配置窗口大于服务端真实窗口时，常规判断不会触发；服务端超限恢复仍须压缩单条进行中的工具链。 */
    @Test
    public void compressForRecovery_forcesSingleActiveChain() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "保留任务目标和当前进度";
        ContextManager cm = new ContextManager(900000, 0.8, 50, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("执行一个复杂任务"));
        msgs.add(assistantWithTools("Read"));
        msgs.add(Message.toolResult("c_Read", "Read", "大量工具结果"));

        assertFalse(cm.shouldCompress(msgs));
        List<Message> result = cm.compressForRecovery(msgs);

        assertNotSame(msgs, result);
        assertEquals(1, result.size());
        assertTrue(result.get(0).summary);
        assertTrue(cm.lastCompressAttempted());
    }

    /** 摘要请求自身不得原样携带巨型工具输出，否则恢复压缩也会再次撞上窗口上限。 */
    @Test
    public void compressForRecovery_truncatesHugeFieldSentToSummarizer() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(900000, 0.8, 50, llm, 0);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 30000; i++) huge.append('数');
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("分析数据"));
        msgs.add(assistantWithTools("Python"));
        msgs.add(Message.toolResult("c_Python", "Python", huge.toString()));

        cm.compressForRecovery(msgs);

        assertEquals(1, llm.completeChatRequests.size());
        String sent = llm.completeChatRequests.get(0);
        assertTrue(sent.contains("压缩时已省略"));
        assertTrue(sent.length() < 20000);
    }
}
