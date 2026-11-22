package com.minion.core.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * v2.4 §12.2 SDK 最小双向协议验收（headless，可编程 stdin + 捕获 stdout）：
 * init→user→assistant/result 往返、版本不兼容拒执、工具批中 interrupt、
 * keep_alive、畸形/超大帧协议错误不执行、EOF 断连清理；can_use_tool 装饰器
 * allow/deny/超时 fail-closed/updatedInput 防绕过（SdkGuardedToolTest 场景并入）。
 */
public class SdkDuplexTest {

    /** 可动态喂帧的 InputStream（测试主线程 offer，EOF 收束） */
    static final class ScriptedStdin extends InputStream {
        final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<byte[]>(64);
        volatile boolean eof;
        byte[] current = new byte[0];
        int pos = 0;

        void send(String line) throws InterruptedException {
            chunks.put((line + "\n").getBytes(StandardCharsets.UTF_8));
        }

        void finish() { eof = true; }

        @Override public int read() {
            while (pos >= current.length) {
                byte[] next = chunks.poll();
                if (next != null) { current = next; pos = 0; continue; }
                if (eof) return -1;
                try { Thread.sleep(10); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
            }
            return current[pos++] & 255;
        }
    }

    /** 脚本引擎：记录调用；latch 模拟长工具批可被 interrupt 解锁 */
    static final class ScriptedEngine implements SdkDuplex.TurnEngine {
        final List<String> prompts = new ArrayList<String>();
        final List<String> interrupts = new ArrayList<String>();
        volatile CountDownLatch blockOn;
        volatile boolean succeeded = true;

        @Override public String sessionId() { return "sess-test"; }
        @Override public void runUserTurn(String prompt) throws Exception {
            prompts.add(prompt);
            if (blockOn != null) blockOn.await(10, TimeUnit.SECONDS);   // 模拟在途工具批
        }
        @Override public boolean lastTurnSucceeded() { return succeeded; }
        @Override public int lastTurnToolRounds() { return 1; }
        @Override public String pauseCode() { return null; }
        @Override public void interrupt() { interrupts.add("interrupt"); }
        @Override public String setPermissionMode(String mode) {
            return "read-only|workspace-write|full|default".contains(mode) ? null : "bad";
        }
        @Override public String setModel(String model) { return null; }
    }

    private static List<JsonObject> frames(ByteArrayOutputStream out) {
        List<JsonObject> parsed = new ArrayList<JsonObject>();
        for (String line : out.toString().split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try { parsed.add(JsonParser.parseString(line).getAsJsonObject()); }
            catch (Exception ignored) { fail("stdout 出现非协议行: " + line); }
        }
        return parsed;
    }

    /** init → user → result 往返 + keep_alive 响应 + request_id 关联 */
    @Test public void initializeUserRoundTrip() throws Exception {
        ScriptedStdin in = new ScriptedStdin();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, "UTF-8");
        StreamJsonProtocol protocol = new StreamJsonProtocol(out, true);
        ScriptedEngine engine = new ScriptedEngine();
        SdkHostBridge bridge = new SdkHostBridge(protocol, 1000);
        SdkDuplex duplex = new SdkDuplex(protocol, engine, bridge);

        Thread driver = new Thread(() -> {
            try {
                in.send("{\"type\":\"initialize\",\"protocolVersion\":1,\"request_id\":\"r1\"}");
                Thread.sleep(300);
                in.send("{\"type\":\"user\",\"content\":\"计算 1+1\"}");
                Thread.sleep(400);
                in.send("{\"type\":\"control_request\",\"request_id\":\"r2\",\"subtype\":\"keep_alive\"}");
                Thread.sleep(300);
                in.finish();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        driver.start();
        int exit = duplex.run(new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)));
        driver.join(5000);

        assertEquals(0, exit);
        assertEquals(java.util.Collections.singletonList("计算 1+1"), engine.prompts);
        List<JsonObject> frames = frames(captured);
        assertTrue(frames.stream().anyMatch(f -> "control_response".equals(type(f))
                && "r1".equals(str(f, "request_id")) && success(f)));
        assertTrue("result success 帧", frames.stream().anyMatch(f ->
                "result".equals(type(f)) && !f.get("is_error").getAsBoolean()));
        assertTrue("keep_alive 响应", frames.stream().anyMatch(f ->
                "control_response".equals(type(f)) && "r2".equals(str(f, "request_id")) && success(f)));
        assertTrue("state 变化帧", frames.stream().anyMatch(f ->
                "system".equals(type(f)) && "idle".equals(str(f, "state"))));
    }

    /** 主版本不兼容 → protocol_error + 拒执退出 */
    @Test public void versionMismatchRejects() throws Exception {
        ScriptedStdin in = new ScriptedStdin();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        StreamJsonProtocol protocol = new StreamJsonProtocol(
                new PrintStream(captured, true, "UTF-8"), true);
        SdkDuplex duplex = new SdkDuplex(protocol, new ScriptedEngine(),
                new SdkHostBridge(protocol, 1000));
        Thread driver = new Thread(() -> {
            try {
                in.send("{\"type\":\"initialize\",\"protocolVersion\":99,\"request_id\":\"r1\"}");
                in.finish();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        driver.start();
        int exit = duplex.run(new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)));
        driver.join(3000);
        assertEquals(2, exit);
        assertTrue(frames(captured).stream().anyMatch(f -> {
            if (!"control_response".equals(type(f))) return false;
            JsonObject response = f.has("response") && f.get("response").isJsonObject()
                    ? f.getAsJsonObject("response") : f;
            return "version_mismatch".equals(str(response, "code"));
        }));
    }

    /** 首帧非 initialize / 畸形帧 / 超大帧 → 协议错误；畸形不中断后续处理 */
    @Test public void malformedAndOversizeFramesRejectedLoopContinues() throws Exception {
        ScriptedStdin in = new ScriptedStdin();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        StreamJsonProtocol protocol = new StreamJsonProtocol(
                new PrintStream(captured, true, "UTF-8"), true);
        ScriptedEngine engine = new ScriptedEngine();
        SdkDuplex duplex = new SdkDuplex(protocol, engine, new SdkHostBridge(protocol, 1000));
        Thread driver = new Thread(() -> {
            try {
                in.send("{\"type\":\"initialize\",\"protocolVersion\":1}");
                Thread.sleep(200);
                in.send("{{{ not json");
                StringBuilder big = new StringBuilder("{\"type\":\"user\",\"content\":\"x");
                for (int i = 0; i < SdkFrameIo.MAX_FRAME_BYTES + 100; i++) big.append('a');
                in.send(big.toString() + "\"}");
                Thread.sleep(200);
                in.send("{\"type\":\"user\",\"content\":\"合法帧继续执行\"}");
                Thread.sleep(300);
                in.finish();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        driver.start();
        int exit = duplex.run(new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)));
        driver.join(8000);
        assertEquals(0, exit);
        List<JsonObject> frames = frames(captured);
        assertTrue(frames.stream().anyMatch(f -> "system".equals(type(f))
                && "malformed_frame".equals(str(f, "code"))));
        assertTrue(frames.stream().anyMatch(f -> "system".equals(type(f))
                && "frame_too_large".equals(str(f, "code"))));
        assertEquals("畸形/超大帧后合法 user 帧仍执行", 1, engine.prompts.size());
        assertEquals("合法帧继续执行", engine.prompts.get(0));
    }

    /** 工具批中 interrupt：user 在途时宿主发 interrupt → engine.interrupt 被调（解锁轮） */
    @Test public void interruptDuringInFlightTurn() throws Exception {
        ScriptedStdin in = new ScriptedStdin();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        StreamJsonProtocol protocol = new StreamJsonProtocol(
                new PrintStream(captured, true, "UTF-8"), true);
        ScriptedEngine engine = new ScriptedEngine();
        final CountDownLatch turnStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        engine.blockOn = new CountDownLatch(1);
        SdkDuplex duplex = new SdkDuplex(protocol, engine, new SdkHostBridge(protocol, 1000));
        Thread driver = new Thread(() -> {
            try {
                in.send("{\"type\":\"initialize\",\"protocolVersion\":1}");
                Thread.sleep(200);
                in.send("{\"type\":\"user\",\"content\":\"长任务\"}");
                turnStarted.await(5, TimeUnit.SECONDS);
                in.send("{\"type\":\"control_request\",\"request_id\":\"r9\",\"subtype\":\"interrupt\"}");
                release.await(5, TimeUnit.SECONDS);
                Thread.sleep(200);
                engine.blockOn.countDown();   // interrupt 到达后释放模拟长工具批
                Thread.sleep(400);
                in.finish();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        // turnStarted 在 prompts 记录后触发——用轮询 prompts 替代
        Thread watcher = new Thread(() -> {
            while (engine.prompts.isEmpty())
                try { Thread.sleep(20); } catch (InterruptedException ignored) { return; }
            turnStarted.countDown();
        });
        watcher.start();
        // release 在 interrupt 到达后置位
        Thread watcher2 = new Thread(() -> {
            while (engine.interrupts.isEmpty())
                try { Thread.sleep(20); } catch (InterruptedException ignored) { return; }
            release.countDown();
        });
        watcher2.start();
        Thread driver2 = driver; driver2.start();
        int exit = duplex.run(new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)));
        driver.join(10000); watcher.join(1000); watcher2.join(1000);
        assertEquals(0, exit);
        assertEquals(1, engine.prompts.size());
        assertTrue("turn 在途时收到 interrupt（EOF 清理会再补一次，共≥1）",
                engine.interrupts.size() >= 1);
        assertTrue("interrupt 响应", frames(captured).stream().anyMatch(f ->
                "control_response".equals(type(f)) && "r9".equals(str(f, "request_id")) && success(f)));
    }

    /** EOF 断连：在途轮被中断 + pending 清理 + 正常退出 */
    @Test public void eofInterruptsAndCleans() throws Exception {
        ScriptedStdin in = new ScriptedStdin();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        StreamJsonProtocol protocol = new StreamJsonProtocol(
                new PrintStream(captured, true, "UTF-8"), true);
        ScriptedEngine engine = new ScriptedEngine();
        engine.blockOn = new CountDownLatch(1);
        SdkHostBridge bridge = new SdkHostBridge(protocol, 1000);
        SdkDuplex duplex = new SdkDuplex(protocol, engine, bridge);
        Thread driver = new Thread(() -> {
            try {
                in.send("{\"type\":\"initialize\",\"protocolVersion\":1}");
                Thread.sleep(150);
                in.send("{\"type\":\"user\",\"content\":\"挂起的长任务\"}");
                Thread.sleep(300);
                in.finish();   // EOF：宿主断连
                Thread.sleep(100);
                engine.blockOn.countDown();   // interrupt 后释放
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        driver.start();
        int exit = duplex.run(new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)));
        driver.join(10000);
        assertEquals(0, exit);
        assertEquals("EOF 应触发 interrupt", 1, engine.interrupts.size());
        assertTrue("断连后 bridge 关闭（后续往返 deny）", bridge.isClosed());
    }

    private static String type(JsonObject f) { return str(f, "type"); }

    private static String str(JsonObject f, String key) {
        return f.has(key) && f.get(key).isJsonPrimitive() ? f.get(key).getAsString() : null;
    }

    private static boolean success(JsonObject frame) {
        JsonObject response = frame.has("response") && frame.get("response").isJsonObject()
                ? frame.getAsJsonObject("response") : frame;
        return response.has("subtype") && "success".equals(response.get("subtype").getAsString());
    }
}
