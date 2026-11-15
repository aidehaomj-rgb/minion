package com.minion.core.tools.dev;

import com.google.gson.*;
import com.minion.core.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CodeIndexToolTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private Path write(String path,String source)throws Exception{
        Path p=tmp.getRoot().toPath().resolve(path);Files.createDirectories(p.getParent());
        Files.write(p,source.getBytes("UTF-8"));return p;
    }
    private CodeIndexTool tool(){return new CodeIndexTool(new Workspace(tmp.getRoot().getAbsolutePath()));}
    private JsonObject args(String action,String symbol){
        JsonObject o=new JsonObject();o.addProperty("action",action);if(symbol!=null)o.addProperty("symbol",symbol);return o;
    }
    private JsonObject run(CodeIndexTool tool,JsonObject args)throws Exception{
        ToolResult result=tool.execute(args);assertTrue(result.output,result.ok);return new JsonParser().parse(result.output).getAsJsonObject();
    }
    private JsonArray hits(JsonObject o){return o.getAsJsonArray("results");}
    private boolean contains(JsonArray hits,String name,String kind){
        for(JsonElement e:hits){JsonObject h=e.getAsJsonObject();if(name.equals(h.get("symbol").getAsString())&&kind.equals(h.get("kind").getAsString()))return true;}return false;
    }
    @Test public void multilingualDefinitionsAndChinesePaths()throws Exception{
        write("中文目录/类.java","class 服务 {\n public void 执行() {}\n}\n");
        write("中文目录/a.py","class Worker:\n    def work(self):\n        return work()\n");
        write("中文目录/a.js","export function launch() {}\nconst task = () => launch();\n");
        write("中文目录/a.ts","interface Shape {}\ntype Alias = Shape;\nlet factory = () => launch();\n");
        JsonArray h=hits(run(tool(),args("symbols",null)));
        for(String s:new String[]{"服务","执行","Worker","work","launch","task","Shape","Alias","factory"})assertTrue(s,contains(h,s,"definition"));
        for(JsonElement e:h){JsonObject x=e.getAsJsonObject();assertTrue(x.get("line").getAsInt()>0);assertEquals(64,x.get("sha256").getAsString().length());assertTrue(x.has("snippet"));}
    }
    @Test public void literalReferencesExcludeCommentsStringsAndDefinitions()throws Exception{
        write("a.js","function foo() {}\nfoo(); foobar();\n// foo()\nconst s = 'foo()'; /* foo */\n");
        JsonObject r=run(tool(),args("references","foo"));assertEquals(1,hits(r).size());
        assertEquals(2,hits(r).get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals(2,hits(run(tool(),args("impact","foo"))).size());
        for(String bad:new String[]{".*","foo|bar","foo()","x/y",""})assertFalse(tool().execute(args("references",bad)).ok);
    }
    @Test public void noStaleCacheEvenSameSizeAndMtimeThenDelete()throws Exception{
        Path p=write("a.py","def old(): pass\nold()\n");FileTime stamp=Files.getLastModifiedTime(p);CodeIndexTool t=tool();
        JsonObject before=run(t,args("symbols","old"));assertEquals(1,hits(before).size());
        Files.write(p,"def new(): pass\nnew()\n".getBytes("UTF-8"));Files.setLastModifiedTime(p,stamp);
        assertEquals(0,hits(run(t,args("impact","old"))).size());
        JsonObject after=run(t,args("symbols","new"));assertEquals(1,hits(after).size());
        assertNotEquals(hits(before).get(0).getAsJsonObject().get("sha256"),hits(after).get(0).getAsJsonObject().get("sha256"));
        Files.delete(p);assertEquals(0,hits(run(t,args("symbols",null))).size());
    }
    @Test public void rejectsOutsideAndRechecksWorkspace()throws Exception{
        Path external=Files.createTempDirectory("code-index-outside");
        try{
            JsonObject a=args("symbols",null);a.addProperty("path",external.toString());assertFalse(tool().execute(a).ok);
            a.addProperty("path","../");assertFalse(tool().execute(a).ok);
            Workspace w=new Workspace(tmp.getRoot().getAbsolutePath());CodeIndexTool t=new CodeIndexTool(w);
            write("local.py","def local(): pass");assertEquals(1,hits(run(t,args("symbols",null))).size());
            w.setWorkDir(external.toString());assertEquals(0,hits(run(t,args("symbols",null))).size());
        }finally{Files.delete(external);}
    }
    @Test public void skippedDirectoriesCannotBeExplicitlySelected()throws Exception{
        for(String s:new String[]{".git",".minion","target","node_modules","venv",".venv"})write(s+"/a.py","def hidden(): pass");
        assertEquals(0,hits(run(tool(),args("symbols",null))).size());
        JsonObject a=args("symbols",null);a.addProperty("path","target/a.py");assertEquals(0,hits(run(tool(),a)).size());
    }
    @Test public void resultAndFileAndByteBudgetsReportTruncation()throws Exception{
        write("a.py","def foo(): pass\nfoo()\nfoo()\n");write("b.py","foo()\n");
        JsonObject a=args("impact","foo");a.addProperty("max_results",1);
        JsonObject r=run(tool(),a);assertTrue(r.get("truncated").getAsBoolean());assertEquals(1,hits(r).size());assertFalse(r.get("complete").getAsBoolean());
        a=args("symbols",null);a.addProperty("max_files",1);assertTrue(run(tool(),a).get("truncated").getAsBoolean());
        a=args("symbols",null);a.addProperty("max_bytes",2);r=run(tool(),a);assertTrue(r.get("truncated").getAsBoolean());assertTrue(r.get("bytes_read").getAsInt()<=2);
        a=args("symbols",null);a.addProperty("max_file_bytes",2);assertTrue(run(tool(),a).get("truncated").getAsBoolean());
        a=args("symbols",null);a.addProperty("max_entries",1);assertTrue(run(tool(),a).get("truncated").getAsBoolean());
    }
    @Test public void cancellationBeforeAndDuringScanAndInterruptPreserved()throws Exception{
        write("a.py","def foo(): pass\nfoo()\n");
        CodeIndexTool t=new CodeIndexTool(new Workspace(tmp.getRoot().getAbsolutePath()),()->true);
        JsonObject r=run(t,args("symbols",null));assertTrue(r.get("cancelled").getAsBoolean());assertEquals(0,hits(r).size());
        AtomicInteger polls=new AtomicInteger();t=new CodeIndexTool(new Workspace(tmp.getRoot().getAbsolutePath()),()->polls.incrementAndGet()>4);
        assertTrue(run(t,args("impact","foo")).get("cancelled").getAsBoolean());
        Thread.currentThread().interrupt();
        try{assertTrue(run(tool(),args("symbols",null)).get("cancelled").getAsBoolean());assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();}
    }
    @Test public void symlinkEscapeWhenSupported()throws Exception{
        Path external=Files.createTempDirectory("code-index-link");Path link=tmp.getRoot().toPath().resolve("linked");
        try{
            Files.write(external.resolve("secret.py"),"def secret(): pass".getBytes("UTF-8"));
            try{Files.createSymbolicLink(link,external);}catch(Exception e){Assume.assumeNoException(e);}
            assertEquals(0,hits(run(tool(),args("symbols",null))).size());
            JsonObject a=args("symbols",null);a.addProperty("path",link.resolve("secret.py").toString());assertFalse(tool().execute(a).ok);
        }finally{Files.deleteIfExists(link);Files.deleteIfExists(external.resolve("secret.py"));Files.deleteIfExists(external);}
    }
    @Test public void cwdRelativeAndSingleFileAndLargeSnippet()throws Exception{
        write("子目录/a.py","def foo(): pass\n"+new String(new char[2000]).replace('\0',' ') + "foo()\n");
        Workspace w=new Workspace(tmp.getRoot().getAbsolutePath());w.cd("子目录");CodeIndexTool t=new CodeIndexTool(w);
        JsonObject a=args("references","foo");a.addProperty("path","a.py");JsonObject r=run(t,a);
        assertEquals(1,hits(r).size());assertTrue(hits(r).get(0).getAsJsonObject().get("snippet").getAsString().length()<300);
        assertTrue(hits(r).get(0).getAsJsonObject().get("file").getAsString().contains("子目录/"));
    }
}
