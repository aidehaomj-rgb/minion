package com.minion.core.tools.knowledge;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class KnowledgeToolTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();

    @Test public void addSearchListReadAndDelete() throws Exception {
        KnowledgeTool tool=new KnowledgeTool(new Workspace(tmp.getRoot().getPath()));
        JsonObject add=args("add");add.addProperty("title","销售规则");add.addProperty("source","制度.docx");add.addProperty("content","华东区域的折扣上限是百分之十。");
        ToolResult added=tool.execute(add);assertTrue(added.output,added.ok);
        ToolResult list=tool.execute(args("list"));assertTrue(list.output.contains("销售规则"));
        JsonObject search=args("search");search.addProperty("query","华东折扣");ToolResult hit=tool.execute(search);assertTrue(hit.output,hit.output.contains("百分之十"));
        String name=list.output.substring(list.output.indexOf("- ")+2,list.output.indexOf(" ("));
        JsonObject read=args("read");read.addProperty("name",name);assertTrue(tool.execute(read).output.contains("制度.docx"));
        JsonObject del=args("delete");del.addProperty("name",name);assertTrue(tool.execute(del).ok);
    }

    @Test public void refusesTraversalName() throws Exception {
        KnowledgeTool tool=new KnowledgeTool(new Workspace(tmp.getRoot().getPath()));JsonObject read=args("read");read.addProperty("name","../secret.md");
        assertFalse(tool.execute(read).ok);
    }
    private static JsonObject args(String action){JsonObject o=new JsonObject();o.addProperty("action",action);return o;}
}
