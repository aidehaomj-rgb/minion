package com.minion.core.context;
import com.minion.core.agent.*;
import com.minion.core.llm.Message;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class WorkingSetProjectionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private TaskState task(Path file)throws Exception {TaskState s=new TaskState();s.begin("修改当前文件",true);s.recordEvidence("root","Read",true,"read",FileEvidence.capture(file),false);return s;}
    @Test public void refreshLabelsActualVersionInsteadOfPretendingOldReadSnapshot()throws Exception {
        Path file=temp.newFile("current.java").toPath();Files.write(file,"old".getBytes("UTF-8"));TaskState s=task(file);
        Files.write(file,"new".getBytes("UTF-8"));Message out=WorkingSetProjection.build(s,p->p.startsWith(temp.getRoot().toPath()));
        assertNotNull(out);assertEquals(Message.Origin.TOOL_EVIDENCE,out.origin);assertFalse(out.hasUserAuthorization());
        assertTrue(out.content.contains("changed_since_receipt"));assertTrue(out.content.contains("\"fragment\":\"new\""));
    }
    @Test public void newerRevisionNeverReadsOlderTasksFile()throws Exception {
        Path file=temp.newFile("old.md").toPath();TaskState s=task(file);s.addGuidance("改为处理另一份材料");
        assertNull(WorkingSetProjection.build(s,p->{fail("old revision must not trigger file access");return true;}));
    }
    @Test public void unauthorizedAndBinaryFilesAreNotRestored()throws Exception {
        Path file=temp.newFile("private.txt").toPath();TaskState s=task(file);assertNull(WorkingSetProjection.build(s,p->false));
        Path binary=temp.newFile("book.xlsx").toPath();assertNull(WorkingSetProjection.build(task(binary),p->true));
    }
    @Test public void textExcerptIsBoundedAndOriginalEvidenceUnchanged()throws Exception {
        Path file=temp.newFile("long.txt").toPath();Files.write(file,String.join("",java.util.Collections.nCopies(6000,"字")).getBytes("UTF-8"));
        TaskState s=task(file);String hash=s.evidence.get(0).file.sha256;Message out=WorkingSetProjection.build(s,p->true);
        assertTrue(out.content.contains("\"truncated\":true"));assertTrue(out.content.length()<4000);assertEquals(hash,s.evidence.get(0).file.sha256);
    }
}
