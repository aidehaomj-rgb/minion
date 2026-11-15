package com.minion.core.tools.data;

import com.minion.core.tools.ToolArguments;

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
    private final com.minion.core.config.Config config;
    private final com.minion.core.tools.confirm.ConfirmGate confirm;

    public DocumentTool(Workspace workspace, PythonRuntime python, CheckpointStore checkpoints) {
        this(workspace,python,checkpoints,null,null);
    }
    public DocumentTool(Workspace workspace, PythonRuntime python, CheckpointStore checkpoints,
                        com.minion.core.config.Config config,com.minion.core.tools.confirm.ConfirmGate confirm) {
        this.workspace = workspace; this.python = python; this.checkpoints = checkpoints;
        this.config=config;this.confirm=confirm;
    }
    @Override public String name() { return "Document"; }
    @Override public String description() {
        return "离线文档：inspect/extract；OFD用ofd_read按页提取文字，ofd_view附页内嵌扫描图/方位裁剪，ofd_ocr用已有本地Tesseract读内嵌图（非整页渲染/验签）；GD用gd_read（识别PDF/OFD内容；原生书生GD须给出exportPath或配置离线转换程序），gd_view显示PDF副本某页或OFD内嵌图；PDF用pdf_read按页文字/pdf_view单页图像(页码1起、dpi72-200、x/y/width/height裁剪、rotate旋转)；ocr执行本地图片OCR并附原图/ocr_status；pdf_merge/pdf_split；docx_create/docx_replace/pptx_create；docx_template模板占位符/docx_report带来源材料/docx_validate结构核验/office_preview转PDF。";
    }
    @Override public JsonObject schema() {
        JsonObject schema=SchemaGenerator.objectSchema("Word/PDF/OFD/GD/PPT 文档工具",
                new String[]{"action","path","paths","outputPath","exportPath","data","find","replace","startPage","endPage","pageTextOffset","imageIndex","dpi","x","y","width","height","rotate","language","psm"}, new String[]{"action"});
        schema.getAsJsonObject("properties").getAsJsonObject("data").addProperty("description",
                "JSON字符串。docx_template: {fields:{占位符名:文字}}，模板为{{名}}；docx_report: {title,fields,styles:{body:样式名},evidence:[{id,source,location}],sections:[{heading,level:1,status:原文/分析/已核对/待核实,evidenceIds:[id],paragraphs:[文字],tables:[二维数组]}]}。原文/已核对必须有证据ID，登记不代表已核验。");
        schema.getAsJsonObject("properties").getAsJsonObject("startPage").addProperty("description","从1开始；pdf_view/ofd_view/ofd_ocr每次1页；pdf_read/ofd_read每次最多20页（OFD默认5页）");
        schema.getAsJsonObject("properties").getAsJsonObject("pageTextOffset").addProperty("description","只用于ofd_read；按返回的续读偏移继续同页，同时设置startPage=endPage");
        schema.getAsJsonObject("properties").getAsJsonObject("imageIndex").addProperty("description","只用于ofd_view/ofd_ocr；一页多个内嵌图时选择第几张，从1开始");
        schema.getAsJsonObject("properties").getAsJsonObject("exportPath").addProperty("description","GD操作可选：用户指定的书生阅读器导出TXT/PDF/OFD副本，不是输出路径");
        schema.getAsJsonObject("properties").getAsJsonObject("outputPath").addProperty("description","输出新文件：模板/报告为.docx，预览为.pdf；缺LibreOffice预览会失败");
        return schema;
    }
    @Override public boolean isHighRisk(JsonObject args) {
        String a=text(args,"action").toLowerCase(Locale.ROOT);
        if (!(a.contains("create")||a.contains("replace")||a.equals("pdf_merge")||a.equals("pdf_split")
                ||a.equals("docx_template")||a.equals("docx_report")||a.equals("office_preview"))) return false;
        String out=text(args,"outputPath");
        return out.isEmpty()||Files.exists(resolve(out))
                ||(a.equals("docx_report")&&Files.exists(resolve(out+".material-state.json")));
    }
    public static boolean readOnlyAction(String action) {
        return Arrays.asList("inspect","extract","pdf_read","pdf_view","ofd_read","ofd_view","ofd_ocr","gd_read","gd_view","ocr","ocr_status","docx_validate").contains(action);
    }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action=text(args,"action").toLowerCase(Locale.ROOT);
        if ("ocr_status".equals(action)) return ocrStatus();
        if ("gd_read".equals(action)||"gd_view".equals(action)) return readGd(requireInput(args),args,false,"gd_view".equals(action));
        if (Arrays.asList("ofd_read","ofd_view","ofd_ocr").contains(action)) {
            Path input=requireInput(args);
            if (!"ofd".equals(ext(input)) && !(GdSupport.isGd(input)&&GdSupport.detect(input)==GdSupport.Kind.OFD))
                return ToolResult.errorWithoutSideEffects("此动作仅支持实际内容为OFD的文件");
            try {
                if ("ofd_read".equals(action)) return OfdSupport.read(input,args);
                String lang=text(args,"language");if(lang.isEmpty())lang=config==null?"chi_sim+eng":config.ocrLanguage();
                return OfdSupport.scan(input,args,Paths.get(workspace.workDir()).toAbsolutePath().normalize(),
                        "ofd_ocr".equals(action),config==null?"tesseract":config.ocrPath(),lang);
            } catch (java.io.IOException invalid) {
                return ToolResult.errorWithoutSideEffects(OfdSupport.readableError(invalid).getMessage());
            }
        }
        if ("pdf_read".equals(action)||"pdf_view".equals(action)) {
            Path input=requireInput(args);
            if(!"pdf".equals(ext(input))&&!(GdSupport.isGd(input)&&GdSupport.detect(input)==GdSupport.Kind.PDF))
                return ToolResult.errorWithoutSideEffects("此动作仅支持实际内容为PDF的文件");
            return PdfViews.read(input,args,"pdf_view".equals(action),workspace.cwd().resolve(".minion/tmp"));
        }
        if ("ocr".equals(action)) {
            Path input=requireInput(args);
            ToolResult pixels=com.minion.core.tools.ViewImageTool.load(input,new JsonObject());
            if(!pixels.ok)return pixels;
            String lang=text(args,"language");if(lang.isEmpty())lang=config==null?"chi_sim+eng":config.ocrLanguage();
            ToolResult r=LocalOcr.read(config==null?"tesseract":config.ocrPath(),lang,input,integer(args,"psm",3));
            if(r.ok)r.images=pixels.images;return r;
        }
        if (java.util.Arrays.asList("docx_template","docx_report","docx_validate","office_preview").contains(action))
            return enhancedOffice(action,args);
        if ("extract".equals(action)||"inspect".equals(action)) {
            Path input=requireInput(args);
            String ext=ext(input);
            if ("gd".equals(ext)) return readGd(input,args,"inspect".equals(action),false);
            if ("pdf".equals(ext)) return extractPdf(input, "inspect".equals(action));
            if ("ofd".equals(ext)) try {
                return "inspect".equals(action)?OfdSupport.inspect(input):OfdSupport.read(input,args);
            } catch(java.io.IOException invalid) {
                return ToolResult.errorWithoutSideEffects(OfdSupport.readableError(invalid).getMessage());
            }
            if ("docx".equals(ext)||"pptx".equals(ext)||"xlsx".equals(ext)) return ToolResult.success(extractText(input));
            return ToolResult.error("仅支持 PDF/OFD/GD/DOCX/PPTX/XLSX");
        }
        if ("pdf_merge".equals(action)) return mergePdf(args);
        if ("pdf_split".equals(action)) return splitPdf(args);
        if ("docx_create".equals(action)||"docx_replace".equals(action)||"pptx_create".equals(action)) return pythonOffice(action,args);
        return ToolResult.error("未知 action: "+action+"；请参照Document工具说明");
    }

    private ToolResult readGd(Path input,JsonObject args,boolean inspect,boolean view)throws Exception{
        if(!GdSupport.isGd(input))return ToolResult.errorWithoutSideEffects("gd_read/gd_view须使用GD原件作为path");
        try {
            Path exported=text(args,"exportPath").isEmpty()?null:requireInput(args,"exportPath");
            Path work=Paths.get(workspace.workDir()).toAbsolutePath().normalize();
            return GdSupport.read(input,exported,args,inspect,view,work.resolve(".minion/tmp"),config);
        }catch(java.io.IOException invalid){return ToolResult.errorWithoutSideEffects(invalid.getMessage());}
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
        if("gd".equals(extension))return GdSupport.importText(input);
        if("ofd".equals(extension))return OfdSupport.importText(input);
        if("pdf".equals(extension))return extractPdfText(input);
        if("docx".equals(extension))return extractOoxml(input,"word/document.xml","</w:p>");
        if("pptx".equals(extension))return extractPptx(input);
        if("xlsx".equals(extension))return extractXlsx(input);
        if(("txt,md,csv,tsv,json,xml,yaml,yml,log,java,py,js,ts,html,css,sql,sh,ps1,bat,properties,ini,conf").contains(extension))
            return limit(TextFiles.decode(Files.readAllBytes(input)).text);
        throw new IllegalArgumentException("不支持提取的格式: "+extension);
    }

    public static String extractText(Path input,Workspace workspace,com.minion.core.config.Config config)throws Exception{
        if(GdSupport.isGd(input))return GdSupport.importText(input,Paths.get(workspace.workDir()).toAbsolutePath().normalize().resolve(".minion/tmp"),config);
        return extractText(input);
    }

    static String extractPdfText(Path input)throws Exception{
        try(PDDocument doc=PDDocument.load(input.toFile(),MemoryUsageSetting.setupTempFileOnly())){
            return limit(new PDFTextStripper().getText(doc));
        }
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
        return LocalOcr.status(config==null?"tesseract":config.ocrPath());
    }

    private ToolResult enhancedOffice(String action,JsonObject args)throws Exception {
        Path input="docx_report".equals(action)&&text(args,"path").isEmpty()?null:requireInput(args);
        if(input!=null&&!"docx".equals(ext(input)))return ToolResult.error("此动作仅支持DOCX");
        Path out="docx_validate".equals(action)?null:requireOutput(args,action);
        if(!"docx_validate".equals(action)&&out==null)return ToolResult.error("需要outputPath，默认不覆盖原文件");
        if(out!=null&&input!=null&&out.equals(input))return ToolResult.error("请输出新文件，不覆盖源模板/文档");
        if(out!=null){
            String expected="office_preview".equals(action)?"pdf":"docx";
            if(!expected.equals(ext(out)))return ToolResult.error("outputPath须为."+expected);
            if("docx_report".equals(action)) {
                ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),resolve(out+".material-state.json"));
                if(guard!=null)return guard;
            }
            checkpoint(out,action);
            if("docx_report".equals(action))checkpoint(resolve(out+".material-state.json"),action+".state");
        }
        JsonObject payload=new JsonObject();payload.addProperty("action",action);
        payload.addProperty("path",input==null?"":input.toString());payload.addProperty("outputPath",out==null?"":out.toString());
        payload.addProperty("workspace",workspace.cwd().toString());
        payload.addProperty("office",config==null?"soffice":config.officePath());
        payload.addProperty("data",text(args,"data"));
        payload.addProperty("statePath","");
        if("docx_validate".equals(action)&&input!=null) {
            Path state=resolve(input+".material-state.json");
            if(Files.isRegularFile(state)&&PathsGuard.errorIfOutside(workspace,null,null,state)==null&&Files.size(state)<=2L*1024*1024)
                payload.addProperty("statePath",state.toString());
        }
        if(input!=null&&Files.size(input)>50L*1024*1024)return ToolResult.error("DOCX超过50MB，请拆分处理");
        return ResourcePython.run(python,workspace.cwd(),"office_workflow.py",payload);
    }

    private Path requireInput(JsonObject args)throws Exception{
        return requireInput(args,"path");
    }
    private Path requireInput(JsonObject args,String key)throws Exception{
        String value=text(args,key);if(value.isEmpty())throw new IllegalArgumentException("缺少 "+key);
        Path p=resolve(value);ToolResult g=PathsGuard.errorIfOutside(workspace,null,null,p);
        if(g!=null&&(confirm==null||!confirm.checkReadOutside(this,args,p.toString())))throw new IllegalArgumentException(g.output);
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
        catch(Exception e){for(String s:raw.split(","))if(!s.trim().isEmpty())out.add(resolve(s.trim()));}
        for(Path p:out){ToolResult g=PathsGuard.errorIfOutside(workspace,null,null,p);if(g!=null)throw new IllegalArgumentException(g.output);}
        return out;
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
    private static int integer(JsonObject o,String k,int d){return ToolArguments.integer(o,k,d);}
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
