package com.minion.core.tools.data;
import com.minion.core.tools.*;
import com.minion.core.tools.python.PythonRuntime;
import com.minion.core.config.Config;
import com.minion.core.tools.confirm.*;
import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public class OfflineWorkflowIntegrationTest {
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 private PythonRuntime python;private Workspace ws;
 @Before public void setup()throws Exception{
  String exe=System.getProperty("minion.test.python","");Assume.assumeTrue("Python integration requires explicit runtime",Files.isRegularFile(Paths.get(exe)));
  python=new PythonRuntime(exe);ws=new Workspace(tmp.getRoot().toString());
  PythonRuntime.RunResult r=python.runCode("import pandas,openpyxl,docx",ws.cwd(),30);Assume.assumeTrue("missing Python data/office libraries: "+r.output,r.ok());
 }
 private void fixture(String code)throws Exception {PythonRuntime.RunResult r=python.runCode(code,ws.cwd(),30);assertTrue(r.output,r.ok());}
 private JsonObject a(String action,String... pairs){return PdfWorkflowTest.args(action,pairs);}
 @Test public void paginationTextIdsAndExactTotals()throws Exception{
  Files.write(ws.cwd().resolve("ids.csv"),("code,money\n0000123456789012345,0.1\n9001234567890123456,0.2\nNA,0.3\n").getBytes(StandardCharsets.UTF_8));
  ExcelTool t=new ExcelTool(ws,python);
  ToolResult r=t.execute(a("read","path","ids.csv","textColumns","code","offset","1","rows","1"));
  assertTrue(r.output,r.ok);assertTrue(r.output.contains("9001234567890123456"));assertFalse(r.output.contains("0000123456789012345"));assertTrue(r.output.contains("下一offset=2"));
  r=t.execute(a("validate","path","ids.csv","textColumns","code","valueColumn","money"));assertTrue(r.output,r.ok);assertTrue(r.output.contains("\"sum\": \"0.6\""));assertTrue(r.output.contains("\"blank\": 0"));
 }
 @Test public void mergeAuditExcludesBlankAndReportsDuplicateKeys()throws Exception{
  Files.write(ws.cwd().resolve("a.csv"),"code\nA\nA\nB\n\n".getBytes(StandardCharsets.UTF_8));
  Files.write(ws.cwd().resolve("b.csv"),"code\nA\nC\n".getBytes(StandardCharsets.UTF_8));
  ToolResult r=new ExcelTool(ws,python).execute(a("merge_audit","path","a.csv","otherPath","b.csv","on","code","textColumns","code"));
  assertTrue(r.output,r.ok);assertTrue(r.output.contains("\"leftDuplicateKeyRows\": 2"));assertTrue(r.output.contains("\"left_only\": 1"));assertTrue(r.output.contains("\"right_only\": 1"));
 }
 @Test public void largePagePersistsCompleteEvidenceInsteadOfSilentTruncation()throws Exception{
  String text=new String(new char[10000]).replace('\0','A')+"UNIQUE_END";
  Files.write(ws.cwd().resolve("large.csv"),("text\n"+text+"\n").getBytes(StandardCharsets.UTF_8));
  ToolResult r=new ExcelTool(ws,python).execute(a("read","path","large.csv","rows","1"));
  assertTrue(r.output,r.ok);assertTrue(r.output.contains("完整结果已落盘"));assertFalse(r.output.contains("UNIQUE_END"));
  try(java.util.stream.Stream<Path> s=Files.list(ws.cwd().resolve(".minion/tmp"))){
   Path file=s.filter(p->p.toString().endsWith(".result.txt")).findFirst().get();
   assertTrue(new String(Files.readAllBytes(file),StandardCharsets.UTF_8).contains("UNIQUE_END"));
  }
 }
 @Test public void patchPreservesSheetsStyleFormulaAndText()throws Exception{
  fixture("from openpyxl import Workbook\nfrom openpyxl.styles import Font\nw=Workbook();s=w.active;s.title='Data';s['A1']='code';s['B1']='formula';s['A2']='000123';s['A2'].font=Font(bold=True);s['B2']='=1+2';w.create_sheet('Other')['A1']='untouched';w.save('book.xlsx')");
  ExcelTool t=new ExcelTool(ws,python);ToolResult r=t.execute(a("patch_cells","path","book.xlsx","outputPath","filled.xlsx","data","[{\"sheet\":\"Data\",\"cell\":\"A2\",\"value\":\"9001234567890123456\"}]"));
  assertTrue(r.output,r.ok);
  fixture("from openpyxl import load_workbook\nw=load_workbook('filled.xlsx');assert w.sheetnames==['Data','Other'];assert w['Data']['A2'].value=='9001234567890123456';assert w['Data']['A2'].font.bold;assert w['Data']['B2'].value=='=1+2';assert w['Other']['A1'].value=='untouched';assert load_workbook('book.xlsx')['Data']['A2'].value=='000123'");
  assertFalse(t.execute(a("patch_cells","path","book.xlsx","outputPath","book.xlsx","data","[]")).ok);
 }
 @Test public void wordTemplateEvidenceStateAndPreviewFailureAreExplicit()throws Exception{
  fixture("from docx import Document\nd=Document();p=d.add_paragraph();p.add_run('{{NA').bold=True;p.add_run('ME}}');d.sections[0].header.paragraphs[0].text='{{NAME}}';d.add_table(rows=1,cols=1).cell(0,0).text='{{NAME}}';d.save('template.docx')");
  DocumentTool t=new DocumentTool(ws,python,null);
  ToolResult r=t.execute(a("docx_template","path","template.docx","outputPath","filled.docx","data","{\"fields\":{\"NAME\":\"标题\"}}"));assertTrue(r.output,r.ok);
  fixture("from docx import Document\nd=Document('filled.docx');assert d.paragraphs[0].text=='标题';assert d.paragraphs[0].runs[0].bold;assert d.sections[0].header.paragraphs[0].text=='标题';assert d.tables[0].cell(0,0).text=='标题'");
  r=t.execute(a("docx_report","path","template.docx","outputPath","report.docx","data","{\"fields\":{\"NAME\":\"报告\"},\"evidence\":[{\"id\":\"E1\",\"source\":\"a.pdf\",\"location\":\"第2页\"}],\"sections\":[{\"heading\":\"核验\",\"status\":\"原文\",\"evidenceIds\":[\"E1\"],\"paragraphs\":[\"样例证据\"]}]}"));assertTrue(r.output,r.ok);
  assertTrue(Files.exists(ws.cwd().resolve("report.docx.material-state.json")));
  r=t.execute(a("docx_validate","path","report.docx"));assertTrue(r.output,r.ok);assertTrue(r.output.contains("\"stateMatchesCurrentFile\": true"));assertTrue(r.output.contains("\"visualVerified\": false"));
  Config c=Config.load(ws.cwd());c.set("office.path","minion-nonexistent-office");
  r=new DocumentTool(ws,python,null,c,null).execute(a("office_preview","path","report.docx","outputPath","preview.pdf"));assertFalse(r.ok);assertFalse(Files.exists(ws.cwd().resolve("preview.pdf")));
 }
 @Test public void permissionsRejectNewOutputsInReadOnlyMode()throws Exception{
  Config c=Config.load(ws.cwd());c.set("permission.mode","read-only");ConfirmGate gate=new ConfirmGate(c,new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
  assertFalse(gate.check(new ExcelTool(ws,python),a("patch_cells","outputPath","new.xlsx")));
  assertFalse(gate.check(new DocumentTool(ws,python,null),a("docx_report","outputPath","new.docx")));
  assertTrue(gate.check(new DocumentTool(ws,python,null),a("pdf_view")));
 }
}
