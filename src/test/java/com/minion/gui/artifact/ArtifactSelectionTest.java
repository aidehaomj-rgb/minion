package com.minion.gui.artifact;
import com.minion.core.agent.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ArtifactSelectionTest {
    @Test public void queuedOldViewEventCannotBelongToNewManagerSelection(){Object a=new Object(),b=new Object();assertFalse(ArtifactSelection.sameOwner(a,b,a));assertFalse(ArtifactSelection.sameOwner(b,b,a));assertTrue(ArtifactSelection.sameOwner(b,b,b));assertFalse(ArtifactSelection.sameOwner(null,null,null));}
    private GeneratedArtifact file(String path,String kind,String task,long updated){GeneratedArtifact a=new GeneratedArtifact();a.path=path;a.kind=kind;a.taskId=task;a.updatedAt=updated;return a;}
    @Test public void automaticExecutionRecordCannotDisplaceTheActualDeliverable() {
        Session s=Session.create(".","test");s.taskState.id="current";
        s.artifacts=new ArrayList<GeneratedArtifact>(Arrays.asList(file("record.md","execution_record","current",100),file("report.docx","deliverable","current",50)));
        assertEquals("report.docx",ArtifactSelection.preferred(s));assertEquals(1,ArtifactSelection.ordered(s).size());
    }
    @Test public void executionRecordIsHiddenWhileExistingResultsRemainAvailable() {
        Session s=Session.create(".","test");s.taskState.id="new";
        s.artifacts=new ArrayList<GeneratedArtifact>(Arrays.asList(file("old.docx","deliverable","old",100),
                file("new.md","execution_record","new",200),file("input.pdf","reference","new",300),
                file("script.py","deliverable","new",400)));
        assertEquals("old.docx",ArtifactSelection.preferred(s));assertEquals(2,ArtifactSelection.ordered(s).size());
    }
    @Test public void referenceOnlySessionStillExposesUserVisibleFiles() {
        Session s=Session.create(".","test");s.taskState.id="current";
        s.artifacts=new ArrayList<GeneratedArtifact>(Arrays.asList(file("report.docx","reference","current",100),
                file("helper.py","reference","current",200)));
        assertEquals("report.docx",ArtifactSelection.preferred(s));assertEquals(1,ArtifactSelection.ordered(s).size());
    }
    @Test public void executionOnlySessionHasNoResultOrPreviewCandidate() {
        Session s=Session.create(".","test");s.taskState.id="current";
        s.artifacts=new ArrayList<GeneratedArtifact>(Arrays.asList(file("record.md","execution_record","current",100)));
        assertNull(ArtifactSelection.preferred(s));assertTrue(ArtifactSelection.ordered(s).isEmpty());
    }
    @Test public void sameKindUsesLatestUpdateAndLegacyNullIsEmpty() {
        Session s=Session.create(".","test");assertNull(ArtifactSelection.preferred(s));
        s.taskState.id="x";s.artifacts=new ArrayList<GeneratedArtifact>(Arrays.asList(file("a.docx","deliverable","x",1),file("b.pptx","deliverable","x",2)));
        assertEquals("b.pptx",ArtifactSelection.preferred(s));assertNotNull(ArtifactSelection.find(s,"a.docx"));
        assertEquals("a.docx",s.artifacts.get(0).path);
    }
    @Test public void workspaceAcceptsDocumentsSlidesImagesButNotSourceCode() {
        assertTrue(ArtifactSelection.userFacingFile("报告.md"));
        assertTrue(ArtifactSelection.userFacingFile("附件.DOCX"));
        assertTrue(ArtifactSelection.userFacingFile("演示.pptx"));
        assertTrue(ArtifactSelection.userFacingFile("图片.png"));
        assertFalse(ArtifactSelection.userFacingFile("main.py"));
        assertFalse(ArtifactSelection.userFacingFile("app.js"));
        assertFalse(ArtifactSelection.userFacingFile("README"));
    }
}
