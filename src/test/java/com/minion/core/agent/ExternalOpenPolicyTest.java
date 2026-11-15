package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ExternalOpenPolicyTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();

    @Test public void detectsSystemAndBrowserLaunchesWithoutTreatingFetchAsOpen() {
        assertEquals("https://www.data10086.com/",
                ExternalOpenPolicy.keyFor("Bash",bash("cmd //c start \"\" \"https://www.data10086.com/\"")));
        assertEquals("https://www.data10086.com/",
                ExternalOpenPolicy.keyFor("Bash",bash("powershell Start-Process 'https://www.data10086.com/'")));
        assertEquals("https://www.data10086.com/",
                ExternalOpenPolicy.keyFor("Bash",bash("\"C:/Chrome/chrome.exe\" --new-window \"https://www.data10086.com/\"")));
        assertEquals("https://example.com/a",
                ExternalOpenPolicy.keyFor("Browser",action("open","https://example.com/a")));
        assertNull(ExternalOpenPolicy.keyFor("Bash",bash("curl https://www.data10086.com/")));
        assertNull(ExternalOpenPolicy.keyFor("Bash",bash("Get-Process chrome")));
    }

    @Test public void urlIdentityNormalizesHostCaseDefaultPathAndTrackingPunctuation() {
        assertEquals("https://example.com/",
                ExternalOpenPolicy.keyFor("OpenUrl",action("","https://EXAMPLE.com")));
        assertEquals("https://example.com/a?q=1",
                ExternalOpenPolicy.keyFor("Bash",bash("chrome.exe \"https://example.com/a?q=1\".")));
    }

    @Test public void agentBlocksSecondLaunchOfSameUrlInOneTurn() throws Exception {
        AtomicInteger launches=new AtomicInteger();
        FakeLlmClient llm=new FakeLlmClient();
        ToolRegistry registry=new ToolRegistry();
        registry.register(new Tool(){
            @Override public String name(){return "Bash";}
            @Override public String description(){return "launch test";}
            @Override public JsonObject schema(){
                return SchemaGenerator.objectSchema(description(),new String[]{"command"},new String[]{"command"});
            }
            @Override public ToolResult execute(JsonObject args){
                launches.incrementAndGet();return ToolResult.success("started");
            }
        });
        Workspace workspace=new Workspace(tmp.getRoot().getPath());
        AgentLoop loop=new AgentLoop(llm,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),
                        new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new AgentUi(){},null,
                workspace,Session.create(workspace.workDir(),"test"));
        try{
            llm.addTurnWithTools(Collections.singletonList(launch("open-1")),null);
            llm.addTurnWithTools(Collections.singletonList(launch("open-2")),null);
            llm.addTurn("页面已打开");
            loop.runUserTurn("打开 https://www.data10086.com/");
            assertEquals(1,launches.get());
            assertTrue(loop.messages().stream().filter(m->m.role==com.minion.core.llm.Message.Role.TOOL)
                    .anyMatch(m->m.content!=null&&m.content.contains("阻止重复启动浏览器")));
        }finally{loop.shutdown();}
    }

    private static com.google.gson.JsonObject bash(String command){
        return JsonParser.parseString("{\"command\":\""+command.replace("\\","\\\\").replace("\"","\\\"")+"\"}")
                .getAsJsonObject();
    }
    private static com.google.gson.JsonObject action(String action,String url){
        com.google.gson.JsonObject out=new com.google.gson.JsonObject();
        if(!action.isEmpty())out.addProperty("action",action);
        out.addProperty("url",url);
        return out;
    }
    private static ToolCall launch(String id){
        ToolCall call=new ToolCall();call.id=id;call.name="Bash";
        call.arguments="{\"command\":\"cmd //c start \\\"\\\" \\\"https://www.data10086.com/\\\"\"}";
        return call;
    }
}
