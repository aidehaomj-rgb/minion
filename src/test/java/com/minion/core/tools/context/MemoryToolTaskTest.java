package com.minion.core.tools.context;

import com.google.gson.JsonObject;
import com.minion.core.agent.Session;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class MemoryToolTaskTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();

    @Test public void readsOnlyCurrentOriginalRequestByPage() throws Exception {
        Workspace workspace=new Workspace(tmp.getRoot().getPath());
        Session session=Session.create(workspace.workDir(),"fake");
        StringBuilder original=new StringBuilder();
        for(int i=0;i<9000;i++)original.append('甲');
        original.append("中段原始约束：保留Excel编号");
        for(int i=0;i<9000;i++)original.append('乙');
        session.taskState.begin(original.toString(),false);
        MemoryTool tool=new MemoryTool(workspace,null,null,session.id,session);
        JsonObject args=new JsonObject();args.addProperty("action","read_task");
        args.addProperty("offset",8990);args.addProperty("limit",80);
        ToolResult result=tool.execute(args);
        assertTrue(result.ok);assertTrue(result.output.contains("中段原始约束：保留Excel编号"));
        assertFalse(tool.isHighRisk(args));
        session.taskState.addGuidance("新增要求：数值列保留两位小数");
        args.addProperty("mode","guidance");args.addProperty("id","1");args.addProperty("offset",0);
        assertTrue(tool.execute(args).output.contains("数值列保留两位小数"));
        args.remove("mode");
        session.taskState.begin("仅核对新的文件",true);
        assertFalse(tool.execute(args).output.contains("保留Excel编号"));
    }
}
