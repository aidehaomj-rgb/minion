package com.minion.core.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.tools.ProcessCleanup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * CC 语义 Hook 系统（开发说明 v2.4 §4.7，hooks.enabled 默认 false）。
 *
 * <p>事件点：PreToolUse、PostToolUse、PostToolUseFailure、Notification、UserPromptSubmit、
 * SessionStart、SessionEnd、Stop、SubagentStart、SubagentStop、PreCompact、PostCompact、
 * PermissionRequest、InstructionsLoaded。
 *
 * <p>配置 {root}/.minion/cc-hooks.json（CC 结构）：{@code {"hooks":{"PreToolUse":[
 * {"matcher":"Bash|Write","hooks":[{"type":"command","command":"...","timeout":5000}]}]}}}。
 * 快照批准制沿 V68 信任模型：配置哈希经用户确认（approve）后才执行，改动即失效——
 * §4.7 的 10.9 可执行配置复核在此以更强口径先行落地。
 *
 * <p>matcher 三态：空/`*` 全匹配；纯字母数字+`|` 精确/列表；否则正则（非法记日志返回 false）。
 * stdin JSON：session_id、transcript_path、cwd、permission_mode、hook_event_name + tool_name/tool_input
 * （工具事件）+ tool_response（PostToolUse）。stdout JSON：continue、decision(approve|block)、reason、
 * systemMessage、permissionDecision(allow/deny/ask)、updatedInput（须重新过输入校验与权限管线）。
 *
 * <p><b>退出码 2 = 阻断</b>，stderr 反馈模型。超时默认 600s（SessionEnd 1.5s）；启动失败/输出畸形/
 * 进程异常杀进程树并按 hooks.timeoutBehavior（仅 ask|deny，无 continue）处理；headless 由调用方
 * 把 ask 转 deny。同事件多 hook 并行，权限决策按最严格合并。Hook allow 不跳过任何工具自检、
 * safetyCheck 或宿主权限回调（PreToolUse 先于权限管线但只收紧不放宽）。
 */
public final class HookSystem {

    public static final String PRE_TOOL_USE = "PreToolUse";
    public static final String POST_TOOL_USE = "PostToolUse";
    public static final String POST_TOOL_USE_FAILURE = "PostToolUseFailure";
    public static final String NOTIFICATION = "Notification";
    public static final String USER_PROMPT_SUBMIT = "UserPromptSubmit";
    public static final String SESSION_START = "SessionStart";
    public static final String SESSION_END = "SessionEnd";
    public static final String STOP = "Stop";
    public static final String SUBAGENT_START = "SubagentStart";
    public static final String SUBAGENT_STOP = "SubagentStop";
    public static final String PRE_COMPACT = "PreCompact";
    public static final String POST_COMPACT = "PostCompact";
    public static final String PERMISSION_REQUEST = "PermissionRequest";
    public static final String INSTRUCTIONS_LOADED = "InstructionsLoaded";

    private static final List<String> EVENTS = Collections.unmodifiableList(Arrays.asList(
            PRE_TOOL_USE, POST_TOOL_USE, POST_TOOL_USE_FAILURE, NOTIFICATION, USER_PROMPT_SUBMIT,
            SESSION_START, SESSION_END, STOP, SUBAGENT_START, SUBAGENT_STOP, PRE_COMPACT,
            POST_COMPACT, PERMISSION_REQUEST, INSTRUCTIONS_LOADED));

    private static final int MAX_CONFIG = 65536, MAX_OUTPUT = 16384;
    private static final long DEFAULT_TIMEOUT_MS = 600_000L;
    /** SessionEnd 默认 1.5s（规格 §4.7）：会话收尾不得被慢 hook 拖住 */
    private static final long SESSION_END_TIMEOUT_MS = 1500L;

    /** 单事件聚合结果：blocked/ask/提示与 updatedInput（调用方须重新校验后才可使用） */
    public static final class Outcome {
        public final boolean blocked;
        public final boolean ask;
        public final String reason;             // 阻断/询问原因（exit2=stderr；否则 stdout reason）
        public final String systemMessage;      // hook systemMessage（反馈 UI，可空）
        public final JsonObject updatedInput;   // PreToolUse updatedInput（可空；须重过校验/权限）
        public final int executed;              // 实际执行的 hook 数

        static final Outcome PASS = new Outcome(false, false, null, null, null, 0);

        Outcome(boolean blocked, boolean ask, String reason, String systemMessage,
                JsonObject updatedInput, int executed) {
            this.blocked = blocked;
            this.ask = ask;
            this.reason = reason;
            this.systemMessage = systemMessage;
            this.updatedInput = updatedInput;
            this.executed = executed;
        }
    }

    private static final class Hook {
        String event, matcher, command;
        long timeoutMs = DEFAULT_TIMEOUT_MS;
        boolean explicitTimeout;
    }

    private final Path root, config;
    private volatile List<Hook> approvedHooks;
    private volatile String approvedHash;
    private final AtomicLong epoch = new AtomicLong();
    /** §10.9 持续信任：附加后 approve 落盘指纹、tryResumeFromTrust 跨会话续用、执行前逐条复核 */
    private volatile com.minion.core.security.ProjectTrustStore trustStore;

    public HookSystem(Path projectRoot) {
        this.root = projectRoot.toAbsolutePath().normalize();
        this.config = root.resolve(".minion").resolve("cc-hooks.json");
    }

    /** §10.9 附加持久信任存储（SessionManager/Headless/Teammate 装配；null=旧会话内快照模型） */
    public void attachTrustStore(com.minion.core.security.ProjectTrustStore store) { this.trustStore = store; }
    public com.minion.core.security.ProjectTrustStore trustStore() { return trustStore; }

    /** 单 hook 的可执行指纹规格：id=event|matcher|command 压缩（重排不影响），引用脚本纳入内容 */
    com.minion.core.security.TrustedExecutable specOf(Hook h) {
        String rawId = h.event + "|" + (h.matcher == null ? "" : h.matcher) + "|" + h.command;
        return com.minion.core.security.TrustedExecutable.of(
                com.minion.core.security.TrustedExecutable.TYPE_HOOK,
                com.minion.core.security.TrustedExecutable.shortId(rawId),
                "project", h.command,
                java.util.Collections.<String>emptyList(),
                java.util.Collections.<String, String>emptyMap(),
                root.toString(),
                com.minion.core.security.TrustedExecutable.referencedScripts(h.command, root));
    }

    /**
     * §10.9 跨会话恢复：全部条目指纹与持久信任匹配才自动启用（未变化项免再批准）；
     * 任一变化/缺记录/库损坏 → false（走人工批准，approve 会重写整组指纹）。
     */
    public synchronized boolean tryResumeFromTrust() {
        com.minion.core.security.ProjectTrustStore ts = trustStore;
        if (ts == null || ts.corrupted()) return false;
        byte[] bytes;
        List<Hook> parsed;
        try {
            bytes = readConfig();
            parsed = parse(bytes);
        } catch (IOException e) {
            return false;   // 无配置/配置无效：保持禁用
        }
        for (Hook h : parsed) if (!ts.isTrusted(specOf(h))) return false;
        approvedHooks = parsed;
        approvedHash = sha256(bytes);
        return true;
    }

    public Path configFile() { return config; }

    public String configurationPreview() throws IOException {
        byte[] bytes = readConfig();
        parse(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 用户确认后启用（UI 路由专用）；附加信任库后批准同时整组落盘指纹（§10.9 持续信任，
     *  已删除条目的旧信任一并清除），下个会话 tryResumeFromTrust 免提示续用 */
    public synchronized void approve(String expectedHash) throws IOException {
        byte[] bytes = readConfig();
        String actual = sha256(bytes);
        if (!actual.equals(expectedHash)) throw new IOException("cc-hooks.json 在预览后已改变，请重新查看并批准");
        approvedHooks = parse(bytes);
        approvedHash = actual;
        com.minion.core.security.ProjectTrustStore ts = trustStore;
        if (ts != null) {
            List<com.minion.core.security.TrustedExecutable> specs =
                    new ArrayList<com.minion.core.security.TrustedExecutable>();
            for (Hook h : approvedHooks) specs.add(specOf(h));
            ts.replaceType(com.minion.core.security.TrustedExecutable.TYPE_HOOK, specs);
        }
    }

    public synchronized void disable() {
        approvedHooks = null;
        approvedHash = null;
    }

    public boolean enabled() { return approvedHooks != null; }

    /** 是否存在匹配该事件+目标（工具名等）的已批准 hook（先查后跑，便宜路径） */
    public boolean matches(String event, String target) {
        List<Hook> hooks = approvedHooks;
        if (hooks == null) return false;
        for (Hook h : hooks)
            if (h.event.equals(event) && matcherMatches(h.matcher, target)) return true;
        return false;
    }

    /**
     * 执行事件全部匹配 hook（并行），最严格合并。target 为工具名（工具事件）或空串。
     * payload 附加进 stdin JSON（tool_input/tool_response 由调用方放入）。
     * blocked=true 时调用方必须拒绝后续动作；ask=true 时 headless 场景按 deny 处理。
     */
    public Outcome run(String event, String target, JsonObject payload) throws InterruptedException {
        List<Hook> hooks = approvedHooks;
        if (hooks == null) return Outcome.PASS;
        List<Hook> matched = new ArrayList<Hook>();
        for (Hook h : hooks) if (h.event.equals(event) && matcherMatches(h.matcher, target)) matched.add(h);
        if (matched.isEmpty()) return Outcome.PASS;

        long myEpoch = epoch.get();
        JsonObject stdin = new JsonObject();
        if (payload != null) for (String key : payload.keySet()) stdin.add(key, payload.get(key));
        stdin.addProperty("hook_event_name", event);
        stdin.addProperty("cwd", root.toString());

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, matched.size()));
        List<Future<Outcome>> futures = new ArrayList<Future<Outcome>>();
        try {
            for (final Hook h : matched)
                futures.add(pool.submit(() -> executeOne(h, stdin, myEpoch)));
            boolean blocked = false, ask = false, proceedStop = false;
            String reason = null, systemMessage = null;
            JsonObject updated = null;
            for (Future<Outcome> f : futures) {
                Outcome one;
                try { one = f.get(); }
                catch (ExecutionException e) { one = new Outcome(false, true, "hook 执行异常", null, null, 1); }
                if (one.blocked) blocked = true;
                if (one.ask) ask = true;
                // 首个非空原因（阻断或 ask 的 reason 都要带回给调用方）
                if (reason == null && one.reason != null) reason = one.reason;
                if (one.systemMessage != null && systemMessage == null) systemMessage = one.systemMessage;
                if (one.updatedInput != null && updated == null) updated = one.updatedInput;
            }
            return new Outcome(blocked, ask, reason, systemMessage, updated, matched.size());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 单 hook 执行 ====================

    private Outcome executeOne(Hook h, JsonObject stdin, long myEpoch) {
        if (Thread.currentThread().isInterrupted()) return new Outcome(false, true, "hook 取消", null, null, 1);
        // §10.9 执行前逐条复核：指纹变化（git pull/外部编辑/引用脚本内容变化）→ 不执行新内容，
        // 该条跳过（视为不存在），其余未变化 hook 照常；零子进程保证——检查在 ProcessBuilder 之前
        com.minion.core.security.ProjectTrustStore ts = trustStore;
        if (ts != null && !ts.isTrusted(specOf(h))) {
            DiagnosticLog.warn("hooks", "hook 指纹变化未重新批准，跳过执行: " + clip(h.command, 120));
            return new Outcome(false, false, null,
                    "Hook 配置已变化，未重新批准前不执行（" + clip(h.command, 80) + "）", null, 0);
        }
        // SessionEnd 未显式配 timeout 时默认 1.5s（会话收尾不得被慢 hook 拖住）
        long timeout = SESSION_END.equals(h.event) && !h.explicitTimeout
                ? Math.min(SESSION_END_TIMEOUT_MS, h.timeoutMs) : h.timeoutMs;
        Process p = null;
        try {
            ProcessBuilder pb = onWindows()
                    ? new ProcessBuilder("cmd.exe", "/c", h.command)
                    : new ProcessBuilder("sh", "-c", h.command);
            pb.directory(root.toFile());
            p = pb.start();
            final Process process = p;
            final ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
            Thread drain = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] b = new byte[2048]; int n;
                    while ((n = in.read(b)) != -1) synchronized (stdout) {
                        int keep = Math.min(n, Math.max(0, MAX_OUTPUT - stdout.size()));
                        if (keep > 0) stdout.write(b, 0, keep);
                    }
                } catch (IOException ignored) { }
            }, "minion-cchook-out");
            Thread drainErr = new Thread(() -> {
                try (InputStream in = process.getErrorStream()) {
                    byte[] b = new byte[2048]; int n;
                    while ((n = in.read(b)) != -1) synchronized (stderr) {
                        int keep = Math.min(n, Math.max(0, MAX_OUTPUT - stderr.size()));
                        if (keep > 0) stderr.write(b, 0, keep);
                    }
                } catch (IOException ignored) { }
            }, "minion-cchook-err");
            drain.setDaemon(true);
            drainErr.setDaemon(true);
            drain.start();
            drainErr.start();
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdin.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException pipeClosed) { /* hook 提前退出不写 stdin：按其退出码处理 */ }
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                ProcessCleanup.terminate(process);
                DiagnosticLog.warn("hooks", "hook 超时被杀（" + h.event + "）: " + h.command);
                return new Outcome(false, true, "hook 超时（" + timeout + "ms），按 ask 处理", null, null, 1);
            }
            if (epoch.get() != myEpoch) return new Outcome(false, true, "hook 已取消", null, null, 1);
            joinQuietly(drain);
            joinQuietly(drainErr);
            int exit = process.exitValue();
            String outText, errText;
            synchronized (stdout) { outText = new String(stdout.toByteArray(), StandardCharsets.UTF_8); }
            synchronized (stderr) { errText = new String(stderr.toByteArray(), StandardCharsets.UTF_8); }
            if (exit == 2)   // 退出码 2 = 阻断，stderr 反馈模型（规格 §4.7）
                return new Outcome(true, false, firstNonEmpty(errText, outText, "hook 阻断（exit 2）"), null, null, 1);
            if (exit != 0)
                return new Outcome(false, true, "hook 非零退出 " + exit + ": " + firstNonEmpty(errText, "", ""), null, null, 1);
            return parseStdout(outText);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            if (p != null) ProcessCleanup.terminate(p);
            return new Outcome(false, true, "hook 被中断", null, null, 1);
        } catch (Exception failed) {
            if (p != null) ProcessCleanup.terminate(p);
            DiagnosticLog.warn("hooks", "hook 启动/执行失败: " + failed.getMessage());
            return new Outcome(false, true, "hook 执行失败，按 ask 处理: " + failed.getMessage(), null, null, 1);
        }
    }

    /** stdout JSON → 决策；畸形输出 fail-ask（不 continue，规格 §4.7） */
    private static Outcome parseStdout(String text) {
        if (text == null || text.trim().isEmpty()) return new Outcome(false, false, null, null, null, 1);
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) return malformed(text);
            JsonObject o = e.getAsJsonObject();
            boolean goOn = !o.has("continue") || !o.get("continue").isJsonPrimitive()
                    || o.get("continue").getAsBoolean();
            String reason = str(o, "reason");
            String systemMessage = str(o, "systemMessage");
            JsonObject updated = o.has("updatedInput") && o.get("updatedInput").isJsonObject()
                    ? o.getAsJsonObject("updatedInput") : null;
            String decision = str(o, "decision").toLowerCase(Locale.ROOT);
            if ("block".equals(decision) || !goOn)
                return new Outcome(true, false, reason != null ? reason : "hook 阻断（decision=block / continue=false）",
                        systemMessage, updated, 1);
            String perm = str(o, "permissionDecision").toLowerCase(Locale.ROOT);
            if ("deny".equals(perm)) return new Outcome(true, false, reason != null ? reason : "hook permissionDecision=deny", systemMessage, updated, 1);
            if ("ask".equals(perm)) return new Outcome(false, true, reason, systemMessage, updated, 1);
            return new Outcome(false, false, reason, systemMessage, updated, 1);   // allow/approve：只表示同意，不放宽
        } catch (RuntimeException malformed) {
            return malformed(text);
        }
    }

    private static Outcome malformed(String text) {
        DiagnosticLog.warn("hooks", "hook stdout 非法 JSON（按 ask 处理）: " + clip(text, 200));
        return new Outcome(false, true, "hook stdout 非法 JSON，按 ask 处理", null, null, 1);
    }

    // ==================== matcher / 配置 ====================

    /** 空/`*` 全匹配；纯字母数字+`|` 精确列表；否则正则（非法记日志返回 false=不执行，fail-closed） */
    static boolean matcherMatches(String matcher, String target) {
        if (target == null) target = "";
        String m = matcher == null ? "" : matcher.trim();
        if (m.isEmpty() || "*".equals(m)) return true;
        if (m.matches("[A-Za-z0-9_|]+")) {
            for (String part : m.split("\\|")) if (part.equals(target)) return true;
            return false;
        }
        try {
            return Pattern.compile(m).matcher(target).find();
        } catch (PatternSyntaxException invalid) {
            DiagnosticLog.warn("hooks", "matcher 非正则且非精确列表，不执行: " + m);
            return false;
        }
    }

    private byte[] readConfig() throws IOException {
        if (!Files.isRegularFile(config) || Files.size(config) > MAX_CONFIG)
            throw new IOException("cc-hooks.json 不存在或超过 64KiB: " + config);
        return Files.readAllBytes(config);
    }

    private static List<Hook> parse(byte[] bytes) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject events = root.getAsJsonObject("hooks");
            if (events == null) throw new IllegalArgumentException("缺少 hooks 键");
            List<Hook> out = new ArrayList<Hook>();
            for (String event : events.keySet()) {
                if (!EVENTS.contains(event)) throw new IllegalArgumentException("未知事件: " + event);
                JsonArray groups = events.getAsJsonArray(event);
                for (JsonElement g : groups) {
                    JsonObject group = g.getAsJsonObject();
                    String matcher = str(group, "matcher");
                    JsonArray commands = group.getAsJsonArray("hooks");
                    if (commands == null) throw new IllegalArgumentException(event + " 组缺少 hooks 数组");
                    for (JsonElement c : commands) {
                        JsonObject cmd = c.getAsJsonObject();
                        Hook h = new Hook();
                        h.event = event;
                        h.matcher = matcher;
                        if (!"command".equals(str(cmd, "type")))
                            throw new IllegalArgumentException("仅支持 type=command");
                        h.command = str(cmd, "command");
                        if (h.command.isEmpty() || h.command.length() > 4096)
                            throw new IllegalArgumentException("command 非法");
                        h.timeoutMs = cmd.has("timeout") && cmd.get("timeout").isJsonPrimitive()
                                ? cmd.get("timeout").getAsLong() : DEFAULT_TIMEOUT_MS;
                        h.explicitTimeout = cmd.has("timeout") && cmd.get("timeout").isJsonPrimitive();
                        if (h.timeoutMs < 100 || h.timeoutMs > DEFAULT_TIMEOUT_MS)
                            throw new IllegalArgumentException("timeout 范围 100.." + DEFAULT_TIMEOUT_MS + "ms");
                        out.add(h);
                    }
                }
            }
            if (out.size() > 32) throw new IllegalArgumentException("hook 总数上限 32");
            return out;
        } catch (RuntimeException e) {
            throw new IOException("cc-hooks.json 无效: " + e.getMessage(), e);
        }
    }

    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static String firstNonEmpty(String a, String b, String c) {
        if (a != null && !a.trim().isEmpty()) return a;
        if (b != null && !b.trim().isEmpty()) return b;
        return c;
    }

    private static String clip(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private static void joinQuietly(Thread t) {
        try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder();
            for (byte b : d) s.append(String.format("%02x", b & 255));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
