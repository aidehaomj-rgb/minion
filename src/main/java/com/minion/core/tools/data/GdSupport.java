package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.tools.TextFiles;
import com.minion.core.tools.ToolResult;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Local bridge for Sursen GD. No executable or undocumented native reader is bundled. */
public final class GdSupport {
    private static final long MAX_FILE = 50L * 1024 * 1024;
    private static final int MAX_TEXT_EXPORT = 2 * 1024 * 1024;
    private static final String GUIDE = "原生书生GD未直接解码。请用已安装的书生阅读器打开原件，将内容另存/导出为TXT、PDF或OFD副本（以当前版本支持为准）；也可复制文字粘贴。"
            + "有导出副本时可重新作为附件添加，或使用 Document gd_read(path=原GD, exportPath=导出副本)。不要改原文件后缀；没有读到正文不代表文件空白。";
    private GdSupport() { }

    enum Kind { PDF, OFD, NATIVE }
    public static boolean isGd(Path input) { return "gd".equals(extension(input)); }

    /** The attachment UI catches only this condition to present the explicit reader/export workflow. */
    public static final class ReaderRequired extends IOException {
        ReaderRequired() { super(GUIDE); }
    }

    static Kind detect(Path input) throws IOException {
        if (Files.size(input) > MAX_FILE) throw new IOException("GD/导出文件超过50MB，请先在阅读器分段导出");
        byte[] magic = new byte[8];
        try (DataInputStream in = new DataInputStream(Files.newInputStream(input))) {
            try { in.readFully(magic); } catch (EOFException e) { return Kind.NATIVE; }
        }
        if (magic[0]=='%' && magic[1]=='P' && magic[2]=='D' && magic[3]=='F' && magic[4]=='-'
                && magic[5]>='1' && magic[5]<='2' && magic[6]=='.' && magic[7]>='0' && magic[7]<='9') return Kind.PDF;
        if (magic[0]=='P' && magic[1]=='K' && magic[2]==3 && magic[3]==4) {
            try (ZipFile zip=new ZipFile(input.toFile())) {
                if (zip.getEntry("OFD.xml")!=null && !zip.getEntry("OFD.xml").isDirectory()) return Kind.OFD;
            } catch (java.util.zip.ZipException invalid) { throw new IOException("GD的ZIP容器已损坏或无法读取，不能当作OFD正文",invalid); }
        }
        return Kind.NATIVE;
    }

    public static String preview(Path input) throws Exception {
        switch (detect(input)) {
            case PDF:
                ToolResult pdf=PdfViews.read(input,previewArgs(),false,null);
                if(!pdf.ok)throw new IOException(pdf.output);
                return "仅预览前3页PDF文字；完整读取使用 Document gd_read。\n"+pdf.output;
            case OFD: return OfdSupport.preview(input);
            default: throw new ReaderRequired();
        }
    }

    static String source(Path input, Path exported) {
        return "GD原件: "+input.toAbsolutePath().normalize()+"\n书生阅读器导出副本: "+exported.toAbsolutePath().normalize()
                +"\n副本由用户选择，不代表原件版式、导出完整性或电子签章已经核验。";
    }

    public static String exportPreview(Path input,Path exported) throws Exception {
        if (!Files.isRegularFile(input)||Files.isSameFile(input,exported)) throw new IOException("请为GD原件选择不同的导出副本");
        return source(input,exported)+"\n"+exportTextPreview(exported);
    }

    private static String exportTextPreview(Path exported) throws Exception {
        String ext=extension(exported);
        if ("txt".equals(ext)) {
            String text=exportedText(exported);
            int end=safeEnd(text,18000);
            return text.substring(0,end)+(end<text.length()?"\n[TXT副本预览已截断；请用Document gd_read续读]":"");
        }
        validateExport(exported);
        return preview(exported);
    }

    static ToolResult read(Path input, Path exported, JsonObject args, boolean inspect, boolean view, Path tmp,Config config) throws Exception {
        if (exported == null) {
            Kind kind=detect(input);
            if (kind==Kind.NATIVE) {
                if(!GdConverter.configured(config))throw new ReaderRequired();
                try(GdConverter converted=GdConverter.convert(input,tmp,config)){
                    return readExport(input,converted.output,args,inspect,view,tmp,true);
                }
            }
            return readKnown(input,args,kind,inspect,view,tmp);
        }
        return readExport(input,exported,args,inspect,view,tmp,false);
    }

    private static ToolResult readExport(Path input,Path exported,JsonObject args,boolean inspect,boolean view,Path tmp,
                                         boolean temporary)throws Exception {
        if (Files.isSameFile(input,exported)) throw new IOException("exportPath必须是另存的副本，不能指向原GD");
        String provenance=temporary?"GD原件: "+input+"\n正文来自已配置的本地转换程序；不代表版式保真或已验签。":source(input,exported);
        if ("txt".equals(extension(exported))) {
            if (view) throw new IOException("TXT导出副本只有文字，没有可显示的页面");
            String text=exportedText(exported);
            if (inspect) return ToolResult.success(provenance+"\nTXT，"+text.length()+"个字符；无版式或页码。");
            int offset=offset(args,text);
            int to=safeEnd(text,offset+5800);
            return ToolResult.success(provenance+"\n"+text.substring(offset,to)
                    +(to<text.length()?"\n[TXT副本续读：pageTextOffset="+to+"]":"\n[已到TXT副本末尾]"));
        }
        Kind kind=validateExport(exported);
        ToolResult result=readKnown(exported,args,kind,inspect,view,tmp);
        String output=temporary?result.output.replace(exported.toString(),input+"（转换副本）"):result.output;
        if (!result.ok) return ToolResult.errorWithoutSideEffects(output);
        ToolResult out=ToolResult.success(provenance+"\n"+output);
        out.images=result.images;
        if(temporary&&out.images!=null)for(com.minion.core.llm.ImagePart image:out.images)
            image.name=image.name.replace(exported.getFileName().toString(),input.getFileName().toString());
        return out;
    }

    private static ToolResult readKnown(Path input,JsonObject args,Kind kind,boolean inspect,boolean view,Path tmp)throws Exception {
        if (kind==Kind.OFD) {
            if (view) return OfdSupport.scan(input,args,tmp.getParent().getParent(),false,"","");
            return inspect?OfdSupport.inspect(input):OfdSupport.read(input,args);
        }
        if (inspect) {
            try (org.apache.pdfbox.pdmodel.PDDocument doc=org.apache.pdfbox.pdmodel.PDDocument.load(input.toFile(),
                    org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())) {
                return ToolResult.success("识别到PDF内容："+input+"\n总页数: "+doc.getNumberOfPages()+"；加密: "+doc.isEncrypted());
            }
        }
        return PdfViews.read(input,args,view,tmp);
    }

    static String importText(Path input)throws Exception {
        switch(detect(input)) {
            case OFD:return OfdSupport.importText(input);
            case PDF:return DocumentTool.extractPdfText(input);
            default:throw new ReaderRequired();
        }
    }

    static String importText(Path input,Path tmp,Config config)throws Exception {
        try{return importText(input);}
        catch(ReaderRequired needed){
            if(!GdConverter.configured(config))throw needed;
            try(GdConverter converted=GdConverter.convert(input,tmp,config)){
                Path output=converted.output;
                String text;
                if("txt".equals(extension(output)))text=exportedText(output);
                else text=importText(output).replace(output.toString(),input+"（转换副本）");
                if(text.trim().isEmpty())throw new IOException("本地转换副本未提取到正文，不导入空白条目");
                return "[通过已配置的离线转换程序读取；未验签]\n"+text;
            }
        }
    }

    private static Kind validateExport(Path exported)throws IOException {
        String ext=extension(exported);
        if (!"pdf".equals(ext)&&!"ofd".equals(ext)) throw new IOException("导出副本仅支持TXT、PDF或OFD");
        Kind kind=detect(exported);
        if (!kind.name().equalsIgnoreCase(ext)) throw new IOException("导出副本的实际格式与后缀不符；请在书生阅读器中真正导出文件，不能仅改名");
        return kind;
    }
    private static int offset(JsonObject a,String text)throws IOException {
        try {
            int offset=a.has("pageTextOffset")?Integer.parseInt(a.get("pageTextOffset").getAsString()):0;
            if(offset<0||offset>text.length()||(offset<text.length()&&Character.isLowSurrogate(text.charAt(offset))))throw new IllegalArgumentException();
            return offset;
        }catch(RuntimeException invalid){throw new IOException("TXT的pageTextOffset无效；请使用上次返回的偏移");}
    }
    private static String exportedText(Path file)throws IOException {
        byte[] bytes;
        try(InputStream in=Files.newInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int n;
            while((n=in.read(buffer))!=-1){
                if(n>MAX_TEXT_EXPORT-out.size())throw new IOException("TXT副本超过2MB，请分段导出");
                out.write(buffer,0,n);
            }
            bytes=out.toByteArray();
        }
        try {
            String text;
            boolean utf16=bytes.length>=2&&(((bytes[0]&255)==255&&(bytes[1]&255)==254)||((bytes[0]&255)==254&&(bytes[1]&255)==255));
            if(utf16)text=StandardCharsets.UTF_16.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            else text=TextFiles.decode(bytes).text;
            if(text.startsWith("\uFEFF"))text=text.substring(1);
            if(text.trim().isEmpty()||text.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'&&c!='\f'))
                throw new IOException("TXT副本未含可读文本；请检查是否确实另存为文本");
            return text;
        }catch(java.nio.charset.CharacterCodingException|IllegalStateException invalid){throw new IOException("无法解码TXT副本（支持UTF-8、带BOM的UTF-16、GBK）",invalid);}
    }
    private static JsonObject previewArgs(){JsonObject args=new JsonObject();args.addProperty("endPage",3);return args;}
    private static int safeEnd(String text,int max){int n=Math.min(text.length(),max);return n>0&&n<text.length()&&Character.isHighSurrogate(text.charAt(n-1))?n-1:n;}
    private static String extension(Path p){String n=p.getFileName().toString();int i=n.lastIndexOf('.');return i<0?"":n.substring(i+1).toLowerCase(Locale.ROOT);}
}
