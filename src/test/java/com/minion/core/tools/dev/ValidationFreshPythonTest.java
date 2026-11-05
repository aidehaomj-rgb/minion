package com.minion.core.tools.dev;
import com.google.gson.*;
import com.minion.core.tools.*;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import static org.junit.Assert.*;

public class ValidationFreshPythonTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void python37SameSizeSameTimestampEditCannotTestStaleBytecode()throws Exception {
        String exe=System.getProperty("minion.test.python","");Assume.assumeTrue("需显式已有Python环境",Files.isRegularFile(Paths.get(exe)));
        Path root=tmp.newFolder("fresh-source").toPath(),main=root.resolve("main.py");Files.write(main,"value=1\n".getBytes("UTF-8"));
        PythonRuntime python=new PythonRuntime(exe);PythonRuntime.RunResult warm=python.runCode("import os,sys\nsys.path.insert(0,os.getcwd())\nimport main\nassert main.value==1\n",root,30);assertTrue(warm.output,warm.ok());
        FileTime time=Files.getLastModifiedTime(main);Files.write(main,"value=2\n".getBytes("UTF-8"));Files.setLastModifiedTime(main,time);
        Files.write(root.resolve("test_logic.py"),("import unittest\nimport main\nclass Check(unittest.TestCase):\n def test_fresh(self): self.assertEqual(main.value,2)\n").getBytes("UTF-8"));
        JsonObject args=new JsonObject();args.addProperty("action","test");args.addProperty("timeoutSeconds",60);
        ToolResult result=new BuildTool(new Workspace(root.toString()),python).execute(args);assertTrue(result.output,result.ok);
        JsonObject report=JsonParser.parseString(result.output).getAsJsonObject();assertEquals("passed",report.get("status").getAsString());assertTrue(report.get("generatedBytecodeCleared").getAsInt()>=1);
    }
}
