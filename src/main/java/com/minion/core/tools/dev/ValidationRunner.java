package com.minion.core.tools.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.minion.core.tools.ProcessCleanup;
import com.minion.core.tools.python.PythonRuntime;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 执行一次独立验证。构造无副作用；调用方仍须通过 Build 的高危确认。 */
public final class ValidationRunner {
    public static final String TYPE = "minion.validation.v1";
    public static final int PREVIEW_CHARS = 6000;
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private final PythonRuntime python;

    public ValidationRunner(PythonRuntime python) { this.python = python; }

    /** 单次报告无共享可变状态；不同调用不会复用上次日志和结果。 */
    public JsonObject run(Path directory, String kind, String command, int timeoutSeconds) {
        long started = System.currentTimeMillis(), clock = System.nanoTime();
        Path root = directory.toAbsolutePath().normalize(), log = null, reportPath = null;
        String id = UUID.randomUUID().toString(), status = "blocked", reason = "";
        JsonObject report = initial(root, id, kind, started);
        SourceVersion.Snapshot before = null;
        ValidationDiscovery.Plan plan = null;
        Process process = null;
        Integer exit = null;
        boolean cancelled = false, timedOut = false, restoreInterrupt = Thread.interrupted();
        ValidationReports.Evidence evidence = new ValidationReports.Evidence();
        try {
            root = root.toRealPath();
            report.getAsJsonObject("scope").addProperty("root", root.toString());
            Path runs = root.resolve(".minion/validation-runs");
            ValidationDiscovery.requireInside(root, runs);
            Files.createDirectories(runs);
            ValidationDiscovery.requireInside(root, runs);
            Path run = Files.createDirectory(runs.resolve(id));
            log = run.resolve("process.log"); reportPath = run.resolve("report.json");
            report.addProperty("logPath", log.toString()); report.addProperty("reportPath", reportPath.toString());
            Files.write(log, ("runId=" + id + "\nkind=" + kind + "\n").getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE_NEW);
            before = SourceVersion.capture(root);
            report.addProperty("sourceDigest", before.digest);
            report.addProperty("sourceFileCount", before.fileCount);
            if (restoreInterrupt || Thread.interrupted()) { restoreInterrupt = true; cancelled = true; status = "cancelled"; reason = "验证启动前已取消"; }
            else {
                plan = ValidationDiscovery.plan(root, run, kind, command == null ? "" : command, python);
                report.addProperty("runner", plan.runner);
                report.addProperty("logEncoding", plan.encoding.name());
                if (!plan.problem.isEmpty()) { status = plan.problemStatus; reason = plan.problem; }
                else {
                    requireCommandBounded(plan.command);
                    if("python_auto".equals(plan.runner))report.addProperty("generatedBytecodeCleared",ValidationDiscovery.freshPythonBytecode(root));
                    report.add("command", JSON.toJsonTree(plan.command));
                    List<Path> locations = new ArrayList<Path>(plan.reports);
                    Map<Path, String> old = ValidationReports.snapshot(root, locations);
                    ProcessBuilder builder = new ProcessBuilder(plan.command).directory(root.toFile()).redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
                    builder.environment().put("PYTHONIOENCODING", "utf-8");
                    builder.environment().put("PYTHONUTF8", "1");
                    builder.environment().put("PYTHONUNBUFFERED", "1");
                    if("python_auto".equals(plan.runner))builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
                    builder.environment().put("CI", "true");
                    builder.environment().put("npm_config_audit", "false");
                    builder.environment().put("npm_config_fund", "false");
                    configurePythonPath(builder, plan.command.get(0));
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("验证启动前已取消");
                    process = builder.start();
                    report.addProperty("processStarted", true);
                    process.getOutputStream().close(); // 不把交互提示挂到超时。
                    try {
                        if (!process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
                            timedOut = true; status = "timeout"; reason = "验证超时，已调用 ProcessCleanup 回收进程树";
                            ProcessCleanup.terminate(process);
                        } else status = "completed";
                    } catch (InterruptedException e) {
                        cancelled = true; restoreInterrupt = true; status = "cancelled";
                        reason = "验证被取消，已调用 ProcessCleanup 回收进程树";
                        ProcessCleanup.terminate(process);
                    }
                    if (process.isAlive()) {
                        try { process.waitFor(5, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { restoreInterrupt = true; }
                    }
                    if (!process.isAlive()) exit = process.exitValue();
                    report.addProperty("processExited", !process.isAlive());
                    if ("maven".equals(plan.runner) || "gradle".equals(plan.runner))
                        locations.addAll(ValidationDiscovery.nativeReportDirectories(root, plan.runner));
                    if ("test".equals(kind)) {
                        evidence = ValidationReports.collect(root, locations, old);
                        if (plan.unittestResult != null && Files.isRegularFile(plan.unittestResult)) {
                            evidence.counts = ValidationReports.unittest(plan.unittestResult, id);
                            evidence.files.add(plan.unittestResult);
                            report.addProperty("runner", "unittest");
                        } else if ("python_auto".equals(plan.runner) && evidence.counts != null) report.addProperty("runner", "pytest");
                    }
                    if (!timedOut && !cancelled) {
                        status = resultStatus(kind, exit, evidence.counts, "python_auto".equals(plan.runner));
                        if ("command_succeeded".equals(status) && "test".equals(kind)) reason = "命令退出 0，但本次没有可核验的真实测试数；不能作为 test/passed";
                        else if ("no_tests".equals(status)) reason = "本次未执行测试；不能作为测试通过";
                        else if ("skipped".equals(status)) reason = "本次测试全部跳过；未证明功能正确";
                        else if ("failed".equals(status)) reason = "验证失败；查看完整日志（缺依赖或离线缓存也是失败，不是成功）";
                    }
                }
            }
        } catch (Exception e) {
            if (process != null && process.isAlive()) ProcessCleanup.terminate(process);
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                cancelled = true; restoreInterrupt = true; status = "cancelled";
            } else if (!timedOut && !cancelled) status = process == null ? "blocked" : "report_error";
            reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            // 保持取消语义，但先清掉 interrupt，避免中断文件通道而丢失取消报告。
            boolean endInterrupt = Thread.interrupted();
            restoreInterrupt |= endInterrupt;
            if (endInterrupt && !cancelled) { cancelled = true; status = "cancelled"; reason = "验证收口时被取消"; }
            if (process != null && process.isAlive()) ProcessCleanup.terminate(process);
            report.addProperty("status", status);
            report.add("exitCode", exit == null ? JsonNull.INSTANCE : JSON.toJsonTree(exit));
            report.addProperty("timedOut", timedOut); report.addProperty("cancelled", cancelled);
            report.addProperty("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - clock));
            report.addProperty("finishedAtMillis", System.currentTimeMillis());
            if (evidence.counts != null) {
                report.addProperty("tests", evidence.counts.tests); report.addProperty("failures", evidence.counts.failures);
                report.addProperty("errors", evidence.counts.errors); report.addProperty("skipped", evidence.counts.skipped);
            }
            report.addProperty("staleReportsIgnored", evidence.staleFiles);
            JsonArray files = new JsonArray();
            for (int i = 0; i < Math.min(evidence.files.size(), 100); i++) files.add(evidence.files.get(i).toString());
            report.add("testReportPaths", files); report.addProperty("testReportCount", evidence.files.size());
            try {
                SourceVersion.Snapshot after = SourceVersion.capture(root);
                report.addProperty("sourceDigestAfter", after.digest);
                boolean unchanged = before != null && before.digest.equals(after.digest);
                report.addProperty("sourceUnchanged", unchanged);
                if (!unchanged && ("passed".equals(status) || "command_succeeded".equals(status))) {
                    status = "stale"; report.addProperty("status", status); reason = "验证期间源码发生变化，结果不能验收当前源码";
                }
            } catch (Exception e) {
                report.addProperty("sourceUnchanged", false);
                if (!cancelled && !timedOut) status = "blocked";
                report.addProperty("status", status); reason += "；源码版本核验失败: " + e.getMessage();
            }
            report.addProperty("functionalVerified", "test".equals(kind) && "passed".equals(status));
            report.addProperty("reason", bounded(reason, 2000));
            report.addProperty("meaning", "test/passed 仅证明本次已运行测试；build 仅构建；syntax_check 仅语法；command/未知计数不证明功能正确");
            try {
                if (log != null) {
                    Charset charset = plan == null ? StandardCharsets.UTF_8 : plan.encoding;
                    Files.write(log, ("\n[minion] status=" + status + "\n" + reason + "\n").getBytes(charset), StandardOpenOption.APPEND);
                    report.addProperty("logPreview", tail(log, charset));
                    report.addProperty("logBytes", Files.size(log));
                    report.addProperty("logDigest", "sha256:" + SourceVersion.fileDigest(log));
                }
                report.addProperty("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - clock));
                report.addProperty("finishedAtMillis", System.currentTimeMillis());
                if (reportPath != null) Files.write(reportPath, JSON.toJson(report).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            } catch (Exception e) {
                report.addProperty("status", "blocked"); report.addProperty("functionalVerified", false);
                report.addProperty("reason", "完整日志/报告落盘失败，不可验收: " + bounded(e.toString(), 1000));
            }
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
        return report;
    }

    /** 供主流程收口：验证报告属于指定项目、日志未被换掉、当前源码仍匹配。
     * 仅核对证据版本，不代替 kind/status/任务归属及验收判断，也不是签名认证。
     */
    public static boolean verifyReport(Path directory, Path reportPath) {
        try {
            Path root = directory.toRealPath(), path = reportPath.toRealPath();
            ValidationDiscovery.requireInside(root, path);
            ValidationDiscovery.requireInside(root, root.resolve(".minion/validation-runs"));
            if (!path.startsWith(root.resolve(".minion/validation-runs").toRealPath()) || !"report.json".equals(path.getFileName().toString())) return false;
            JsonObject report = ValidationDiscovery.json(path);
            if (!TYPE.equals(report.get("type").getAsString()) || !SourceVersion.POLICY.equals(report.get("sourcePolicy").getAsString())
                    || !root.equals(Paths.get(report.getAsJsonObject("scope").get("root").getAsString()).toRealPath())
                    || !path.equals(Paths.get(report.get("reportPath").getAsString()).toRealPath())
                    || !path.getParent().getFileName().toString().equals(report.get("runId").getAsString())
                    || !report.get("sourceDigest").equals(report.get("sourceDigestAfter"))
                    || !report.get("sourceUnchanged").getAsBoolean()) return false;
            Path log = Paths.get(report.get("logPath").getAsString()).toRealPath();
            if (!log.equals(path.getParent().resolve("process.log")) || !report.get("logDigest").getAsString().equals("sha256:" + SourceVersion.fileDigest(log))) return false;
            return SourceVersion.verify(root, report.get("sourceDigest").getAsString());
        } catch (Exception e) { return false; }
    }

    static String resultStatus(String kind, Integer exit, ValidationReports.Counts counts, boolean pytestPossible) {
        if ("test".equals(kind)) {
            if (counts != null && (counts.failures > 0 || counts.errors > 0)) return "failed";
            if (counts != null && counts.tests == 0 && (Integer.valueOf(0).equals(exit) || (pytestPossible && Integer.valueOf(5).equals(exit)))) return "no_tests";
            if (pytestPossible && Integer.valueOf(5).equals(exit)) return "no_tests";
            if (!Integer.valueOf(0).equals(exit)) return "failed";
            if (counts == null) return "command_succeeded";
            if (counts.skipped == counts.tests) return "skipped";
            return "passed";
        }
        if (!Integer.valueOf(0).equals(exit)) return "failed";
        return "command".equals(kind) ? "command_succeeded" : "passed";
    }

    static String serialize(JsonObject report) { return JSON.toJson(report); }

    private static JsonObject initial(Path root, String id, String kind, long started) {
        JsonObject r = new JsonObject();
        r.addProperty("type", TYPE); r.addProperty("runId", id); r.addProperty("kind", kind);
        r.addProperty("status", "blocked"); r.addProperty("startedAtMillis", started);
        JsonObject scope = new JsonObject(); scope.addProperty("root", root.toString()); r.add("scope", scope);
        r.addProperty("sourcePolicy", SourceVersion.POLICY);
        r.add("excludedDirectories", JSON.toJsonTree(SourceVersion.excludedDirectories()));
        r.addProperty("sourceExclusionNote", ".minion 仅 validation.json 参与摘要；.pyc/.pyo/.class 排除；输出/依赖目录内源码不受覆盖；符号链接拒绝");
        for (String key : new String[]{"exitCode", "tests", "failures", "errors", "skipped", "sourceDigest", "sourceDigestAfter", "logPath", "reportPath"}) r.add(key, JsonNull.INSTANCE);
        r.addProperty("sourceUnchanged", false); r.addProperty("processStarted", false);
        r.addProperty("logEncoding", StandardCharsets.UTF_8.name());
        return r;
    }

    private static void requireCommandBounded(List<String> command) throws IOException {
        int length = 0;
        for (String arg : command) length += arg.length();
        if (length > 16000) throw new IOException("验证命令超过 16000 字符，请改用项目内脚本");
    }
    private static String tail(Path log, Charset encoding) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            int count = (int) Math.min(length, PREVIEW_CHARS * 4L);
            byte[] bytes = new byte[count]; file.seek(length - count); file.readFully(bytes);
            String s = new String(bytes, encoding);
            if (s.length() > PREVIEW_CHARS) s = s.substring(s.length() - PREVIEW_CHARS);
            return length > count || s.length() >= PREVIEW_CHARS ? "[仅日志尾部，完整原始字节见 logPath]\n" + s : s;
        }
    }
    private static String bounded(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    private static void configurePythonPath(ProcessBuilder pb, String executable) {
        // 复用既有 Conda DLL 搜索目录约定，不修改 PythonRuntime 或系统环境。
        Path prefix = Paths.get(executable).toAbsolutePath().getParent();
        if (prefix == null || !Files.isRegularFile(prefix.resolve("python.exe"))) return;
        List<String> paths = new ArrayList<String>();
        for (String sub : new String[]{"", "DLLs", "Library/bin", "Scripts", "Library/usr/bin"}) {
            Path candidate = prefix.resolve(sub);
            if (Files.isDirectory(candidate)) paths.add(candidate.toString());
        }
        String old = pb.environment().get("PATH");
        if (old == null) old = pb.environment().get("Path");
        if (old != null) paths.add(old);
        pb.environment().put("PATH", String.join(java.io.File.pathSeparator, paths));
        if (Files.isDirectory(prefix.resolve("conda-meta"))) pb.environment().put("CONDA_PREFIX", prefix.toString());
    }
}
