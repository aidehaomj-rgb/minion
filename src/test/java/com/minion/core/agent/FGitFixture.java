package com.minion.core.agent;

import com.minion.core.tools.dev.GitCommand;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

/** 只在独立临时目录构造Git对象夹具，不运行commit/reset/clean/checkout命令。 */
public final class FGitFixture {
    public static Path create()throws Exception{
        Path root=Files.createTempDirectory("minion-f-isolation-");git(root,"init");git(root,"config","core.autocrlf","false");
        byte[] blob=object(root,"blob","base\n".getBytes(StandardCharsets.UTF_8));ByteArrayOutputStream tree=new ByteArrayOutputStream();tree.write("100644 a.txt\0".getBytes(StandardCharsets.UTF_8));tree.write(blob);
        String treeId=hex(object(root,"tree",tree.toByteArray()));String commit="tree "+treeId+"\nauthor F <fixture@invalid> 1600000000 +0000\ncommitter F <fixture@invalid> 1600000000 +0000\n\nfixture\n";
        String head=hex(object(root,"commit",commit.getBytes(StandardCharsets.UTF_8)));Files.createDirectories(root.resolve(".git/refs/heads"));write(root.resolve(".git/refs/heads/f-fixture"),head+"\n");write(root.resolve(".git/HEAD"),"ref: refs/heads/f-fixture\n");write(root.resolve("a.txt"),"base\n");git(root,"read-tree","HEAD");return root;
    }
    public static void write(Path p,String s)throws Exception{Files.createDirectories(p.getParent());Files.write(p,s.getBytes(StandardCharsets.UTF_8));}
    public static void advanceHead(Path root)throws Exception{String old=git(root,"rev-parse","HEAD").trim(),tree=git(root,"rev-parse","HEAD^{tree}").trim();String content="tree "+tree+"\nparent "+old+"\nauthor F <fixture@invalid> 1600000001 +0000\ncommitter F <fixture@invalid> 1600000001 +0000\n\nchanged fixture\n";String id=hex(object(root,"commit",content.getBytes(StandardCharsets.UTF_8)));write(root.resolve(".git/refs/heads/f-fixture"),id+"\n");}
    public static String git(Path root,String...args)throws Exception{GitCommand.Result r=GitCommand.run(root,Arrays.asList(args),10000,1000000);if(r.exitCode!=0||r.truncated)throw new AssertionError(r.text()+r.stderr);return r.text();}
    private static byte[] object(Path root,String type,byte[] content)throws Exception{ByteArrayOutputStream data=new ByteArrayOutputStream();data.write((type+" "+content.length+"\0").getBytes(StandardCharsets.US_ASCII));data.write(content);byte[] bytes=data.toByteArray(),id=MessageDigest.getInstance("SHA-1").digest(bytes);String name=hex(id);Path target=root.resolve(".git/objects/"+name.substring(0,2)+"/"+name.substring(2));Files.createDirectories(target.getParent());try(DeflaterOutputStream out=new DeflaterOutputStream(Files.newOutputStream(target))){out.write(bytes);}return id;}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte value:bytes)b.append(String.format("%02x",value&255));return b.toString();}
}
