package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class TextProcessToolTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void normalizeAndDedupeWriteNewFiles()throws Exception{
        Path root=temp.newFolder("text").toPath();Files.write(root.resolve("a.txt"),"甲  \r\n\r\n\r\n乙\r\n甲  \r\n".getBytes(StandardCharsets.UTF_8));
        TextProcessTool tool=new TextProcessTool(new Workspace(root.toString()),null);
        JsonObject n=args("normalize","a.txt","n.txt");assertTrue(tool.execute(n).ok);assertEquals("甲\n\n乙\n甲\n",read(root.resolve("n.txt")));
        JsonObject d=args("dedupe","n.txt","d.txt");assertTrue(tool.execute(d).ok);assertEquals("甲\n乙\n",read(root.resolve("d.txt")));
    }
    @Test public void regexExtractAndCompareWork()throws Exception{
        Path root=temp.newFolder("regex").toPath();Files.write(root.resolve("a.txt"),"编号 A-12\n编号 B-34\n".getBytes(StandardCharsets.UTF_8));Files.write(root.resolve("b.txt"),"编号 B-34\n编号 C-56\n".getBytes(StandardCharsets.UTF_8));
        TextProcessTool tool=new TextProcessTool(new Workspace(root.toString()),null);JsonObject e=args("regex_extract","a.txt","ids.txt");e.addProperty("pattern","编号 ([A-Z]-[0-9]+)");assertTrue(tool.execute(e).ok);assertEquals("A-12\nB-34\n",read(root.resolve("ids.txt")));
        JsonObject c=new JsonObject();c.addProperty("action","compare");c.addProperty("path","a.txt");c.addProperty("otherPath","b.txt");ToolResult r=tool.execute(c);assertTrue(r.ok);assertTrue(r.output.contains("左侧独有行=1"));
    }
    @Test public void refusesOverwriteInput()throws Exception{Path root=temp.newFolder("guard").toPath();Files.write(root.resolve("a.txt"),"x".getBytes(StandardCharsets.UTF_8));TextProcessTool tool=new TextProcessTool(new Workspace(root.toString()),null);assertFalse(tool.execute(args("normalize","a.txt","a.txt")).ok);}
    private static JsonObject args(String action,String path,String out){JsonObject o=new JsonObject();o.addProperty("action",action);o.addProperty("path",path);o.addProperty("outputPath",out);return o;}
    private static String read(Path p)throws Exception{return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
}
