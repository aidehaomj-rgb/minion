package com.minion.core.agent;
import com.google.gson.Gson;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ReplayScoreboardTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void offlinePython37AggregatorUsesOnlyExplicitManualLabels()throws Exception {
        String exe=System.getProperty("minion.test.python","");Assume.assumeTrue("需显式已有Python环境",Files.isRegularFile(Paths.get(exe)));
        Path script=Paths.get("tools/qa/summarize_agent_replays.py").toAbsolutePath();
        String code="import sys, importlib.util\nsys.dont_write_bytecode=True\n"
                +"spec=importlib.util.spec_from_file_location('replay_score',"+new Gson().toJson(script.toString())+")\nm=importlib.util.module_from_spec(spec)\nspec.loader.exec_module(m)\n"
                +"review={'expectedItems':10,'omittedItems':2,'duplicateItems':0,'incorrectItems':1,'accepted':False}\n"
                +"report={'taskId':'anonymous-1','status':'completed','manualBusinessReview':review}\n"
                +"r=m.summarize([report,report,{'taskId':'unreviewed','status':'completed'}])\n"
                +"assert r['reviewed']==1 and r['false_completions']==1 and r['duplicates_ignored']==1\n"
                +"assert r['unreviewed']==1 and r['false_completion_rate']==1.0\n"
                +"assert m.summarize([])['acceptance_rate']=='unknown_no_manual_review'\n"
                +"assert m.summarize([])['manual_flags']['prematureStop']['true_rate']=='unknown_not_labelled'\n"
                +"review['prematureStop']=True\nassert m.summarize([report])['manual_flags']['prematureStop']['true_rate']==1.0\n"
                +"bad={'taskId':'bad','manualBusinessReview':{'expectedItems':True,'omittedItems':0,'duplicateItems':0,'incorrectItems':0,'accepted':True}}\n"
                +"assert m.summarize([bad])['invalid']==1\nprint('REPLAY_METRICS_OK')\n";
        PythonRuntime.RunResult result=new PythonRuntime(exe).runCode(code,tmp.getRoot().toPath(),30);
        assertTrue(result.output,result.ok());assertTrue(result.output,result.output.contains("REPLAY_METRICS_OK"));
    }
}
