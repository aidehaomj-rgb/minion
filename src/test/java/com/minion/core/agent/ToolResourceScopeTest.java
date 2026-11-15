package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.Workspace;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ToolResourceScopeTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JsonObject args(String json){return JsonParser.parseString(json).getAsJsonObject();}
    @Test public void excelMergeLocksBothInputsAndOutput() throws Exception {
        Path root=temp.newFolder().toPath();Workspace workspace=new Workspace(root.toString());
        ToolResourceScope scope=ToolResourceScope.resolve("Excel",args("{\"action\":\"merge\",\"path\":\"a.xlsx\",\"otherPath\":\"b.xlsx\",\"outputPath\":\"result.xlsx\"}"),workspace);
        assertTrue(scope.known);assertTrue(scope.paths.contains(root.resolve("a.xlsx")));
        assertTrue(scope.paths.contains(root.resolve("b.xlsx")));assertTrue(scope.paths.contains(root.resolve("result.xlsx")));
        try{scope.paths.clear();fail("immutable declaration");}catch(UnsupportedOperationException expected){}
    }
    @Test public void reportsAndValidationIncludeMaterialSidecar() throws Exception {
        Path root=temp.newFolder().toPath();Workspace workspace=new Workspace(root.toString());
        ToolResourceScope report=ToolResourceScope.resolve("Document",args("{\"action\":\"docx_report\",\"outputPath\":\"r.docx\"}"),workspace);
        ToolResourceScope validate=ToolResourceScope.resolve("Document",args("{\"action\":\"docx_validate\",\"path\":\"r.docx\"}"),workspace);
        assertTrue(report.known);assertTrue(validate.known);
        assertTrue(report.paths.contains(root.resolve("r.docx.material-state.json")));
        assertTrue(validate.paths.contains(root.resolve("r.docx.material-state.json")));
    }
    @Test public void unknownCodeDirectoryOutputsMissingAndMalformedResourcesRemainExclusive() throws Exception {
        Workspace workspace=new Workspace(temp.newFolder().getAbsolutePath());
        assertFalse(ToolResourceScope.resolve("Bash",args("{\"command\":\"echo ok\",\"path\":\"fake\"}"),workspace).known);
        assertFalse(ToolResourceScope.resolve("Document",args("{\"action\":\"pdf_split\",\"path\":\"a.pdf\",\"outputPath\":\"pages\"}"),workspace).known);
        assertFalse(ToolResourceScope.resolve("Document",args("{\"action\":\"office_preview\",\"path\":\"a.docx\",\"outputPath\":\"a.pdf\"}"),workspace).known);
        assertFalse(ToolResourceScope.resolve("Excel",args("{\"action\":\"write\"}"),workspace).known);
        assertFalse(ToolResourceScope.resolve("Read",args("{\"path\":{}}"),workspace).known);
        assertFalse(ToolResourceScope.resolve("Excel",args("{\"action\":\"read\",\"path\":\"a.xlsx\",\"filter\":\"arbitrary_expression\"}"),workspace).known);
        assertFalse(ToolResourceScope.resolve("Excel",args("{\"action\":\"batch_profile\",\"paths\":\"a;b\"}"),workspace).known);
    }
    @Test public void parallelWhitelistDoesNotTrustCallerReadOnlyOrResourceHints() {
        assertFalse(ToolResourceScope.parallelReadOnly("BrowserScreenshot",args("{\"readOnly\":true,\"profile\":\"separate\"}")));
        assertFalse(ToolResourceScope.parallelReadOnly("Bash",args("{\"command\":\"ls\"}")));
        assertFalse(ToolResourceScope.parallelReadOnly("Excel",args("{\"action\":\"read\",\"filter\":\"x > 0\"}")));
        assertTrue(ToolResourceScope.parallelReadOnly("Excel",args("{\"action\":\"read\",\"path\":\"a.xlsx\"}")));
    }
    @Test public void canonicalNewFileUsesExistingRealAncestorWithoutCreatingOutput() throws Exception {
        Path root=temp.newFolder().toPath();Path file=root.resolve("not-yet/sub/new.txt");
        assertEquals(root.toRealPath().resolve("not-yet/sub/new.txt"),ToolResourceScope.canonical(file));
        assertFalse(Files.exists(root.resolve("not-yet")));
    }
    @Test public void symbolicAliasesOfNewFilesShareCanonicalIdentityWhenOsPermitsLinks() throws Exception {
        Path root=temp.newFolder().toPath(),real=Files.createDirectory(root.resolve("real")),alias=root.resolve("alias");
        try{Files.createSymbolicLink(alias,real);}catch(Exception unsupported){Assume.assumeNoException(unsupported);}
        assertEquals(ToolResourceScope.canonical(real.resolve("new.txt")),ToolResourceScope.canonical(alias.resolve("new.txt")));
    }
}
