package com.minion.gui.artifact;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Offline, read-only Java 8 preview extraction. Call on a worker thread, not the UI thread.
 * No URL is opened, no ZIP entry is extracted, and no image pixels are decoded.
 * Anticipated unsupported formats return a notice; missing/unreadable/unsafe/corrupt inputs
 * throw an actionable IOException. There is no attempt to decrypt or execute Office content.
 */
public final class DocumentPreviewLoader {
    public static final int MAX_MARKDOWN_CHARS = 200_000;
    public static final int MAX_TEXT_BYTES = 1024 * 1024;
    public static final long MAX_DOCX_BYTES = 32L * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 2048;
    public static final int MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024;
    public static final long MAX_ZIP_TOTAL_BYTES = 32L * 1024 * 1024;
    public static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    public static final int MAX_IMAGES = 8;
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_DIRECT_IMAGE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_TOTAL_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_IMAGE_DIMENSION = 8192;
    public static final long MAX_IMAGE_PIXELS = 16_000_000L;
    public static final int MAX_TABLE_ROWS = 100;
    public static final int MAX_TABLE_COLUMNS = 20;
    public static final int MAX_SLIDES = 100;
    private static final int MAX_CELL_CHARS = 4000;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String WS = "http://purl.oclc.org/ooxml/wordprocessingml/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String RS = "http://purl.oclc.org/ooxml/officeDocument/relationships";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String AS = "http://purl.oclc.org/ooxml/drawingml/main";
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String PS = "http://purl.oclc.org/ooxml/presentationml/main";
    private static final String V = "urn:schemas-microsoft-com:vml";
    private static final String PREVIEW_NOTICE = "内容预览并非 Word 分页完全保真；原文未修改，请用 Word/WPS 打开查看完整排版。";
    private static final Set<String> TEXT_FORMATS = new HashSet<String>(Arrays.asList(
            "md", "markdown", "txt", "csv", "tsv", "json", "jsonl", "log", "yaml", "yml", "xml", "ini", "properties",
            "py", "pyw", "java", "html", "htm", "svg", "js", "mjs", "cjs", "jsx", "ts", "tsx", "css", "scss", "less",
            "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "cs", "go", "rs", "rb", "php", "sql", "kt", "kts", "swift",
            "sh", "bash", "bat", "cmd", "ps1", "psm1", "vue", "svelte", "toml", "gradle", "conf", "config", "lock"));

    private DocumentPreviewLoader() { }

    /** Result.format is the lowercase filename extension without a dot. */
    public static Result load(Path path) throws IOException {
        if (path == null) throw new IOException("未提供文档路径。");
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("无法预览：路径不是普通文件：" + path);
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String format = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if ("doc".equals(format)||"ppt".equals(format)||"xls".equals(format)) {
            OfficePreviewConverter.Conversion converted=OfficePreviewConverter.convert(path);
            if(converted!=null)return new Result("", "已用 "+converted.engine
                    +" 转换为全页 PDF 预览；原文件未修改。", false, format,
                    Collections.<EmbeddedImage>emptyList(),converted.pdf,
                    Collections.<XlsxPreview.Sheet>emptyList(),
                    Collections.<SlidePreview>emptyList());
            return new Result("", "旧版 ."+format+" 无法直接预览，需要本机 Word/WPS/LibreOffice "
                    + "才能转换；当前未检测到可用转换器。 "+PREVIEW_NOTICE, false, format,
                    Collections.<EmbeddedImage>emptyList());
        }
        if ("html".equals(format)||"htm".equals(format)) return html(path,format);
        if (TEXT_FORMATS.contains(format)) return text(path, format);
        if (Arrays.asList("png","jpg","jpeg","gif","bmp").contains(format)) {
            if(attributes.size()>MAX_DIRECT_IMAGE_BYTES) throw new IOException("图片超过 10 MiB 预览限制；请打开原图。");
            byte[] data;try(InputStream in=Files.newInputStream(path)){data=prefix(in,MAX_DIRECT_IMAGE_BYTES+1);}
            if(data.length>MAX_DIRECT_IMAGE_BYTES||!safeImage(data))throw new IOException("图片损坏、为动图或尺寸超过预览限制。");
            return new Result("","本地图片预览",false,format,Collections.singletonList(new EmbeddedImage(name,data)));
        }
        if ("pdf".equals(format)) return pdf(path,attributes.size());
        if ("xlsx".equals(format)) {
            if(attributes.size()>MAX_DOCX_BYTES)throw new IOException("XLSX 超过 32 MiB 预览限制，请打开原件。");
            List<XlsxPreview.Sheet> sheets=XlsxPreview.load(path);
            return new Result("", "只读预览 "+sheets.size()+" 个工作表；最多显示 200 行、30 列。",
                    false, format, Collections.<EmbeddedImage>emptyList(), null, sheets,
                    Collections.<SlidePreview>emptyList());
        }
        if ("ofd".equals(format)||"gd".equals(format)) {
            try {
                String value="ofd".equals(format)?com.minion.core.tools.data.OfdSupport.preview(path)
                        :com.minion.core.tools.data.GdSupport.preview(path);
                return new Result(value,"仅预览可提取文字；原版式及签章请在本地阅读器查看。",false,format,Collections.<EmbeddedImage>emptyList());
            } catch(com.minion.core.tools.data.GdSupport.ReaderRequired nativeGd) {
                return new Result("","原生GD请用书生阅读器打开原件。",false,format,Collections.<EmbeddedImage>emptyList());
            } catch(IOException invalid) {throw invalid;}
            catch(Exception invalid) {throw new IOException("无法预览本地 "+format.toUpperCase(Locale.ROOT)+" 文件。",invalid);}
        }
        if (!"docx".equals(format)&&!"pptx".equals(format)) {
            return new Result("", "不支持该格式的离线内容预览，请使用关联应用打开原文。 " + PREVIEW_NOTICE,
                    false, format, Collections.<EmbeddedImage>emptyList());
        }
        if (attributes.size() > MAX_DOCX_BYTES)
            throw new IOException(format.toUpperCase(Locale.ROOT)+" 超过 32 MiB 预览限制，请用关联应用打开原文。");
        byte[] signature;
        try (InputStream in = Files.newInputStream(path)) { signature = prefix(in, 8); }
        if (signature.length >= 8 && signature[0] == (byte) 0xd0 && signature[1] == (byte) 0xcf
                && signature[2] == 0x11 && signature[3] == (byte) 0xe0) {
            throw new IOException("该 "+format.toUpperCase(Locale.ROOT)
                    +" 是加密或旧版 Office 复合文档，无法直接预览，请用关联应用打开或解密后另存。");
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            validateZip(zip);
            return "docx".equals(format)?new Docx(zip,path).read():new Pptx(zip,path).read();
        } catch (ZipException e) {
            throw new IOException(format.toUpperCase(Locale.ROOT)+" 已损坏、加密或不是有效的 ZIP 文档，请用关联应用打开。", e);
        } catch (IllegalArgumentException e) {
            throw new IOException(format.toUpperCase(Locale.ROOT)+" ZIP 条目损坏，无法安全预览，请用关联应用打开。", e);
        }
    }

    private static Result pdf(Path path,long size)throws IOException {
        if(size>32L*1024*1024) throw new IOException("PDF 超过 32 MiB 预览限制，请打开原件。");
        try(org.apache.pdfbox.pdmodel.PDDocument doc=org.apache.pdfbox.pdmodel.PDDocument.load(path.toFile(),
                org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())) {
            String message="共 "+doc.getNumberOfPages()+" 页";
            if(doc.getNumberOfPages()==0)return new Result("",message,false,"pdf",Collections.<EmbeddedImage>emptyList());
            org.apache.pdfbox.pdmodel.common.PDRectangle box=doc.getPage(0).getCropBox();
            double w=box.getWidth(),h=box.getHeight();
            if(!Double.isFinite(w)||!Double.isFinite(h)||w<=0||h<=0||Math.max(w,h)/Math.min(w,h)>80)
                throw new IOException("PDF 页面尺寸不适合内置预览，请打开原文件。");
            return new Result("","",message+" · 分页阅读器按需渲染；文本模式首次打开时提取",
                    false,"pdf",
                    Collections.<EmbeddedImage>emptyList(),path.toAbsolutePath(),path.toAbsolutePath(),
                    Collections.<XlsxPreview.Sheet>emptyList(),
                    Collections.<SlidePreview>emptyList());
        }
    }

    private static Result text(Path path, String format) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path)) { bytes = prefix(in, MAX_TEXT_BYTES + 1); }
        State state = new State();
        boolean partial = bytes.length > MAX_TEXT_BYTES;
        if (partial) state.cut("文本超过 1 MiB 读取边界，后续内容已截断。");
        int length = Math.min(bytes.length, MAX_TEXT_BYTES);
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) offset = 3;
        else if (length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE; offset = 2;
        } else if (length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE; offset = 2;
        }
        CharsetDecoder decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = CharBuffer.allocate(length + 1);
        try {
            // endOfInput=false deliberately discards only an incomplete final codepoint at a byte cutoff.
            CoderResult decoded = decoder.decode(ByteBuffer.wrap(bytes, offset, length - offset), chars, !partial);
            if (decoded.isError()) decoded.throwException();
            if (!partial) decoder.flush(chars);
        } catch (CharacterCodingException e) {
            throw new IOException("文本不是有效的 UTF-8/带 BOM 的 UTF-16，或为二进制文件；请用原应用打开。", e);
        }
        chars.flip();
        String value = chars.toString();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\f') {
                throw new IOException("检测到二进制控制字符，拒绝按文本预览，请用原应用打开。");
            }
        }
        String markdown;
        if ("md".equals(format) || "markdown".equals(format)) {
            Limited out = new Limited(state, MAX_MARKDOWN_CHARS); out.add(value); markdown = out.toString();
        } else markdown = codeBlock(value, format, state);
        String source=value.substring(0,Math.min(value.length(),MAX_MARKDOWN_CHARS));
        if(!source.isEmpty()&&Character.isHighSurrogate(source.charAt(source.length()-1)))source=source.substring(0,source.length()-1);
        return new Result(markdown, source, state.notice().replace(PREVIEW_NOTICE,"本地只读文本预览；原文件未修改。"), state.truncated, format, Collections.<EmbeddedImage>emptyList());
    }

    private static Result html(Path path,String format)throws IOException{
        Result text=text(path,format);
        String notice="HTML 以离线安全视图预览；脚本、外链资源、表单提交和弹窗已禁用。";
        return new Result(text.sourceText,text.sourceText,notice,text.truncated,format,
                Collections.<EmbeddedImage>emptyList());
    }

    private static String codeBlock(String value, String format, State state) {
        if (value.isEmpty()) return "";
        int backticks = longestRun(value, '`');
        int tildes = longestRun(value, '~');
        char delimiter = backticks <= tildes ? '`' : '~';
        int count = Math.max(3, Math.min(backticks, tildes) + 1);
        if (count > 64) {
            Limited out = new Limited(state, MAX_MARKDOWN_CHARS);
            out.add("    ");
            for (int i = 0; i < value.length() && !out.full(); i++) {
                out.add(value.substring(i, i + 1));
                if (value.charAt(i) == '\n') out.add("    ");
            }
            if (out.full()) state.cut("内容超过 200000 字符预览限制，已截断。");
            return out.toString();
        }
        char[] fenceChars = new char[count]; Arrays.fill(fenceChars, delimiter);
        String fence = new String(fenceChars);
        String open = fence + format + "\n", close = "\n" + fence;
        Limited content = new Limited(state, MAX_MARKDOWN_CHARS - open.length() - close.length());
        content.add(value);
        return open + content + close;
    }

    private static int longestRun(String value, char delimiter) {
        int longest = 0, run = 0;
        for (int i = 0; i < value.length(); i++) {
            run = value.charAt(i) == delimiter ? run + 1 : 0; longest = Math.max(longest, run);
        }
        return longest;
    }

    private static byte[] prefix(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int read;
        while (out.size() < limit && (read = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()))) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("文档预览读取已取消。");
            if (read > 0) out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void validateZip(ZipFile zip) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        Set<String> names = new HashSet<String>();
        int count = 0; long total = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement(); String name = entry.getName();
            if (++count > MAX_ZIP_ENTRIES) throw new IOException("ZIP 条目超过 2048 个安全上限，疑似 ZIP 炸弹。");
            if (!names.add(name) || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                    || name.indexOf('\0') >= 0 || Arrays.asList(name.split("/")).contains("..")) {
                throw new IOException("ZIP 含重复或不安全路径，拒绝预览。");
            }
            long size = entry.getSize(), compressed = entry.getCompressedSize();
            if (size < 0 || compressed < 0 || compressed > MAX_DOCX_BYTES || size > MAX_ZIP_ENTRY_BYTES) {
                throw new IOException("ZIP 条目超过 8 MiB 或大小无效，疑似 ZIP 炸弹。");
            }
            total += size;
            if (total > MAX_ZIP_TOTAL_BYTES) throw new IOException("ZIP 解压总量超过 32 MiB，疑似 ZIP 炸弹。");
            if (size > 65536 && (compressed == 0 || size > compressed * 200L)) {
                throw new IOException("ZIP 压缩比超过 200 倍，疑似 ZIP 炸弹，拒绝预览。");
            }
        }
    }

    private static byte[] entryBytes(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        if (entry.getSize() > limit) throw new IOException("DOCX 内部条目超过读取限制：" + entry.getName());
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) { bytes = prefix(in, limit + 1); }
        if (bytes.length > limit) throw new IOException("ZIP 实际解压内容超限，疑似 ZIP 炸弹。");
        CRC32 crc = new CRC32(); crc.update(bytes);
        if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc()) {
            throw new IOException("DOCX ZIP 条目损坏（长度/CRC 不匹配）：" + entry.getName());
        }
        return bytes;
    }

    /** Namespace-aware, depth/node/attribute-bounded SAX tree; all external resolution fails closed. */
    private static X xml(ZipFile zip, String name, boolean required) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            if (required) throw new IOException("DOCX 已损坏，缺少正文：" + name + "；请用 Word/WPS 打开。");
            return null;
        }
        byte[] bytes = entryBytes(zip, entry, MAX_XML_BYTES);
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true); factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            TreeHandler handler = new TreeHandler();
            reader.setContentHandler(handler); reader.setErrorHandler(handler); reader.setEntityResolver(handler);
            reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
            return handler.root;
        } catch (Exception e) {
            throw new IOException("DOCX XML 已损坏或不安全（禁止 DOCTYPE、外部实体及过深/过多节点）：" + name, e);
        }
    }

    private static final class TreeHandler extends DefaultHandler {
        final Deque<X> stack = new ArrayDeque<X>(); X root; int nodes;
        @Override public void startElement(String uri, String local, String qname, Attributes attributes) throws SAXException {
            if (stack.size() >= 64 || ++nodes > 50_000 || attributes.getLength() > 64) {
                throw new SAXException("XML 结构超出安全限制");
            }
            if (Thread.currentThread().isInterrupted()) throw new SAXException("预览已取消");
            X node = new X(uri, local);
            for (int i = 0; i < attributes.getLength(); i++) {
                node.attributes.put(attributes.getURI(i) + "|" + attributes.getLocalName(i), attributes.getValue(i));
            }
            if (stack.isEmpty()) root = node; else stack.peek().children.add(node);
            stack.push(node);
        }
        @Override public void endElement(String uri, String local, String qname) { stack.pop(); }
        @Override public void characters(char[] chars, int start, int length) {
            if (!stack.isEmpty()) stack.peek().text.append(chars, start, length);
        }
        @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
            throw new SAXException("禁止外部实体");
        }
        @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
    }

    private static final class X {
        final String ns, name;
        final Map<String, String> attributes = new HashMap<String, String>();
        final List<X> children = new ArrayList<X>(); final StringBuilder text = new StringBuilder();
        X(String ns, String name) { this.ns = ns; this.name = name; }
        boolean w(String local) { return name.equals(local) && (W.equals(ns) || WS.equals(ns)); }
        X child(String local) { for (X x : children) if (x.w(local)) return x; return null; }
        String attr(String uri, String local) { String value = attributes.get(uri + "|" + local); return value == null ? "" : value; }
        String val() { String value = attr(W, "val"); return value.isEmpty() ? attr(WS, "val") : value; }
        String r(String local) { String value = attr(R, local); return value.isEmpty() ? attr(RS, local) : value; }
    }

    /** Text-only PPTX preview with the same fail-closed OOXML limits as DOCX. */
    private static final class Pptx {
        final ZipFile zip;final Path source;final State state=new State();
        Pptx(ZipFile zip,Path source){this.zip=zip;this.source=source;}

        Result read()throws IOException{
            List<String> slides=slideParts();
            if(slides.isEmpty())throw new IOException("PPTX 中没有可读取的幻灯片。");
            Limited out=new Limited(state,MAX_MARKDOWN_CHARS);
            List<SlidePreview> cards=new ArrayList<SlidePreview>();
            int shown=0;
            for(String part:slides){
                if(out.full()||shown>=MAX_SLIDES)break;
                X slide=xml(zip,part,false);if(slide==null){state.notes.add("缺少幻灯片："+part);continue;}
                shown++;slide(slide,shown,out);
                cards.add(slidePreview(slide,shown,part));
            }
            if(slides.size()>MAX_SLIDES)state.cut("幻灯片超过 100 页，后续页未提取。");
            state.notes.add("PPTX 文字按幻灯片顺序提取；完整动画、交互和母版细节请打开原件。");
            return new Result(out.toString(),out.toString(),state.notice(),state.truncated,
                    "pptx",Collections.<EmbeddedImage>emptyList(),source.toAbsolutePath(),
                    null,Collections.<XlsxPreview.Sheet>emptyList(),cards);
        }

        private SlidePreview slidePreview(X root,int number,String part)throws IOException{
            List<X> paragraphs=new ArrayList<X>();paragraphs(root,paragraphs);
            List<String> lines=new ArrayList<String>();
            for(X paragraph:paragraphs){
                Limited text=new Limited(state,4000);inline(paragraph,text);
                String value=text.toString().replace('\r',' ').replaceAll("[\\t ]+"," ").trim();
                if(!value.isEmpty())lines.add(value);
                if(lines.size()>=10)break;
            }
            String title=lines.isEmpty()?"幻灯片 "+number:lines.remove(0);
            return new SlidePreview(number,title,lines,slideImages(part));
        }

        private List<EmbeddedImage> slideImages(String slidePart)throws IOException{
            String base=slidePart.substring(slidePart.lastIndexOf('/')+1);
            String relPart=slidePart.substring(0,slidePart.lastIndexOf('/')+1)
                    +"_rels/"+base+".rels";
            X rels=xml(zip,relPart,false);
            if(rels==null)return Collections.emptyList();
            List<EmbeddedImage> result=new ArrayList<EmbeddedImage>();
            for(X rel:rels.children){
                if(!"Relationship".equals(rel.name))continue;
                String type=rel.attr("","Type"),target=rel.attr("","Target");
                if(!type.endsWith("/image")||target.isEmpty())continue;
                String media=normalizeSlideMedia(target);
                if(media==null)continue;
                ZipEntry entry=zip.getEntry(media);
                if(entry==null||entry.getSize()<0||entry.getSize()>MAX_IMAGE_BYTES)continue;
                byte[] bytes=entryBytes(zip,entry,MAX_IMAGE_BYTES);
                if(safeImage(bytes))result.add(new EmbeddedImage(media,bytes));
                if(result.size()>=4)break;
            }
            return result;
        }

        private String normalizeSlideMedia(String target){
            if(target.indexOf('\\')>=0||target.indexOf(':')>=0||target.indexOf('\0')>=0)return null;
            try{
                String path=new URI(null,null,"/ppt/slides/"+target,null).normalize().getPath();
                if(path==null||!path.startsWith("/ppt/media/")||path.contains(".."))return null;
                return path.substring(1);
            }catch(URISyntaxException invalid){return null;}
        }

        private List<String> slideParts()throws IOException{
            List<String> ordered=new ArrayList<String>();
            Map<String,String> rels=new HashMap<String,String>();
            X relationships=xml(zip,"ppt/_rels/presentation.xml.rels",false);
            if(relationships!=null)for(X rel:relationships.children){
                if(!"Relationship".equals(rel.name))continue;
                rels.put(rel.attr("","Id"),rel.attr("","Target"));
            }
            X presentation=xml(zip,"ppt/presentation.xml",false);
            if(presentation!=null&&!rels.isEmpty()){
                X list=find(presentation,"sldIdLst");
                if(list!=null)for(X item:list.children){
                    if(!"sldId".equals(item.name))continue;
                    String id=item.attr(R,"id");if(id.isEmpty())id=item.attr(RS,"id");
                    String target=rels.get(id),part=slidePart(target);
                    if(part!=null&&!ordered.contains(part))ordered.add(part);
                }
            }
            if(!ordered.isEmpty())return ordered;
            for(Enumeration<? extends ZipEntry> entries=zip.entries();entries.hasMoreElements();){
                String name=entries.nextElement().getName();
                if(name.matches("ppt/slides/slide[0-9]+\\.xml"))ordered.add(name);
            }
            ordered.sort((a,b)->Integer.compare(slideNumber(a),slideNumber(b)));
            return ordered;
        }

        private String slidePart(String target){
            if(target==null||target.trim().isEmpty()||target.indexOf('\\')>=0||target.indexOf(':')>=0)return null;
            try{
                String path=new URI(null,null,target.startsWith("/")?target:"/ppt/"+target,null)
                        .normalize().getPath();
                if(path==null||!path.startsWith("/ppt/slides/")||path.contains(".."))return null;
                return path.substring(1);
            }catch(URISyntaxException invalid){return null;}
        }

        private int slideNumber(String part){
            int a=part.lastIndexOf("slide"),b=part.lastIndexOf('.');
            try{return Integer.parseInt(part.substring(a+5,b));}catch(RuntimeException invalid){return Integer.MAX_VALUE;}
        }

        private X find(X node,String local){
            if(local.equals(node.name))return node;
            for(X child:node.children){X found=find(child,local);if(found!=null)return found;}
            return null;
        }

        private void slide(X root,int number,Limited out){
            out.add("## 幻灯片 "+number+"\n\n");
            List<X> paragraphs=new ArrayList<X>();paragraphs(root,paragraphs);
            int count=0;
            for(X paragraph:paragraphs){
                Limited text=new Limited(state,8000);inline(paragraph,text);
                String value=text.toString().replace('\r',' ').replaceAll("[\\t ]+"," ").trim();
                if(value.isEmpty())continue;
                out.add(value);out.add("\n\n");count++;
            }
            if(count==0)out.add("（本页未提取到文字）\n\n");
        }

        private void paragraphs(X node,List<X> out){
            if((A.equals(node.ns)||AS.equals(node.ns))&&"p".equals(node.name))out.add(node);
            for(X child:node.children)paragraphs(child,out);
        }

        private void inline(X node,Limited out){
            if(out.full())return;
            if((A.equals(node.ns)||AS.equals(node.ns))&&"t".equals(node.name))escaped(node.text.toString(),out);
            else if((A.equals(node.ns)||AS.equals(node.ns))&&"br".equals(node.name))out.add("\n");
            for(X child:node.children)inline(child,out);
        }
    }

    private static final class Docx {
        final ZipFile zip; final Path source; final State state = new State();
        final Map<String, X> styles = new HashMap<String, X>();
        final Map<String, boolean[]> numbering = new HashMap<String, boolean[]>();
        final Map<String, Relationship> relationships = new HashMap<String, Relationship>();
        final Map<String, EmbeddedImage> images = new LinkedHashMap<String, EmbeddedImage>();
        final Set<String> attemptedImages = new HashSet<String>();
        int imageBytes;
        Docx(ZipFile zip,Path source) { this.zip = zip; this.source=source; }

        Result read() throws IOException {
            X document = xml(zip, "word/document.xml", true);
            if (document == null || !document.w("document") || document.child("body") == null) {
                throw new IOException("DOCX 正文结构损坏或命名空间无效，请用 Word/WPS 打开。");
            }
            readStyles(); readNumbering(); readRelationships();
            Limited out = new Limited(state, MAX_MARKDOWN_CHARS);
            blocks(document.child("body"), out);
            state.notes.add("仅提取正文、简单格式和安全附图；不执行宏、域、嵌入对象或外部关系，页眉页脚/批注/修订等不保证还原。");
            return new Result(out.toString(), out.toString(), state.notice(), state.truncated,
                    "docx", new ArrayList<EmbeddedImage>(images.values()),
                    source==null?null:source.toAbsolutePath(), null,
                    Collections.<XlsxPreview.Sheet>emptyList(),
                    Collections.<SlidePreview>emptyList());
        }

        void readStyles() throws IOException {
            X root = xml(zip, "word/styles.xml", false);
            if (root == null) return;
            for (X style : root.children) if (style.w("style")) {
                String id = style.attr(W, "styleId"); if (id.isEmpty()) id = style.attr(WS, "styleId");
                styles.put(id, style);
            }
        }

        void readNumbering() throws IOException {
            X root = xml(zip, "word/numbering.xml", false);
            if (root == null) return;
            Map<String, boolean[]> abstractNumbers = new HashMap<String, boolean[]>();
            for (X node : root.children) if (node.w("abstractNum")) {
                boolean[] ordered = new boolean[9];
                for (X level : node.children) if (level.w("lvl")) {
                    int index = integer(wordAttr(level, "ilvl"), 0, 8);
                    X fmt = level.child("numFmt");
                    ordered[index] = fmt != null && !"bullet".equals(fmt.val()) && !"none".equals(fmt.val());
                }
                abstractNumbers.put(wordAttr(node, "abstractNumId"), ordered);
            }
            for (X node : root.children) if (node.w("num")) {
                X id = node.child("abstractNumId");
                if (id != null) numbering.put(wordAttr(node, "numId"), abstractNumbers.get(id.val()));
            }
        }

        void readRelationships() throws IOException {
            X root = xml(zip, "word/_rels/document.xml.rels", false);
            if (root == null) return;
            for (X node : root.children) if (REL.equals(node.ns) && "Relationship".equals(node.name)) {
                String type = node.attr("", "Type"), target = node.attr("", "Target");
                boolean external = !node.attr("", "TargetMode").isEmpty()
                        && !"Internal".equalsIgnoreCase(node.attr("", "TargetMode"));
                if (external) state.notes.add("外部链接/外链图片已忽略，未发起网络或本地目标访问。");
                relationships.put(node.attr("", "Id"), new Relationship(type, target, external));
            }
        }

        void blocks(X parent, Limited out) throws IOException {
            for (X node : parent.children) {
                if (out.full()) { state.cut("内容超过 200000 字符预览限制，已截断。"); break; }
                if (node.w("p")) { out.add(paragraph(node, MAX_MARKDOWN_CHARS)); out.add("\n\n"); }
                else if (node.w("tbl")) table(node, out);
                else if (node.w("sdt") || node.w("sdtContent") || node.w("customXml") || node.w("ins")) blocks(node, out);
            }
        }

        String paragraph(X paragraph, int limit) throws IOException {
            Limited out = new Limited(state, limit);
            X properties = paragraph.child("pPr");
            X styleId = properties == null ? null : properties.child("pStyle");
            List<X> chain = new ArrayList<X>();
            if (properties != null) chain.add(properties);
            String id = styleId == null ? "" : styleId.val(); String originalId = id;
            Set<String> seen = new HashSet<String>();
            while (seen.add(id) && chain.size() < 16 && styles.containsKey(id)) {
                X style = styles.get(id); X ppr = style.child("pPr"); if (ppr != null) chain.add(ppr);
                X basedOn = style.child("basedOn"); id = basedOn == null ? "" : basedOn.val();
            }
            int heading = heading(originalId);
            X outline = property(chain, "outlineLvl");
            if (outline != null) { int level = integer(outline.val(), 9, 9); heading = level < 9 ? Math.min(6, level + 1) : 0; }
            if (heading > 0) for (int i = 0; i < heading; i++) out.add("#");
            if (heading > 0) out.add(" ");
            else {
                X num = property(chain, "numPr");
                X numId = num == null ? null : num.child("numId"), level = num == null ? null : num.child("ilvl");
                int depth = level == null ? 0 : integer(level.val(), 0, 8);
                boolean isList = numId != null && !"0".equals(numId.val());
                boolean ordered = false;
                if (isList) { boolean[] levels = numbering.get(numId.val()); ordered = levels != null && levels[depth]; }
                else if (num == null && originalId.toLowerCase(Locale.ROOT).startsWith("list")) {
                    isList = true; ordered = originalId.toLowerCase(Locale.ROOT).contains("number");
                }
                if (isList) { for (int i = 0; i < depth; i++) out.add("  "); out.add(ordered ? "1. " : "- "); }
            }
            inline(paragraph, out);
            return out.toString();
        }

        void inline(X node, Limited out) throws IOException {
            for (int childIndex=0;childIndex<node.children.size();childIndex++) {
                X child=node.children.get(childIndex);
                if (out.full()) { state.cut("正文或表格单元格内容已截断，请打开原文。"); break; }
                if (child.w("t")) escaped(child.text.toString(), out);
                else if (child.w("tab")) out.add("    ");
                else if (child.w("br") || child.w("cr")) out.add("\n");
                else if (child.w("r")) {
                    X props = child.child("rPr"), bold = props == null ? null : props.child("b");
                    boolean isBold = bold != null && !Arrays.asList("0", "false", "off").contains(bold.val().toLowerCase(Locale.ROOT));
                    if(!isBold)inline(child,out);
                    else {
                        Limited run=new Limited(state,Math.max(0,out.limit-out.length()-4));inline(child,run);
                        String value=run.toString();int start=0,end=value.length();
                        while(start<end&&Character.isWhitespace(value.charAt(start)))start++;
                        while(end>start&&Character.isWhitespace(value.charAt(end-1)))end--;
                        if(start==end)out.add(value);
                        else {
                            out.add(value.substring(0,start));out.add("**");out.add(value.substring(start,end));out.add("**");out.add(value.substring(end));
                            // Word runs may end in punctuation next to a number; Markdown needs a boundary to close emphasis.
                            char next=childIndex+1<node.children.size()?firstVisible(node.children.get(childIndex+1)):'\0';
                            if(end==value.length()&&next!='\0'&&!Character.isWhitespace(next))out.add(" ");
                        }
                    }
                } else if (((A.equals(child.ns) || AS.equals(child.ns)) && "blip".equals(child.name))
                        || (V.equals(child.ns) && "imagedata".equals(child.name))) {
                    String relationship = child.r("embed"); if (relationship.isEmpty()) relationship = child.r("id");
                    if (relationship.isEmpty()) state.notes.add("外链图片已忽略，未访问外部目标。");
                    else image(relationship, out);
                } else if (child.w("pPr") || child.w("rPr") || child.w("del") || child.w("instrText")
                        || child.w("object") || child.w("altChunk") || child.w("txbxContent")) {
                    // Never interpret fields, deleted text, embedded objects, HTML, macros, or external content.
                } else inline(child, out); // Hyperlinks contribute text only, never their target.
            }
        }

        private char firstVisible(X node) {
            if(node.w("t")&&node.text.length()>0)return node.text.charAt(0);
            if(node.w("rPr")||node.w("pPr"))return '\0';
            for(X child:node.children){char c=firstVisible(child);if(c!='\0')return c;}
            return '\0';
        }

        void table(X table, Limited out) throws IOException {
            List<X> rows = new ArrayList<X>(); int columns = 0, rowCount = 0;
            for (X row : table.children) if (row.w("tr")) {
                rowCount++;
                if (rows.size() < MAX_TABLE_ROWS) {
                    rows.add(row); int cells = 0; for (X cell : row.children) if (cell.w("tc")) cells++;
                    columns = Math.max(columns, cells);
                }
            }
            if (rowCount > MAX_TABLE_ROWS || columns > MAX_TABLE_COLUMNS) {
                state.cut("大表格已截断：每表最多 100 行、20 列；请打开原文查看完整表格。");
            }
            columns = Math.min(columns, MAX_TABLE_COLUMNS);
            if (columns == 0) return;
            for (int rowIndex = 0; rowIndex < rows.size() && !out.full(); rowIndex++) {
                out.add("| "); int column = 0;
                for (X cell : rows.get(rowIndex).children) if (cell.w("tc") && column < columns && !out.full()) {
                    Limited content = new Limited(state, MAX_CELL_CHARS);
                    for (X item : cell.children) {
                        if (item.w("p")) { if (content.length() > 0) content.add(" / "); content.add(paragraph(item, MAX_CELL_CHARS)); }
                        else if (item.w("tbl")) { content.add("（嵌套表格略）"); state.cut("嵌套表格已略过，请打开原文。"); }
                        if (content.full()) break;
                    }
                    out.add(content.toString().replace("\r", "").replace("\n", " / ")); out.add(" | "); column++;
                }
                while (column++ < columns && !out.full()) out.add(" | ");
                out.add("\n");
                if (rowIndex == 0) { out.add("|"); for (int i = 0; i < columns; i++) out.add(" --- |"); out.add("\n"); }
            }
            out.add("\n");
        }

        void image(String id, Limited out) throws IOException {
            Relationship rel = relationships.get(id);
            if (rel == null) { state.notes.add("部分图片关系缺失，未能读取附图。"); return; }
            if (rel.external) return;
            if (!(R + "/image").equals(rel.type) && !(RS + "/image").equals(rel.type)) return;
            String target = mediaTarget(rel.target);
            if (target == null) { state.notes.add("不安全或非内嵌媒体路径已忽略，未访问外部目标。"); return; }
            EmbeddedImage image = images.get(target);
            if (image == null) {
                if (!attemptedImages.add(target)) return;
                ZipEntry entry = zip.getEntry(target);
                if (entry == null) { state.notes.add("部分内嵌图片缺失。"); return; }
                if (images.size() >= MAX_IMAGES || entry.getSize() > MAX_IMAGE_BYTES
                        || entry.getSize() > MAX_TOTAL_IMAGE_BYTES - imageBytes) {
                    state.cut("附图已截断：最多 8 张、单图 2 MiB、总计 8 MiB；请打开原文查看全部图片。"); return;
                }
                byte[] bytes = entryBytes(zip, entry, MAX_IMAGE_BYTES);
                imageBytes += bytes.length; // Failed metadata checks also consume the read budget.
                if (!safeImage(bytes)) {
                    state.cut("部分图片格式/元数据无效或尺寸超限，已略过（仅静态 PNG/JPEG/GIF/BMP，单边≤8192、单图≤16MP）。"); return;
                }
                image = new EmbeddedImage(target, bytes); images.put(target, image);
            }
            out.add("（附图："); escaped(image.name.substring(image.name.lastIndexOf('/') + 1), out); out.add("，见附图列表）");
        }
    }

    /** Reads headers only. In particular, never call ImageIO.read or ImageReader.read here. */
    private static boolean safeImage(byte[] bytes) {
        try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Arrays.asList("png", "jpeg", "jpg", "gif", "bmp").contains(format)) return false;
                reader.setInput(stream, false, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                        || (long) width * height > MAX_IMAGE_PIXELS) return false;
                // Animated GIFs can have thousands of frames despite tiny compressed byte sizes.
                return !"gif".equals(format) || reader.getNumImages(true) == 1;
            } finally { reader.dispose(); }
        } catch (IOException e) { return false; }
        catch (RuntimeException e) { return false; }
    }

    private static String mediaTarget(String target) {
        try {
            URI uri = new URI(target);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            String path = uri.getPath();
            if (path == null || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) return null;
            String resolved = new URI(null, null, path.startsWith("/") ? path : "/word/" + path, null).normalize().getPath();
            return resolved.startsWith("/word/media/") && !resolved.endsWith("/") ? resolved.substring(1) : null;
        } catch (URISyntaxException e) { return null; }
    }

    private static String wordAttr(X node, String name) {
        String value = node.attr(W, name); return value.isEmpty() ? node.attr(WS, name) : value;
    }

    private static X property(List<X> chain, String name) {
        for (X item : chain) { X property = item.child(name); if (property != null) return property; }
        return null;
    }

    private static int integer(String value, int fallback, int max) {
        try { int number = Integer.parseInt(value); return number >= 0 && number <= max ? number : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }

    private static int heading(String style) {
        String name = style.toLowerCase(Locale.ROOT).replace(" ", "");
        if ("title".equals(name)) return 1;
        if (name.matches("(?:heading|标题)[1-9]")) return Math.min(6, name.charAt(name.length() - 1) - '0');
        return 0;
    }

    private static void escaped(String value, Limited out) {
        for (int i = 0; i < value.length(); i++) {
            if (out.full()) { out.state.cut("正文或表格单元格内容已截断，请打开原文。"); break; }
            char c = value.charAt(i);
            if ("\\`*_{}[]<>!|#+-.()~".indexOf(c) >= 0) out.add("\\");
            out.add(String.valueOf(c));
        }
    }

    private static final class Relationship {
        final String type, target; final boolean external;
        Relationship(String type, String target, boolean external) { this.type = type; this.target = target; this.external = external; }
    }

    private static final class State {
        boolean truncated; final Set<String> notes = new LinkedHashSet<String>();
        void cut(String message) { truncated = true; notes.add(message); }
        String notice() { StringBuilder notice = new StringBuilder(PREVIEW_NOTICE); for (String note : notes) notice.append(' ').append(note); return notice.toString(); }
    }

    private static final class Limited {
        final State state; final int limit; final StringBuilder value = new StringBuilder();
        Limited(State state, int limit) { this.state = state; this.limit = limit; }
        int length() { return value.length(); }
        boolean full() { return value.length() >= limit; }
        void add(String text) {
            int keep = Math.min(text.length(), limit - value.length());
            if (keep < text.length() && keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) keep--;
            value.append(text, 0, keep);
            if (keep < text.length()) state.cut("正文或表格单元格内容超过预览限制，已截断，请打开原文。");
        }
        @Override public String toString() {
            int size = value.length();
            return size > 0 && Character.isHighSurrogate(value.charAt(size - 1)) ? value.substring(0, size - 1) : value.toString();
        }
    }

    public static final class Result {
        public final String markdown;
        /** Original text for textual files; Markdown extraction for Word, never an outer fence. */
        public final String sourceText;
        public final String notice;
        public final boolean truncated;
        public final String format;
        /** Unmodifiable list; images are displayed separately, with no file/network URL in markdown. */
        public final List<EmbeddedImage> images;
        public final Path sourcePath;
        /** Optional converted/original PDF for the common paginated reader. */
        public final Path previewPdf;
        public final List<XlsxPreview.Sheet> sheets;
        public final List<SlidePreview> slides;
        public Result(String markdown, String notice, boolean truncated, String format, List<EmbeddedImage> images) {
            this(markdown,markdown,notice,truncated,format,images,null,null,
                    Collections.<XlsxPreview.Sheet>emptyList(),
                    Collections.<SlidePreview>emptyList());
        }
        Result(String markdown,String sourceText,String notice,boolean truncated,String format,List<EmbeddedImage> images) {
            this(markdown,sourceText,notice,truncated,format,images,null,null,
                    Collections.<XlsxPreview.Sheet>emptyList(),
                    Collections.<SlidePreview>emptyList());
        }
        Result(String markdown,String sourceText,String notice,boolean truncated,String format,
               List<EmbeddedImage> images,Path sourcePath,Path previewPdf,
               List<XlsxPreview.Sheet> sheets,List<SlidePreview> slides) {
            this.markdown = markdown; this.sourceText=sourceText;this.notice = notice; this.truncated = truncated; this.format = format;
            this.images = Collections.unmodifiableList(new ArrayList<EmbeddedImage>(images));
            this.sourcePath=sourcePath;this.previewPdf=previewPdf;
            this.sheets=Collections.unmodifiableList(new ArrayList<XlsxPreview.Sheet>(sheets));
            this.slides=Collections.unmodifiableList(new ArrayList<SlidePreview>(slides));
        }
        Result(String markdown,String notice,boolean truncated,String format,
               List<EmbeddedImage> images,Path previewPdf,
               List<XlsxPreview.Sheet> sheets,List<SlidePreview> slides) {
            this(markdown,markdown,notice,truncated,format,images,previewPdf,previewPdf,sheets,slides);
        }
    }

    public static final class SlidePreview {
        public final int number;
        public final String title;
        public final List<String> lines;
        public final List<EmbeddedImage> images;
        SlidePreview(int number,String title,List<String> lines,List<EmbeddedImage> images){
            this.number=number;this.title=title==null?"":title;
            this.lines=Collections.unmodifiableList(new ArrayList<String>(lines));
            this.images=Collections.unmodifiableList(new ArrayList<EmbeddedImage>(images));
        }
    }

    public static final class EmbeddedImage {
        /** ZIP media entry name, never an external path. bytes is a defensive copy on construction. */
        public final String name;
        public final byte[] bytes;
        public EmbeddedImage(String name, byte[] bytes) { this.name = name; this.bytes = bytes.clone(); }
    }
}
