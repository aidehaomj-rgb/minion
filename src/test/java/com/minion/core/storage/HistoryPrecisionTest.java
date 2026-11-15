package com.minion.core.storage;

import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.Assert.*;

public class HistoryPrecisionTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private void record(HistoryArchive a,String task,Message... messages)throws Exception {
        a.recordCompaction(Arrays.asList(messages),Collections.<Message>emptyList(),task);
    }
    @Test public void duplicateFragmentsCollapseButDifferentTaskEvidenceIsRetained()throws Exception {
        HistoryArchive a=new HistoryArchive(tmp.getRoot().toPath(),"session");
        Message one=Message.user("重复片段必须先核对原文");one.taskId="a";
        Message two=Message.user(one.content);two.taskId="a";Message three=Message.user(one.content);three.taskId="b";
        record(a,"fallback",one,two,three);
        assertEquals(2,a.search("重复片段",10).size());assertEquals(1,a.search("重复片段",10,"a").size());
    }
    @Test public void lazyCacheSeesNewSegmentsAndDeleteAndRebuildDoNotModifyOriginalBytes()throws Exception {
        Path dir=tmp.getRoot().toPath();HistoryArchive a=new HistoryArchive(dir,"session");
        assertFalse(Files.exists(dir.resolve(".history")));record(a,"a",Message.user("第一份独有凭据"));
        Map<Path,byte[]> bytes=new HashMap<Path,byte[]>();
        try(DirectoryStream<Path> files=Files.newDirectoryStream(dir.resolve(".history/session"))){for(Path p:files)bytes.put(p,Files.readAllBytes(p));}
        assertEquals(1,a.search("第一份独有凭据",5).size());
        HistoryArchive b=new HistoryArchive(dir,"session");record(b,"a",Message.user("第二份新增凭据"));
        assertEquals(1,a.search("第二份新增凭据",5).size());a.rebuildSearchIndex();assertEquals(1,b.search("第一份独有凭据",5).size());
        for(Map.Entry<Path,byte[]> e:bytes.entrySet())assertArrayEquals(e.getValue(),Files.readAllBytes(e.getKey()));
        a.delete();assertTrue(b.search("第一份独有凭据",5).isEmpty());
    }
    @Test public void strictEvidenceExcludesChangedSameSizeSameTimeFilesAndTimeScopeIsRespected()throws Exception {
        Path project=tmp.newFolder("project").toPath(),input=project.resolve("input.txt");Files.write(input,"AAAA".getBytes(StandardCharsets.UTF_8));
        HistoryArchive a=new HistoryArchive(tmp.newFolder("sessions").toPath(),"session",project);
        ToolCall call=new ToolCall();call.id="read";call.name="Read";call.arguments="{\"path\":\"input.txt\"}";
        Message request=Message.assistant(null);request.toolCalls=Collections.singletonList(call);
        Message result=Message.toolResult("read","Read","文件结论应当待复核");result.taskId="task";
        result.ts=Math.max(System.currentTimeMillis(),Files.getLastModifiedTime(input).toMillis());record(a,"task",request,result);
        assertEquals(1,a.search("文件结论",5,"task","input.txt",0,0,false).size());
        assertTrue(a.search("文件结论",5,"task","input.txt",result.ts+1,0,true).isEmpty());
        FileTime time=Files.getLastModifiedTime(input);Files.write(input,"BBBB".getBytes(StandardCharsets.UTF_8));Files.setLastModifiedTime(input,time);
        assertEquals("changed",a.search("文件结论",5).get(0).sourceState);
        assertTrue(a.search("文件结论",5,"task","input.txt",0,0,false).isEmpty());
    }
    @Test public void cachedIndexCannotBypassOriginalHashValidation()throws Exception {
        Path dir=tmp.getRoot().toPath();HistoryArchive a=new HistoryArchive(dir,"session");record(a,"a",Message.user("原始审计记录"));
        assertEquals(1,a.search("原始审计记录",5).size());
        try(DirectoryStream<Path> files=Files.newDirectoryStream(dir.resolve(".history/session"),"*.json")){
            for(Path p:files)if(!p.toString().endsWith(".idx.json")){
                String body=new String(Files.readAllBytes(p),StandardCharsets.UTF_8);
                Files.write(p,body.replace("原始审计记录","伪造审计记录").getBytes(StandardCharsets.UTF_8));
            }
        }
        assertTrue(a.search("原始审计记录",5).isEmpty());assertTrue(a.search("伪造审计记录",5).isEmpty());
    }
    @Test public void syntheticLongArchiveAndRepeatedCompactionsKeepExactMiddleReference()throws Exception {
        HistoryArchive a=new HistoryArchive(tmp.getRoot().toPath(),"session");StringBuilder body=new StringBuilder();
        for(int n=0;n<20000;n++)body.append("普通过程资料").append(n).append("行，不是永久事实。\n");
        int offset=body.length();body.append("独有回放标记第38分块证据\n");
        for(int n=0;n<20000;n++)body.append("其余工具过程记录，不得视为已核验结论。\n");
        Message original=Message.toolResult("child38","Task",body.toString());
        record(a,"task",original);String id=original.eventId;
        for(int n=0;n<3;n++)record(a,"task",original,Message.user("继续核对阶段"+n));
        List<HistoryArchive.Hit> hits=a.search("独有回放标记第38分块证据",5,"task");
        assertEquals(1,hits.size());assertEquals(id,hits.get(0).eventId);
        assertTrue(a.read(hits.get(0).messageId,offset,100).contains("独有回放标记第38分块证据"));
    }
    @Test public void metadataOnlyLargeFileStampIsNeverCalledCurrentEvidence()throws Exception {
        Path project=tmp.newFolder("large-project").toPath(),input=project.resolve("large.txt");
        try(java.io.RandomAccessFile f=new java.io.RandomAccessFile(input.toFile(),"rw")){f.setLength(51L*1024*1024);}
        HistoryArchive a=new HistoryArchive(tmp.newFolder("large-sessions").toPath(),"session",project);
        ToolCall call=new ToolCall();call.id="read-large";call.name="Read";call.arguments="{\"path\":\"large.txt\"}";
        Message request=Message.assistant(null);request.toolCalls=Collections.singletonList(call);
        Message result=Message.toolResult("read-large","Read","大文件结论尚缺完整指纹");
        result.ts=Math.max(System.currentTimeMillis(),Files.getLastModifiedTime(input).toMillis());record(a,"task",request,result);
        assertEquals("unverified",a.search("大文件结论",5).get(0).sourceState);
        assertFalse(a.search("大文件结论",5).get(0).currentEvidence);
        assertTrue(a.search("大文件结论",5,null,null,0,0,false).isEmpty());
    }
}
