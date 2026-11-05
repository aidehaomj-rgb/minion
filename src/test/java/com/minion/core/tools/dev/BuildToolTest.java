package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;

import static org.junit.Assert.*;

public class BuildToolTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void androidProjectIsRecognizedAndMissingWrapperIsExplained() throws Exception {
        java.nio.file.Path root = tmp.newFolder("app-project").toPath();
        java.nio.file.Path manifest = root.resolve("app/src/main/AndroidManifest.xml");
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, "<manifest package=\"local.test\"/>".getBytes("UTF-8"));
        BuildTool tool = new BuildTool(new Workspace(tmp.getRoot().getPath()), new PythonRuntime(""));
        ToolResult detected = tool.execute(args("detect", "app-project"));
        assertTrue(detected.output, detected.ok);
        assertTrue(detected.output, detected.output.contains("android"));
        ToolResult built = tool.execute(args("build", "app-project"));
        assertFalse(built.ok);
        assertTrue(built.output, built.output.contains("wrapper"));
    }

    @Test public void plainGradleProjectIsRecognized() throws Exception {
        java.nio.file.Path root = tmp.newFolder("lib").toPath();
        Files.write(root.resolve("build.gradle"), "plugins { id 'java' }".getBytes("UTF-8"));
        BuildTool tool = new BuildTool(new Workspace(tmp.getRoot().getPath()), new PythonRuntime(""));
        ToolResult detected = tool.execute(args("detect", "lib"));
        assertTrue(detected.output, detected.output.contains("项目类型: gradle"));
    }

    private static JsonObject args(String action, String path) {
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        o.addProperty("path", path);
        return o;
    }
}
