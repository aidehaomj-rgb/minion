package com.minion.core.tools.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Python 测试使用已有解释器，不安装 pytest；环境缺失显式 JUnit skip。 */
public class ValidationRunnerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void syntacticallyValidButFalseAssertionMustFailRealTests() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("test_logic.py"), "import unittest\nclass Check(unittest.TestCase):\n def test_wrong(self):\n  self.assertEqual(1,2)\n");
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertFalse(result.output, result.ok);
        JsonObject report = parse(result);
        assertEquals("failed", status(report)); assertEquals(1, report.get("tests").getAsInt());
        assertEquals(1, report.get("failures").getAsInt()); assertEquals(1, report.get("exitCode").getAsInt());
        assertEvidence(root, report);
        assertFalse(report.get("functionalVerified").getAsBoolean());
    }

    @Test public void realTestsPassAndSourceEditInvalidatesEvidence() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("test_logic.py"), "import unittest\nclass Check(unittest.TestCase):\n def test_ok(self):\n  self.assertEqual(2,2)\n");
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertTrue(result.output, result.ok);
        JsonObject report = parse(result);
        assertEquals("passed", status(report)); assertEquals(1, report.get("tests").getAsInt());
        assertTrue(report.get("functionalVerified").getAsBoolean()); assertEvidence(root, report);
        JsonObject verify = ValidationDiscoveryTest.args("verify"); verify.addProperty("reportPath", report.get("reportPath").getAsString());
        assertTrue(tool(root).execute(verify).ok);
        SourceVersionTest.write(root.resolve("main.py"), "x=2\n");
        assertFalse(ValidationRunner.verifyReport(root, path(report, "reportPath")));
        assertFalse(tool(root).execute(verify).ok);
    }

    @Test public void noTestsConfiguredIsNotSuccessfulAndDoesNotStartProcess() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("main.py"), "x=1\n");
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertFalse(result.ok);
        JsonObject report = parse(result);
        assertEquals("not_configured", status(report)); assertFalse(report.get("processStarted").getAsBoolean());
        assertTrue(report.get("tests").isJsonNull()); assertEvidence(root, report);
    }

    @Test public void zeroDiscoveredTestsIsNotPassed() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("test_empty.py"), "# no tests here\n");
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertFalse(result.ok);
        JsonObject report = parse(result);
        assertEquals(result.output, "no_tests", status(report));
        assertFalse(report.get("functionalVerified").getAsBoolean());
    }

    @Test public void autoDiscoveryNeverImportsOldTestsFromOutputDirectories() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("test_current.py"), "import unittest\nclass Current(unittest.TestCase):\n def test_ok(self):\n  self.assertEqual(1,1)\n");
        SourceVersionTest.write(root.resolve("target/__init__.py"), "raise RuntimeError('stale-output-imported')\n");
        SourceVersionTest.write(root.resolve("target/test_old.py"), "raise RuntimeError('stale-output-imported')\n");
        SourceVersionTest.write(root.resolve("module/target/test_old.py"), "raise RuntimeError('stale-output-imported')\n");
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertTrue(result.output, result.ok);
        JsonObject r = parse(result);
        assertEquals("passed", status(r)); assertEquals(1, r.get("tests").getAsInt()); assertEvidence(root, r);
    }

    @Test public void allSkippedTestsAreNotPassed() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("test_skip.py"), "import unittest\nclass Check(unittest.TestCase):\n @unittest.skip('no local service')\n def test_skip(self):\n  self.fail('not run')\n");
        JsonObject r = parse(tool(root).execute(ValidationDiscoveryTest.args("test")));
        assertEquals("skipped", status(r)); assertEquals(1, r.get("skipped").getAsInt());
        assertFalse(r.get("functionalVerified").getAsBoolean());
    }

    @Test public void importErrorMustNotBecomeZeroTestSuccess() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("test_import.py"), "import minion_deliberately_missing_dependency_xyz\n");
        JsonObject r = parse(tool(root).execute(ValidationDiscoveryTest.args("test")));
        assertEquals("failed", status(r));
        assertTrue(r.toString(), r.get("exitCode").getAsInt() != 0);
        assertTrue(r.toString(), r.get("logPreview").getAsString().contains("minion_deliberately_missing_dependency_xyz"));
    }

    @Test public void syntaxCheckIsSeparateFromTestsAndFindsSyntaxErrors() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("main.py"), "x=1\n");
        ToolResult success = tool(root).execute(ValidationDiscoveryTest.args("syntax_check"));
        assertTrue(success.output, success.ok);
        JsonObject syntax = parse(success);
        assertEquals("syntax_check", syntax.get("kind").getAsString());
        assertFalse(syntax.get("functionalVerified").getAsBoolean()); assertTrue(syntax.get("tests").isJsonNull());
        assertEvidence(root, syntax);
        SourceVersionTest.write(root.resolve("main.py"), "x=\n");
        ToolResult failure = tool(root).execute(ValidationDiscoveryTest.args("syntax_check"));
        assertFalse(failure.output, failure.ok); assertEquals("failed", status(parse(failure)));
    }

    @Test public void customExitZeroWithoutCountsIsCommandSucceededNotPassed() throws Exception {
        Path root = project();
        configure(root, "test", "print('custom-validation-did-run')", null, "UTF-8");
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertFalse(result.ok);
        JsonObject r = parse(result);
        assertEquals("command_succeeded", status(r)); assertTrue(r.get("tests").isJsonNull());
        assertEquals(0, r.get("exitCode").getAsInt()); assertEvidence(root, r);
    }

    @Test public void unchangedOldJUnitCannotTurnExitZeroIntoPassed() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("target/old.xml"), "<testsuite tests='999' failures='0'/>");
        configure(root, "test", "print('no tests run')", "target/old.xml", "UTF-8");
        JsonObject r = parse(tool(root).execute(ValidationDiscoveryTest.args("test")));
        assertEquals("command_succeeded", status(r)); assertTrue(r.get("tests").isJsonNull());
        assertEquals(1, r.get("staleReportsIgnored").getAsInt());
    }

    @Test public void sourceChangeDuringExecutionMakesBuildEvidenceStale() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve("main.py"), "x=1\n");
        configure(root, "build", "open('main.py','w').write('x=2\\n')", null, "UTF-8");
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("build"));
        assertFalse(result.ok);
        JsonObject r = parse(result);
        assertEquals("stale", status(r)); assertFalse(r.get("sourceUnchanged").getAsBoolean());
        assertNotEquals(r.get("sourceDigest"), r.get("sourceDigestAfter"));
        assertFalse(ValidationRunner.verifyReport(root, path(r, "reportPath")));
    }

    @Test public void fullLogPersistsButPreviewIsBoundedAndEachRunIsUnique() throws Exception {
        Path root = project();
        configure(root, "build", "print('A'*200000); print('tail-marker')", null, "UTF-8");
        JsonObject first = parse(tool(root).execute(ValidationDiscoveryTest.args("build")));
        JsonObject second = parse(tool(root).execute(ValidationDiscoveryTest.args("build")));
        assertEquals("passed", status(first)); assertFalse(first.get("functionalVerified").getAsBoolean());
        assertTrue(Files.size(path(first, "logPath")) > 200000);
        assertTrue(first.get("logPreview").getAsString().length() < ValidationRunner.PREVIEW_CHARS + 100);
        assertTrue(first.get("logPreview").getAsString().contains("tail-marker"));
        assertNotEquals(first.get("reportPath"), second.get("reportPath"));
        assertEquals(first.get("sourceDigest"), second.get("sourceDigest"));
        assertEvidence(root, first); assertEvidence(root, second);
        SourceVersionTest.write(path(first, "logPath"), "tampered");
        assertFalse(ValidationRunner.verifyReport(root, path(first, "reportPath")));
    }

    @Test public void configuredWindowsEncodingPreservesChineseLog() throws Exception {
        Path root = project();
        configure(root, "build", "import sys; sys.stdout.buffer.write('中文日志'.encode('gbk'))", null, "GBK");
        JsonObject r = parse(tool(root).execute(ValidationDiscoveryTest.args("build")));
        assertEquals("passed", status(r)); assertEquals("GBK", r.get("logEncoding").getAsString());
        assertTrue(r.toString(), r.get("logPreview").getAsString().contains("中文日志"));
        assertEvidence(root, r);
    }

    @Test public void missingExecutableAndBadConfigAreStructuredFailures() throws Exception {
        Path root = project();
        SourceVersionTest.write(root.resolve(".minion/validation.json"), "{\"version\":1,\"test\":{\"command\":[\"minion-nonexistent-executable-xyzz\"]}}");
        JsonObject missing = parse(tool(root).execute(ValidationDiscoveryTest.args("test")));
        assertEquals("blocked", status(missing)); assertTrue(missing.get("exitCode").isJsonNull());
        assertFalse(missing.get("processStarted").getAsBoolean()); assertEvidence(root, missing);
        SourceVersionTest.write(root.resolve(".minion/validation.json"), "not-json");
        JsonObject invalid = parse(tool(root).execute(ValidationDiscoveryTest.args("test")));
        assertEquals("blocked", status(invalid)); assertFalse(invalid.get("processStarted").getAsBoolean());
    }

    @Test public void cancellationBeforeStartWritesReportAndPreservesInterrupt() throws Exception {
        Path root = project();
        try {
            Thread.currentThread().interrupt();
            JsonObject r = new ValidationRunner(python()).run(root, "test", "", 30);
            assertEquals("cancelled", status(r)); assertTrue(r.get("cancelled").getAsBoolean());
            assertFalse(r.get("processStarted").getAsBoolean()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void timeoutKillsActualParentAndChildProcesses() throws Exception { checkTreeCleanup(false); }
    @Test public void cancellationKillsActualParentAndChildProcesses() throws Exception { checkTreeCleanup(true); }

    private void checkTreeCleanup(boolean cancel) throws Exception {
        Assume.assumeTrue("现有 ProcessCleanup 的进程树实现针对 Windows；其他平台不伪称通过", ValidationDiscovery.windows());
        Path root = project();
        String code = "import subprocess,sys,time,os\nfrom pathlib import Path\nPath('target').mkdir(exist_ok=True)\n"
                + "child=subprocess.Popen([sys.executable,'-c',\"import time;from pathlib import Path;time.sleep(3);Path('target/child-leaked').write_text('leaked')\"])\n"
                + "Path('target/pids.txt').write_text(str(os.getpid())+' '+str(child.pid))\ntime.sleep(60)\n";
        configure(root, "build", code, null, "UTF-8");
        final AtomicReference<JsonObject> result = new AtomicReference<JsonObject>();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final ValidationRunner runner = new ValidationRunner(python());
        Thread worker = new Thread(() -> { result.set(runner.run(root, "build", "", cancel ? 30 : 1)); interrupted.set(Thread.currentThread().isInterrupted()); });
        worker.start();
        try {
            long deadline = System.currentTimeMillis() + 7000;
            while (!Files.exists(root.resolve("target/pids.txt")) && worker.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(30);
            assertTrue("测试进程必须确实已生成子进程", Files.exists(root.resolve("target/pids.txt")));
            if (cancel) worker.interrupt();
            worker.join(12000);
            assertFalse("运行器必须回收并返回", worker.isAlive());
            JsonObject r = result.get(); assertNotNull(r);
            assertEquals(r.toString(), cancel ? "cancelled" : "timeout", status(r));
            assertTrue(r.toString(), r.get("processExited").getAsBoolean());
            if (cancel) assertTrue(interrupted.get());
            Thread.sleep(3500);
            assertFalse("子进程不可存活写入泄漏标记", Files.exists(root.resolve("target/child-leaked")));
            assertEvidence(root, r);
        } finally {
            if (worker.isAlive()) { worker.interrupt(); worker.join(7000); }
            // 失败清理只针对本测试记录的数值 PID，不触碰其他进程。
            if (Files.isRegularFile(root.resolve("target/pids.txt"))) {
                for (String pid : new String(Files.readAllBytes(root.resolve("target/pids.txt")), StandardCharsets.UTF_8).trim().split(" "))
                    if (pid.matches("[0-9]+")) { Process p = new ProcessBuilder("taskkill.exe", "/PID", pid, "/T", "/F").start(); p.waitFor(); }
            }
        }
    }

    private void configure(Path root, String kind, String code, String report, String encoding) throws Exception {
        Path script = root.resolve("validate.py"); SourceVersionTest.write(script, code);
        JsonObject config = new JsonObject(), entry = new JsonObject(); config.addProperty("version", 1);
        JsonArray command = new JsonArray(); command.add("${python}"); command.add("-X"); command.add("utf8"); command.add(script.toString());
        entry.add("command", command); entry.addProperty("encoding", encoding);
        if (report != null) { JsonArray reports = new JsonArray(); reports.add(report); entry.add("reports", reports); }
        config.add(kind, entry); SourceVersionTest.write(root.resolve(".minion/validation.json"), config.toString());
    }
    private Path project() throws Exception { return tmp.newFolder("项目 空格" + System.nanoTime()).toPath(); }
    private BuildTool tool(Path root) throws Exception { return new BuildTool(new Workspace(root.toString()), python()); }
    private PythonRuntime python() {
        String configured = System.getProperty("minion.validation.python", System.getenv("MINION_PYTHON"));
        PythonRuntime runtime = new PythonRuntime(configured);
        Assume.assumeNotNull(runtime.resolve());
        return runtime;
    }
    private static JsonObject parse(ToolResult r) { return JsonParser.parseString(r.output).getAsJsonObject(); }
    private static String status(JsonObject r) { return r.get("status").getAsString(); }
    private static Path path(JsonObject r, String key) { return Paths.get(r.get(key).getAsString()); }
    private static void assertEvidence(Path root, JsonObject report) throws Exception {
        assertEquals(ValidationRunner.TYPE, report.get("type").getAsString());
        assertEquals(root.toRealPath().toString(), report.getAsJsonObject("scope").get("root").getAsString());
        assertTrue(Files.isRegularFile(path(report, "logPath"))); assertTrue(Files.isRegularFile(path(report, "reportPath")));
        assertTrue(report.toString(), report.get("sourceUnchanged").getAsBoolean());
        assertTrue(ValidationRunner.verifyReport(root, path(report, "reportPath")));
        assertEquals(report, JsonParser.parseString(new String(Files.readAllBytes(path(report, "reportPath")), StandardCharsets.UTF_8)).getAsJsonObject());
    }
}
