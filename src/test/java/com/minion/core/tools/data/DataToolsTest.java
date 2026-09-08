package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class DataToolsTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();

    @Test public void sqliteExecuteSchemaAndQuery() throws Exception {
        PythonRuntime py=runtime(); Workspace ws=new Workspace(tmp.getRoot().getPath()); SqliteTool tool=new SqliteTool(ws,py);
        ToolResult made=tool.execute(args("execute","database","test.db","sql","create table t(name text,n integer); insert into t values('甲',2);"));
        assertTrue(made.output,made.ok);
        ToolResult schema=tool.execute(args("schema","database","test.db"));assertTrue(schema.output,schema.output.toLowerCase().contains("create table t"));
        ToolResult rows=tool.execute(args("query","database","test.db","sql","select * from t"));assertTrue(rows.output,rows.output.contains("甲"));
    }

    @Test public void excelInspectsAndReadsXlsx() throws Exception {
        PythonRuntime py=runtime(); Workspace ws=new Workspace(tmp.getRoot().getPath());
        PythonRuntime.RunResult create=py.runCode("import pandas as pd\npd.DataFrame({'产品':['A','B'],'数量':[2,3]}).to_excel('data.xlsx',index=False)",ws.cwd(),30);
        Assume.assumeTrue("test Python lacks pandas/openpyxl: "+create.output,create.ok());
        ExcelTool tool=new ExcelTool(ws,py);
        ToolResult inspect=tool.execute(args("inspect","path","data.xlsx"));assertTrue(inspect.output,inspect.ok);assertTrue(inspect.output.contains("产品"));
        ToolResult read=tool.execute(args("read","path","data.xlsx","rows","2"));assertTrue(read.output,read.output.contains("A,2"));
    }

    private PythonRuntime runtime(){String exe=System.getProperty("minion.test.python","");Assume.assumeTrue(Files.isRegularFile(java.nio.file.Paths.get(exe)));return new PythonRuntime(exe);}
    private static JsonObject args(String action,String... pairs){JsonObject o=new JsonObject();o.addProperty("action",action);for(int i=0;i+1<pairs.length;i+=2)o.addProperty(pairs[i],pairs[i+1]);return o;}
}
