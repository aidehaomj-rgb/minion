package com.minion.core.tools.python;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class PythonToolTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();

    @Test public void configuredExecutableIsResolved() throws Exception {
        Path fake=tmp.newFile("python.exe").toPath();
        assertEquals(fake.toAbsolutePath().normalize(),new PythonRuntime(fake.toString()).resolve());
    }

    @Test public void runCodeAndStatus_withTestPython() throws Exception {
        String exe=System.getProperty("minion.test.python",""); Assume.assumeTrue(Files.isRegularFile(java.nio.file.Paths.get(exe)));
        PythonTool tool=new PythonTool(new Workspace(tmp.getRoot().getPath()),new PythonRuntime(exe));
        JsonObject status=new JsonObject();status.addProperty("action","status");
        assertTrue(tool.execute(status).ok);
        JsonObject run=new JsonObject();run.addProperty("action","run_code");run.addProperty("code","print('中文-ok')");
        ToolResult result=tool.execute(run);assertTrue(result.output,result.ok);assertTrue(result.output.contains("中文-ok"));
    }
}
