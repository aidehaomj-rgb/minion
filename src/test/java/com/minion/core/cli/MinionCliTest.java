package com.minion.core.cli;

import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/** §11.1 CLI 参数解析 + §12 stream-json 协议验收 */
public class MinionCliTest {

    // ==================== 参数解析 ====================

    @Test public void printModeWithPrompt() {
        MinionCli.Args args = MinionCli.parse(new String[]{"-p", "你好"});
        assertTrue(args.print);
        assertEquals("你好", args.prompt);
        assertEquals("text", args.outputFormat);
        assertFalse(args.isCliMode() == false);
    }

    @Test public void fullParameterSet() {
        MinionCli.Args args = MinionCli.parse(new String[]{
                "-p", "--model", "qwen3.8-flash", "--permission-mode", "read-only",
                "--allowedTools", "Read,Grep,Glob", "--max-turns", "5",
                "--output-format", "stream-json", "分析代码"});
        assertTrue(args.print);
        assertEquals("qwen3.8-flash", args.model);
        assertEquals("read-only", args.permissionMode);
        assertEquals(Arrays.asList("Read", "Grep", "Glob"), args.allowedTools);
        assertEquals(5, args.maxTurns);
        assertEquals("stream-json", args.outputFormat);
        assertEquals("分析代码", args.prompt);
    }

    @Test public void shortFlagCombination() {
        MinionCli.Args args = MinionCli.parse(new String[]{"-vp", "test"});
        assertTrue(args.verbose);
        assertTrue(args.print);
        assertEquals("test", args.prompt);
    }

    /** §13.1 --settings：可重复收集（CLI 层分层链输入） */
    @Test public void settingsFilesRepeatable() {
        MinionCli.Args args = MinionCli.parse(new String[]{
                "-p", "--settings", "a.json", "--settings", "b.json", "提示"});
        assertEquals(Arrays.asList("a.json", "b.json"), args.settingsFiles);
        assertEquals("提示", args.prompt);
    }

    @Test public void resumeAndBare() {
        MinionCli.Args args = MinionCli.parse(new String[]{"-c", "--bare", "继续"});
        assertTrue(args.resume);
        assertTrue(args.bare);
    }

    @Test public void systemPromptFromFile() {
        // 带值参数跳过（暂不消费）不炸
        MinionCli.Args args = MinionCli.parse(new String[]{
                "--settings", "s.json", "--mcp-config", "m.json", "-p", "hi"});
        assertTrue(args.print);
        assertEquals("hi", args.prompt);
    }

    /** CLI 分流判定 */
    @Test public void cliInvocationDetection() {
        assertTrue(MinionCli.isCliInvocation(new String[]{"-p", "x"}));
        assertTrue(MinionCli.isCliInvocation(new String[]{"--print", "x"}));
        assertTrue(MinionCli.isCliInvocation(new String[]{"--output-format", "json", "x"}));
        assertTrue(MinionCli.isCliInvocation(new String[]{"doctor"}));
        assertFalse(MinionCli.isCliInvocation(new String[]{}));
        assertFalse(MinionCli.isCliInvocation(new String[]{"普通文本"}));
    }

    // ==================== stream-json 协议 ====================

    @Test public void protocolEmitsNdJsonLines() throws java.io.UnsupportedEncodingException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StreamJsonProtocol protocol = new StreamJsonProtocol(
                new PrintStream(buffer, true, "UTF-8"), true);
        protocol.systemInit("s1", "test-model", 10, "default");
        protocol.textDelta("s1", "你好");
        protocol.textDelta("s1", "世界");
        protocol.result("s1", true, "success", 1500, 3);
        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        // Windows \r\n 兼容：按 \r?\n 分割
        String[] lines = output.trim().split("\r?\n");
        assertEquals(4, lines.length);
        // 每行是合法 JSON
        for (String line : lines) {
            assertTrue("行应为 JSON: " + line,
                    JsonParser.parseString(line).isJsonObject());
        }
        // init 消息
        assertTrue(lines[0].contains("system"));
        assertTrue(lines[0].contains("init"));
        assertTrue(lines[0].contains("s1"));
        assertTrue(lines[0].contains("test-model"));
        // result 消息
        String last = lines[lines.length - 1];
        assertTrue(last.contains("result"));
        assertTrue(last.contains("success"));
        assertTrue(last.contains("false"));   // is_error=false
    }

    /** 非 stream-json 模式：协议静默丢弃 */
    @Test public void disabledProtocolEmitsNothing() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StreamJsonProtocol protocol = new StreamJsonProtocol(
                new PrintStream(buffer, true), false);
        protocol.systemInit("s", "m", 1, "d");
        protocol.textDelta("s", "x");
        protocol.result("s", true, "success", 0, 0);
        assertEquals(0, buffer.size());
    }

    // ==================== HeadlessConfirmUi ====================

    @Test public void headlessConfirmUiAskToDeny() {
        HeadlessConfirmUi deny = new HeadlessConfirmUi(false);
        assertEquals(HeadlessConfirmUi.Decision.REJECT, deny.ask("高危操作"));
        HeadlessConfirmUi bypass = new HeadlessConfirmUi(true);
        assertEquals(HeadlessConfirmUi.Decision.APPROVE, bypass.ask("高危操作"));
    }
}
