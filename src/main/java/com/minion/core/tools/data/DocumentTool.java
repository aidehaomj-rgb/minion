package com.minion.core.tools.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.TextFiles;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Word/PDF/PPT 离线文档工具；读取与 PDF 操作 Java 原生，Office 生成使用可选 Python 库。 */
public final class DocumentTool implements Tool {
    private static final int MAX_TEXT = 200_000;
    private final Workspace workspace;
    private final PythonRuntime python;
    private final CheckpointStore checkpoints;

    public DocumentTool(Workspace workspace, PythonRuntime python, CheckpointStore checkpoints) {
        this.workspace = workspace; this.python = python; this.checkpoints = checkpoints;
    }
    @Override public String name() { return "Document"; }
    @Override public String description() {
        return "离线文档：extract/inspect 读取 PDF/DOCX/PPTX；pdf_merge/pdf_split；docx_create/docx_replace/pptx_create；ocr_status 检查 OCR";
    }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("Word/PDF/PPT 文档工具",
                new String[]{"action","path","paths","outputPath","data","find","replace","startPage","endPage"}, new String[]{"action"});
    }
    @Override public boolean isHighRisk(JsonObject args) {
        String a=text(args,"action").toLowerCase(Locale.ROOT);
        if (!(a.contains("create")||a.contains("replace")||a.startsWith("pdf_"))) return false;
        String out=text(args,"outputPath");
        return out.isEmpty()||Files.exists(resolve(out));
    }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action=text(args,"action").toLowerCase(Locale.ROOT);
        if ("ocr_status".equals(action)) return ocrStatus();
        if ("extract".equals(action)||"inspect".equals(action)) {
            Path input=requireInput(args);
            String ext=ext(input);
            if ("pdf".equals(ext)) return extractPdf(input, "inspect".equals(action));
            if ("docx".equals(ext)||"pptx".equals(ext)||"xlsx".equals(ext)) return ToolResult.success(extractText(input));
            return ToolResult.error("仅支持 PDF/DOCX/PPTX");
        }
        if ("pdf_merge".equals(action)) return mergePdf(args);
        if ("pdf_split".equals(action)) return splitPdf(args);
        if ("docx_create".equals(action)||"docx_replace".equals(action)||"pptx_create".equals(action)) return pythonOffice(action,args);
        return ToolResult.error("未知 action: "+action+"（支持 extract/inspect/pdf_merge/pdf_split/docx_create/docx_replace/pptx_create/ocr_status）");
    }

    private ToolResult extractPdf(Path input, boolean inspect) throws Exception {
        PDDocument doc=PDDocument.load(input.toFile());
        try {
            if(inspect) return ToolResult.success("PDF: "+input+"\n页数: "+doc.getNumberOfPages()+"\n加密: "+doc.isEncrypted());
            PDFTextStripper stripper=new PDFTextStripper();
            String text=stripper.getText(doc);
            if(text.trim().isEmpty()) return ToolResult.error("PDF 未提取到文字，可能是扫描件；请先运行 OCR");
            return ToolResult.success(limit(text));
        } finally { doc.close(); }
    }

    /** 知识库与其他核心工具复用的本地文本提取，不生成提示词边界。 */
    public static String extractText(Path input) throws Exception {
        String extension=ext(input);
        if("pdf".equals(extension)){
            PDDocument doc=PDDocument.load(input.toFile());
            try{return limit(new PDFTextStripper().getText(doc));}finally{doc.close();}
        }
        if("docx".equals(extension))return extractOoxml(input,"word/document.xml","</w:p>");
        if("pptx".equals(extension))return extractPptx(input);
        if("xlsx".equals(extension))return extractXlsx(input);
        if(("txt,md,csv,tsv,json,xml,yaml,yml,log,java,py,js,ts,html,css,sql,sh,ps1,bat,properties,ini,conf").contains(extension))
            return limit(TextFiles.decode(Files.readAllBytes(input)).text);
        throw new IllegalArgumentException("不支持提取的格式: "+extension);
    }

    private ToolResult mergePdf(JsonObject args) throws Exception {
        List<Path> inputs=paths(text(args,"paths"));
        if(inputs.size()<2)return ToolResult.error("pdf_merge 的 paths 至少需要两个 PDF（JSON数组或逗号分隔）");
        Path out=requireOutput(args,"pdf_merge"); if(out==null)return ToolResult.error("pdf_merge 需要工作区内 outputPath");
        checkpoint(out,"Document.pdf_merge");
        PDFMergerUtility merger=new PDFMergerUtility();
        for(Path p:inputs){if(!Files.isRegularFile(p)||!"pdf".equals(ext(p)))return ToolResult.error("PDF 不存在: "+p);merger.addSource(p.toFile());}
        merger.setDestinationFileName(out.toString()); merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
        return ToolResult.success("已合并 PDF: "+out);
    }

    private ToolResult splitPdf(JsonObject args) throws Exception {
        Path input=requireInput(args); Path out=requireOutput(args,"pdf_split");
        if(out==null)return ToolResult.error("pdf_split 需要 outputPath（作为输出目录）");
        Files.createDirectories(out);
        PDDocument doc=PDDocument.load(input.toFile());
        try {
            int start=Math.max(1,integer(args,"startPage",1)); int end=Math.min(doc.getNumberOfPages(),integer(args,"endPage",doc.getNumberOfPages()));
            if(start>end)return ToolResult.error("页码范围非法");
            int count=0;
            for(int page=start;page<=end;page++){
                PDDocument one=new PDDocument();
                try{one.importPage(doc.getPage(page-1));one.save(out.resolve(String.format(Locale.ROOT,"page-%04d.pdf",page)).toFile());count++;}
                finally{one.close();}
            }
            return ToolResult.success("已拆分 "+count+" 页到: "+out);
        } finally { doc.close(); }
    }

    private ToolResult pythonOffice(String action,JsonObject args)throws Exception{
        Path input=null;
        if("docx_replace".equals(action))input=requireInput(args);
        Path out=requireOutput(args,action);if(out==null)return ToolResult.error(action+" 需要工作区内 outputPath");
        checkpoint(out,"Document."+action);
        Path helper=writeHelper();
        try{
            List<String> argv=Arrays.asList(action,input==null?EMPTY:input.toString(),out.toString(),arg(text(args,"data")),arg(text(args,"find")),arg(text(args,"replace")));
            PythonRuntime.RunResult r=python.runFile(helper,argv,workspace.cwd(),180);
            String output=r.output==null?"":r.output.trim();
            return r.ok()?ToolResult.success(output):ToolResult.error("文档生成失败；请检查 python-docx/python-pptx 兼容依赖\n"+output);
        }finally{Files.deleteIfExists(helper);}
    }

    private ToolResult ocrStatus(){
        try{
            Process p=new ProcessBuilder("tesseract","--version").redirectErrorStream(true).start();
            boolean done=p.waitFor(8, TimeUnit.SECONDS); if(!done){p.destroy();return ToolResult.error("Tesseract OCR 响应超时");}
            java.io.BufferedReader br=new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));String first=br.readLine();br.close();
            return ToolResult.success("Tesseract: "+(first==null?"已安装":first)+"\n扫描 PDF 可先转图片后 OCR；当前版本提供依赖检查与人工流程入口。");
        }catch(Exception e){return ToolResult.error("未找到 Tesseract OCR。内网安装兼容 Win7 的 Tesseract 4.x 后即可调用。");}
    }

    private Path requireInput(JsonObject args)throws Exception{
        String value=text(args,"path");if(value.isEmpty())throw new IllegalArgumentException("缺少 path");
        Path p=resolve(value);ToolResult g=PathsGuard.errorIfOutside(workspace.workDir(),p);if(g!=null)throw new IllegalArgumentException(g.output);
        if(!Files.isRegularFile(p))throw new IllegalArgumentException("文件不存在: "+p);return p;
    }
    private Path requireOutput(JsonObject args,String action)throws Exception{
        String value=text(args,"outputPath");if(value.isEmpty())return null;Path p=resolve(value);
        ToolResult g=PathsGuard.errorIfOutside(workspace.workDir(),p);if(g!=null)throw new IllegalArgumentException("输出路径越出工作区");
        if(!"pdf_split".equals(action)&&p.getParent()!=null)Files.createDirectories(p.getParent());return p;
    }
    private List<Path> paths(String raw){
        List<Path> out=new ArrayList<Path>();if(raw==null||raw.trim().isEmpty())return out;
        try{JsonArray a=new Gson().fromJson(raw,JsonArray.class);for(JsonElement e:a)out.add(resolve(e.getAsString()));}
        catch(Exception e){for(String s:raw.split(","))if(!s.trim().isEmpty())out.add(resolve(s.trim()));}return out;
    }
    private void checkpoint(Path out,String action)throws Exception{if(checkpoints!=null)checkpoints.create(out,action);}
    private Path writeHelper()throws Exception{Path d=workspace.cwd().resolve(".minion/tmp");Files.createDirectories(d);Path p=Files.createTempFile(d,"document-",".py");Files.write(p,PY_SCRIPT.getBytes(StandardCharsets.UTF_8));return p;}
    private Path resolve(String value){return PathsGuard.resolve(workspace.cwd().toString(),value).toAbsolutePath().normalize();}

    private static String extractOoxml(Path file,String entryName,String paragraphEnd)throws Exception{
        ZipFile zip=new ZipFile(file.toFile());try{ZipEntry e=zip.getEntry(entryName);if(e==null)return "未找到正文";return xmlText(read(zip.getInputStream(e)),paragraphEnd);}finally{zip.close();}
    }
    private static String extractPptx(Path file)throws Exception{
        ZipFile zip=new ZipFile(file.toFile());try{List<ZipEntry> slides=new ArrayList<ZipEntry>();Enumeration<? extends ZipEntry> en=zip.entries();while(en.hasMoreElements()){ZipEntry e=en.nextElement();if(e.getName().matches("ppt/slides/slide[0-9]+\\.xml"))slides.add(e);}Collections.sort(slides,new Comparator<ZipEntry>(){@Override public int compare(ZipEntry a,ZipEntry b){return a.getName().compareTo(b.getName());}});StringBuilder sb=new StringBuilder();for(ZipEntry e:slides)sb.append("\n["+e.getName()+"]\n").append(xmlText(read(zip.getInputStream(e)),"</a:p>"));return limit(sb.toString());}finally{zip.close();}
    }
    private static String extractXlsx(Path file)throws Exception{
        ZipFile zip=new ZipFile(file.toFile());
        try{
            List<ZipEntry> sheets=new ArrayList<ZipEntry>();Enumeration<? extends ZipEntry> en=zip.entries();
            while(en.hasMoreElements()){ZipEntry e=en.nextElement();if(e.getName().matches("xl/worksheets/sheet[0-9]+\\.xml"))sheets.add(e);}
            Collections.sort(sheets,new Comparator<ZipEntry>(){@Override public int compare(ZipEntry a,ZipEntry b){return a.getName().compareTo(b.getName());}});
            StringBuilder sb=new StringBuilder();for(ZipEntry e:sheets)sb.append("\n["+e.getName()+"]\n").append(xmlText(read(zip.getInputStream(e)),"</row>"));return limit(sb.toString());
        }finally{zip.close();}
    }
    private static String read(InputStream in)throws Exception{try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}finally{in.close();}}
    private static String xmlText(String xml,String paragraphEnd){String s=xml.replace(paragraphEnd,"\n").replace("</w:tr>","\n").replace("</a:tr>","\n");s=s.replaceAll("<[^>]+>","");s=s.replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'");return limit(s.trim());}
    private static String limit(String s){return s.length()>MAX_TEXT?s.substring(0,MAX_TEXT)+"\n[内容已截断]":s;}
    private static String ext(Path p){String n=p.getFileName().toString();int i=n.lastIndexOf('.');return i<0?"":n.substring(i+1).toLowerCase(Locale.ROOT);}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
    private static String arg(String v){return v==null||v.isEmpty()?EMPTY:v;}
    private static final String EMPTY="__MINION_EMPTY__";
    private static final String PY_SCRIPT=
            "from __future__ import print_function\nimport sys,json,os,shutil\naction,src,out,data,find,repl=sys.argv[1:7]\n"
          + "def val(v): return '' if v=='__MINION_EMPTY__' else v\nsrc,data,find,repl=map(val,[src,data,find,repl])\n"
          + "if action=='docx_create':\n from docx import Document\n d=Document()\n for p in json.loads(data or '[]'): d.add_paragraph(str(p))\n d.save(out)\n print('DOCX 已生成: '+out)\n"
          + "elif action=='docx_replace':\n from docx import Document\n d=Document(src); count=0\n for p in d.paragraphs:\n  if find in p.text: p.text=p.text.replace(find,repl); count+=1\n for t in d.tables:\n  for row in t.rows:\n   for c in row.cells:\n    if find in c.text: c.text=c.text.replace(find,repl); count+=1\n d.save(out)\n print('DOCX 已替换 %d 处: %s'%(count,out))\n"
          + "elif action=='pptx_create':\n from pptx import Presentation\n r=Presentation()\n slides=json.loads(data or '[]')\n for item in slides:\n  s=r.slides.add_slide(r.slide_layouts[1]); s.shapes.title.text=str(item.get('title','')); s.placeholders[1].text=str(item.get('content',''))\n r.save(out)\n print('PPTX 已生成: '+out)\n"
          + "else: raise ValueError('unsupported action')\n";
}
