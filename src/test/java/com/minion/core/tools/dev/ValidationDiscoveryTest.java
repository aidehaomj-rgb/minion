package com.minion.core.tools.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ValidationDiscoveryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void detectsTestOnlyPythonProjectsWithoutLaunchingAnything() throws Exception {
        Path root = tmp.newFolder().toPath();
        SourceVersionTest.write(root.resolve("test_logic.py"), "assert False\n");
        assertEquals("python", ValidationDiscovery.detect(root));
        BuildTool tool = new BuildTool(new Workspace(root.toString()), new PythonRuntime("Z:/missing-python"));
        assertTrue(tool.execute(args("detect")).ok);
        assertFalse(Files.exists(root.resolve(".minion")));
    }

    @Test public void mavenWrapperIsPreferredWithoutExecutingIt() throws Exception {
        Path root = tmp.newFolder("maven 中文 空格").toPath();
        SourceVersionTest.write(root.resolve("pom.xml"), "<project/>");
        Path wrapper = root.resolve(ValidationDiscovery.windows() ? "mvnw.cmd" : "mvnw");
        SourceVersionTest.write(wrapper, "not executed");
        ValidationDiscovery.Plan plan = plan(root, "test");
        assertEquals(wrapper.toString(), plan.command.get(0));
        assertTrue(plan.command.contains("-o"));
        assertTrue(plan.command.get(3).startsWith("-Dsurefire.reportsDirectory="));
        assertEquals("maven", plan.runner);
    }

    @Test public void mavenFallsBackToPlatformExecutable() throws Exception {
        Path root = tmp.newFolder().toPath();
        SourceVersionTest.write(root.resolve("pom.xml"), "<project/>");
        ValidationDiscovery.Plan plan = plan(root, "build");
        assertEquals(ValidationDiscovery.windows() ? "mvn.cmd" : "mvn", plan.command.get(0));
        assertTrue(plan.command.contains("-DskipTests"));
    }

    @Test public void gradleRequiresProjectWrapperAndForcesFreshOfflineTests() throws Exception {
        Path root = tmp.newFolder().toPath();
        SourceVersionTest.write(root.resolve("build.gradle.kts"), "plugins {}\n");
        ValidationDiscovery.Plan absent = plan(root, "test");
        assertTrue(absent.problem.contains("wrapper")); assertEquals("blocked", absent.problemStatus);
        SourceVersionTest.write(root.resolve(ValidationDiscovery.windows() ? "gradlew.bat" : "gradlew"), "not executed");
        ValidationDiscovery.Plan present = plan(root, "test");
        assertTrue(present.command.contains("--offline"));
        assertTrue(present.command.contains("--rerun-tasks"));
        assertTrue(present.command.contains("--no-build-cache"));
    }

    @Test public void npmMustUseExistingScriptsRatherThanAssumeTest() throws Exception {
        Path root = tmp.newFolder().toPath();
        SourceVersionTest.write(root.resolve("package.json"), "{\"scripts\":{\"build\":\"node build.js\"}}");
        assertTrue(plan(root, "test").problem.contains("scripts.test"));
        ValidationDiscovery.Plan build = plan(root, "build");
        assertTrue(build.command.contains("run")); assertTrue(build.command.contains("build"));
        SourceVersionTest.write(root.resolve("package.json"), "{\"scripts\":{\"test\":\"  \"}}");
        assertFalse(plan(root, "test").problem.isEmpty());
    }

    @Test public void validationCommandsRemainBehindExistingHighRiskGate() throws Exception {
        BuildTool tool = new BuildTool(new Workspace(tmp.getRoot().toString()), new PythonRuntime(""));
        for (String action : new String[]{"test", "build", "syntax_check", "command", "serve"}) assertTrue(action, tool.isHighRisk(args(action)));
        for (String action : new String[]{"detect", "verify", "artifacts"}) assertFalse(action, tool.isHighRisk(args(action)));
    }

    @Test public void explicitConfigOverridesDetectionAndSupportsRunDirectory() throws Exception {
        Path root = tmp.newFolder().toPath(), run = root.resolve(".minion/validation-runs/example");
        JsonObject config = new JsonObject(), test = new JsonObject();
        config.addProperty("version", 1);
        JsonArray command = new JsonArray(); command.add("existing-tool"); command.add("--out=${runDir}/test.xml"); test.add("command", command);
        JsonArray reports = new JsonArray(); reports.add("${runDir}/test.xml"); test.add("reports", reports);
        test.addProperty("encoding", "GBK"); config.add("test", test);
        SourceVersionTest.write(root.resolve(".minion/validation.json"), config.toString());
        ValidationDiscovery.Plan p = ValidationDiscovery.plan(root, run, "test", "", new PythonRuntime(""));
        assertEquals("project_config", p.runner); assertEquals("GBK", p.encoding.name());
        assertEquals(run.resolve("test.xml"), p.reports.get(0));
        assertTrue(p.command.get(1).contains(run.toString()));
    }

    @Test(expected = java.io.IOException.class) public void explicitReportCannotEscapeProject() throws Exception {
        Path root = tmp.newFolder().toPath();
        SourceVersionTest.write(root.resolve(".minion/validation.json"), "{\"version\":1,\"test\":{\"command\":[\"echo\",\"x\"],\"reports\":[\"../outside.xml\"]}}");
        plan(root, "test");
    }

    @Test public void pythonBuildDoesNotFallBackToSyntaxOrFakeTest() throws Exception {
        Path root = tmp.newFolder().toPath();
        SourceVersionTest.write(root.resolve("main.py"), "print('hi')\n");
        assertFalse(plan(root, "test").problem.isEmpty());
        assertFalse(plan(root, "build").problem.isEmpty());
    }

    private ValidationDiscovery.Plan plan(Path root, String action) throws Exception {
        return ValidationDiscovery.plan(root, root.resolve(".minion/validation-runs/example"), action, "", new PythonRuntime(""));
    }
    static JsonObject args(String action) { JsonObject j = new JsonObject(); j.addProperty("action", action); return j; }
}
