package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ValidationBoundaryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** wrapper 为命令选择夹具，不调用真实 Maven，不写共享 target。 */
    @Test public void executesProjectMavenWrapperInChineseSpacePathAndReportsFailure() throws Exception {
        Path root = tmp.newFolder("maven 项目 空格").toPath();
        SourceVersionTest.write(root.resolve("pom.xml"), "<project/>");
        wrapper(root.resolve(ValidationDiscovery.windows() ? "mvnw.cmd" : "mvnw"), 23);
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertFalse(result.ok);
        JsonObject r = parse(result);
        assertEquals(r.toString(), "failed", status(r)); assertEquals(23, r.get("exitCode").getAsInt());
        assertTrue(r.get("logPreview").getAsString().contains("project-wrapper-invoked"));
        assertTrue(r.getAsJsonArray("command").get(0).getAsString().contains("mvnw"));
        assertTrue(r.get("tests").isJsonNull());
    }

    /** 不将 wrapper 退出 0 等同于执行了 Gradle 测试。 */
    @Test public void gradleWrapperExitZeroWithoutTestsRemainsUnknown() throws Exception {
        Path root = tmp.newFolder("gradle 项目 空格").toPath();
        SourceVersionTest.write(root.resolve("build.gradle"), "plugins {}\n");
        wrapper(root.resolve(ValidationDiscovery.windows() ? "gradlew.bat" : "gradlew"), 0);
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertFalse(result.ok);
        JsonObject r = parse(result);
        assertEquals(r.toString(), "command_succeeded", status(r));
        assertTrue(r.get("tests").isJsonNull()); assertFalse(r.get("functionalVerified").getAsBoolean());
        assertTrue(r.get("logPreview").getAsString().contains("project-wrapper-invoked"));
    }

    @Test public void actualNpmScriptAssertionFailsAndExitZeroStaysUnknown() throws Exception {
        Assume.assumeTrue("只使用已有 node/npm，缺失就跳过，不下载", available("node", "--version") && available(ValidationDiscovery.windows() ? "npm.cmd" : "npm", "--version"));
        Path root = tmp.newFolder("npm 项目 空格").toPath();
        packageJson(root, "node -e \"require('assert').strictEqual(1,2)\"");
        ToolResult failed = tool(root).execute(ValidationDiscoveryTest.args("test"));
        JsonObject r = parse(failed);
        assertFalse(failed.ok); assertEquals(r.toString(), "failed", status(r));
        assertTrue(r.get("logPreview").getAsString().contains("AssertionError"));
        packageJson(root, "node -e \"console.log('actual-npm-script')\"");
        ToolResult noCounts = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertFalse(noCounts.ok);
        assertEquals("command_succeeded", status(parse(noCounts)));
    }

    @Test public void detectNeverExecutesProjectValidationConfiguration() throws Exception {
        Path root = tmp.newFolder().toPath();
        SourceVersionTest.write(root.resolve(".minion/validation.json"), "{\"version\":1,\"test\":{\"command\":\"echo should-not-run > marker\"}}");
        assertTrue(tool(root).execute(ValidationDiscoveryTest.args("detect")).ok);
        assertFalse(Files.exists(root.resolve("marker")));
        assertFalse(Files.exists(root.resolve(".minion/validation-runs")));
    }

    @Test public void outputDirectoryPermissionOrTypeBlockIsNotSuccessful() throws Exception {
        Path root = tmp.newFolder().toPath();
        SourceVersionTest.write(root.resolve(".minion"), "this is a file, cannot create logs inside");
        JsonObject args = ValidationDiscoveryTest.args("command"); args.addProperty("command", "echo never-run");
        ToolResult result = tool(root).execute(args);
        assertFalse(result.ok); JsonObject r = parse(result);
        assertEquals("blocked", status(r)); assertFalse(r.get("processStarted").getAsBoolean());
        assertTrue(r.get("reportPath").isJsonNull());
    }

    @Test public void customShellCommandExitZeroIsNotFunctionalVerification() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonObject args = ValidationDiscoveryTest.args("command"); args.addProperty("command", "echo command-marker");
        ToolResult result = tool(root).execute(args);
        assertTrue(result.output, result.ok); JsonObject r = parse(result);
        assertEquals("command", r.get("kind").getAsString()); assertEquals("command_succeeded", status(r));
        assertFalse(r.get("functionalVerified").getAsBoolean()); assertTrue(r.get("tests").isJsonNull());
    }

    @Test public void commandCannotLeaveProjectAndVerifyCannotAdoptForeignReport() throws Exception {
        Path root = tmp.newFolder("project").toPath(), outside = tmp.newFolder("outside").toPath();
        JsonObject args = ValidationDiscoveryTest.args("command"); args.addProperty("path", outside.toString()); args.addProperty("command", "echo no");
        assertFalse(tool(root).execute(args).ok);
        assertFalse(ValidationRunner.verifyReport(root, outside.resolve("report.json")));
    }

    @Test public void malformedFreshXmlBlocksTestPassEvenIfCommandExitsZero() throws Exception {
        Path root = tmp.newFolder().toPath();
        // 用 Gson 构造 shell 路径，兼容 Windows 反斜线和中文空格。
        JsonObject config = new JsonObject(), entry = new JsonObject(); config.addProperty("version", 1);
        entry.addProperty("command", "echo not-xml > \"${runDir}/result.xml\"");
        com.google.gson.JsonArray reports = new com.google.gson.JsonArray(); reports.add("${runDir}/result.xml"); entry.add("reports", reports); config.add("test", entry);
        SourceVersionTest.write(root.resolve(".minion/validation.json"), config.toString());
        ToolResult result = tool(root).execute(ValidationDiscoveryTest.args("test"));
        assertFalse(result.ok); JsonObject r = parse(result);
        assertEquals(r.toString(), "report_error", status(r)); assertEquals(0, r.get("exitCode").getAsInt());
    }

    private static void packageJson(Path root, String script) throws Exception {
        JsonObject pkg = new JsonObject(), scripts = new JsonObject(); scripts.addProperty("test", script); pkg.add("scripts", scripts);
        SourceVersionTest.write(root.resolve("package.json"), pkg.toString());
    }
    private static void wrapper(Path file, int exit) throws Exception {
        SourceVersionTest.write(file, ValidationDiscovery.windows() ? "@echo off\r\necho project-wrapper-invoked\r\nexit /b " + exit + "\r\n"
                : "#!/bin/sh\necho project-wrapper-invoked\nexit " + exit + "\n");
        if (!ValidationDiscovery.windows()) assertTrue(file.toFile().setExecutable(true));
    }
    private static boolean available(String executable, String arg) {
        Process p = null;
        try { p = new ProcessBuilder(executable, arg).redirectErrorStream(true).start(); return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0; }
        catch (Exception e) { return false; }
        finally { if (p != null && p.isAlive()) com.minion.core.tools.ProcessCleanup.terminate(p); }
    }
    private static BuildTool tool(Path root) { return new BuildTool(new Workspace(root.toString()), new PythonRuntime("")); }
    private static JsonObject parse(ToolResult result) { return JsonParser.parseString(result.output).getAsJsonObject(); }
    private static String status(JsonObject report) { return report.get("status").getAsString(); }
}
