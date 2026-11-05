package com.minion.core.agent;
import com.google.gson.*;
import com.minion.core.tools.*;
import com.minion.core.tools.dev.BuildTool;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class WorkflowValidationGateTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private Path root;private BuildTool build;
    @Before public void setup()throws Exception {
        String exe=System.getProperty("minion.test.python","");Assume.assumeTrue("需显式已有Python环境",Files.isRegularFile(Paths.get(exe)));
        root=tmp.newFolder("actual-tests").toPath();Files.write(root.resolve("main.py"),"value=2\n".getBytes("UTF-8"));
        Files.write(root.resolve("test_logic.py"),("import unittest\nfrom main import value\nclass RealTest(unittest.TestCase):\n    def test_value(self): self.assertEqual(value,2)\n").getBytes("UTF-8"));
        build=new BuildTool(new Workspace(root.toString()),new PythonRuntime(exe));
    }
    private TaskState state(){TaskState s=new TaskState();s.begin("运行实际测试",true);s.workflow=TaskWorkflow.configure(s,JsonParser.parseString("[{\"id\":\"verify\",\"title\":\"运行测试\",\"scope\":\".\",\"acceptance\":\"实际断言通过\",\"validationKind\":\"test\"}]").getAsJsonArray(),1);s.workflow.transition(s,"verify","start",Collections.emptyList(),Collections.emptyList(),"","");return s;}
    private ToolResult run(String action)throws Exception{JsonObject a=new JsonObject();a.addProperty("action",action);a.addProperty("timeoutSeconds",60);return build.execute(a);}
    private String record(TaskState s,String tool,ToolResult r){s.recordToolEvidence(tool,r.ok,r.output,null,false,p->p.toAbsolutePath().normalize().startsWith(root));return s.evidence.get(s.evidence.size()-1).id;}
    private void complete(TaskState s,String ref){s.workflow.transition(s,"verify","complete",Collections.singletonList(ref),Collections.emptyList(),"根据实际测试日志核验","",p->p.toAbsolutePath().normalize().startsWith(root));}
    @Test public void actualPassedTestCanCompleteButCodeChangeInvalidatesIt()throws Exception{
        TaskState s=state();ToolResult actual=run("test");assertTrue(actual.output,actual.ok);String ref=record(s,"Build",actual);complete(s,ref);
        assertEquals("",s.workflow.blocking(s,p->p.startsWith(root)));
        Files.write(root.resolve("main.py"),"value=3\n".getBytes("UTF-8"));assertTrue(s.workflow.blocking(s,p->p.startsWith(root)).contains("验证已过期"));
    }
    @Test public void syntaxCheckAndCopiedModelJsonCannotMasqueradeAsTests()throws Exception{
        TaskState s=state();ToolResult syntax=run("syntax_check");assertTrue(syntax.output,syntax.ok);String ref=record(s,"Build",syntax);
        try{complete(s,ref);fail("语法检查不能充当实际测试");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("真实test"));}
        ToolResult test=run("test");assertTrue(test.output,test.ok);String forged=record(s,"Read",test);
        try{complete(s,forged);fail("复制JSON不是Build证据");}catch(IllegalArgumentException expected){assertNull(s.evidence.get(s.evidence.size()-1).validation);}
    }
    @Test public void reportTamperingCannotKeepAValidatedStepCompleted()throws Exception{
        TaskState s=state();ToolResult test=run("test");assertTrue(test.output,test.ok);String ref=record(s,"Build",test);complete(s,ref);
        TaskState.Evidence e=s.evidence.get(s.evidence.size()-1);Files.write(Paths.get(e.validationFile.path),"{}".getBytes("UTF-8"));
        assertFalse(TaskValidation.current(e,p->p.startsWith(root)));assertFalse(s.workflow.blocking(s,p->p.startsWith(root)).isEmpty());
    }
}
