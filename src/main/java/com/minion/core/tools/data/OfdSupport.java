package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.ViewImageTool;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Shared OFD entry points. Tool callers check file permissions before passing the path here. */
public final class OfdSupport {
    // The normal tool-output store inlines at most 8000 chars; return explicit OFD continuations instead.
    private static final int TOOL_CHARS = 8000, IMPORT_CHARS = 120000;
    private static final long MAX_IMAGE_PIXELS = 24_000_000L;
    private static final String NO_TEXT = "[未提取到可读文字（不是空白页证明）；扫描图请用 Document ofd_view 或 ofd_ocr。]";
    private static final String LIMITS = "仅提取原生文字层，不包含图像文字、批注或验签；表格与特殊版面顺序需对照原件。";
    private static final String SCAN_LIMITS = "仅提取本页引用的原始内嵌扫描图，不是整页渲染；不含印章、版面叠加或其他对象。";

    private OfdSupport() { }

    public static String preview(Path input) throws IOException {
        try (OfdReader ofd = new OfdReader(input)) {
            StringBuilder out = new StringBuilder(header(input, ofd)).append("\n此处仅预览前3页文字；详读用 Document ofd_read 按页读取。\n");
            for (int page = 1; page <= Math.min(3, ofd.pageCount()); page++) {
                OfdReader.PageText item = ofd.text(page);
                out.append(label(page));
                int end = safeEnd(item.text, 5000);
                out.append(item.text.isEmpty() ? NO_TEXT : item.text.substring(0, end));
                if (item.truncated || end < item.text.length()) out.append("\n[本页预览已截取，请用 Document ofd_read 详读。]");
            }
            return out.toString();
        } catch (IOException e) { throw readableError(e); }
    }

    static ToolResult inspect(Path input) throws IOException {
        try (OfdReader ofd = new OfdReader(input)) {
            return ToolResult.success(header(input, ofd) + "\n页码从1开始，按包内文档及页面目录顺序编号。");
        }
    }

    /** `Read` defaults to preview; its text-file line offset/limit are not OFD page numbers. */
    public static ToolResult readFile(Path input) {
        try { return ToolResult.success(preview(input)); }
        catch (IOException e) { return ToolResult.errorWithoutSideEffects(e.getMessage()); }
    }

    static ToolResult read(Path input, JsonObject args) throws IOException {
        int start = integer(args, "startPage", 1), end = integer(args, "endPage", start + 4);
        int offset = integer(args, "pageTextOffset", 0);
        if (start < 1 || end < start || (long) end - start >= 20 || offset < 0)
            throw new IOException("OFD页码从1开始，每次最多20页；pageTextOffset须非负");
        if (offset > 0 && start != end) throw new IOException("页内续读时 startPage 与 endPage 必须相同");
        try (OfdReader ofd = new OfdReader(input)) {
            if (start > ofd.pageCount()) throw new IOException("起始页超出OFD总页数: " + ofd.pageCount());
            StringBuilder out = new StringBuilder(header(input, ofd));
            int last = start - 1;
            for (int page = start; page <= Math.min(end, ofd.pageCount()); page++) {
                OfdReader.PageText item = ofd.text(page);
                int from = page == start ? offset : 0;
                if (from > item.text.length() || (from > 0 && from < item.text.length() && Character.isLowSurrogate(item.text.charAt(from))))
                    throw new IOException("pageTextOffset超出本页文本或不在字符边界，请使用上次返回的续读参数");
                int available = TOOL_CHARS - out.length() - 512;
                if (available < 200) break;
                out.append(label(page));
                if (item.text.isEmpty()) out.append(NO_TEXT);
                else {
                    int to = safeEnd(item.text, from + available);
                    out.append(item.text, from, to);
                    if (to < item.text.length()) {
                        return ToolResult.success(out.append("\n[第").append(page).append("页尚有文字。下一次 startPage=")
                                .append(page).append(" endPage=").append(page).append(" pageTextOffset=").append(to).append("]").toString());
                    }
                }
                if (item.truncated) out.append("\n[本页超过解析上限，有未提取文字，不能作为已读全文。]");
                last = page;
            }
            out.append("\n本次已检查至第").append(last).append("页文字层");
            if (last < ofd.pageCount()) out.append("；下一次 startPage=").append(last + 1);
            else out.append("；已到最后一页。");
            return ToolResult.success(out.toString());
        }
    }

    static String importText(Path input) throws IOException {
        try (OfdReader ofd = new OfdReader(input)) {
            StringBuilder out = new StringBuilder(header(input, ofd));
            boolean hasText = false;
            int last = 0;
            for (int page = 1; page <= Math.min(200, ofd.pageCount()) && out.length() < IMPORT_CHARS; page++) {
                OfdReader.PageText item = ofd.text(page);
                hasText |= !item.text.isEmpty();
                out.append(label(page));
                int to = safeEnd(item.text, IMPORT_CHARS - out.length());
                out.append(item.text.isEmpty() ? NO_TEXT : item.text.substring(0, to));
                if (item.truncated || to < item.text.length()) out.append("\n[本页文字未完整导入]");
                last = page;
            }
            if (!hasText) throw new IOException("OFD已检查范围内没有可索引文字；扫描件请先用 Document ofd_ocr 或导出带文字层版本。");
            if (last < ofd.pageCount()) out.append("\n[知识库导入上限：仅检查到第").append(last).append("页，请按页查看后续内容]");
            return out.toString();
        } catch (IOException e) { throw readableError(e); }
    }

    static ToolResult scan(Path input, JsonObject args, Path workspace, boolean ocr, String exe, String lang) throws IOException {
        int page = integer(args, "startPage", 1), end = integer(args, "endPage", page), index = integer(args, "imageIndex", 0);
        if (page < 1 || end != page || index < 0) throw new IOException("OFD扫描图每次只读1页；页码、imageIndex从1开始");
        try (OfdReader ofd = new OfdReader(input)) {
            if (page > ofd.pageCount()) throw new IOException("起始页超出OFD总页数: " + ofd.pageCount());
            List<String> resources = ofd.imageResources(page);
            if (resources.isEmpty()) throw new IOException("未找到本页直接引用的内嵌扫描图；若需看版式、矢量内容或签章，请用本地OFD阅读器转为PDF后再读取");
            if (index == 0 && resources.size() > 1)
                return ToolResult.success("第" + page + "页有" + resources.size() + "个内嵌图像资源，可能包含装饰图；请用 imageIndex=1.." + resources.size() + " 分别读取。当前未提取图像/OCR。");
            if (index == 0) index = 1;
            if (index > resources.size()) throw new IOException("imageIndex超出本页内嵌图像资源数: " + resources.size());
            byte[] bytes = ofd.imageBytes(resources.get(index - 1));
            String format = rasterFormat(bytes);
            Path tmp = workspace.resolve(".minion").resolve("tmp");
            if (!PathsGuard.inside(workspace.toString(), tmp)) throw new IOException("OFD临时目录超出工作区");
            Files.createDirectories(tmp);
            if (!PathsGuard.inside(workspace.toString(), tmp)) throw new IOException("OFD临时目录超出工作区");
            Path image = Files.createTempFile(tmp, "ofd-scan-", "." + format);
            try {
                Files.write(image, bytes);
                JsonObject visualArgs = args.deepCopy();
                if (!visualArgs.has("detail")) visualArgs.addProperty("detail", "overview");
                ToolResult result = ocr ? LocalOcr.read(exe, lang, image, integer(args, "psm", 3)) : ViewImageTool.load(image, visualArgs);
                if (!result.ok) return result;
                String context = "OFD: " + input + "；第" + page + "页；内嵌图像 " + index + "/" + resources.size() + "。\n" + SCAN_LIMITS;
                if (ocr) return ToolResult.success(context + "\n" + result.output.replace(image.toString(), input + "#page=" + page + "&image=" + index));
                for (int i = 0; i < result.images.size(); i++) result.images.get(i).name =
                        input.getFileName() + "#page=" + page + "&image=" + index + (i == 0 ? "" : "&tile=" + i);
                ToolResult out = ToolResult.success(context + "\n" + result.output.replace(image.toString(), input + "#page=" + page + "&image=" + index));
                out.images = result.images;
                return out;
            } finally { Files.deleteIfExists(image); }
        }
    }

    static IOException readableError(IOException e) {
        if (e instanceof java.util.zip.ZipException) return new IOException("不能打开OFD：不是有效的OFD压缩包、已加密或文件损坏。", e);
        return e;
    }
    private static String rasterFormat(byte[] bytes) throws IOException {
        try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("扫描图编码在当前环境无法解码（例如JBIG2/JP2）；请先用本地阅读器转成PDF/PNG。");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS)
                    throw new IOException("OFD扫描图超过2400万像素；请先在本地阅读器导出较小图片。");
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.matches("[a-z0-9]{1,12}")) throw new IOException("未知的扫描图编码");
                return format;
            } finally { reader.dispose(); }
        }
    }
    private static int integer(JsonObject args, String key, int fallback) throws IOException {
        if (!args.has(key)) return fallback;
        try { return Integer.parseInt(args.get(key).getAsString()); }
        catch (RuntimeException bad) { throw new IOException(key + "须为整数"); }
    }
    private static String header(Path path, OfdReader ofd) {
        String source = path.toString();
        if (source.length() > 1200) source = source.substring(0, 600) + "…" + source.substring(source.length() - 599);
        return "OFD: " + source + "\n" + ofd.description() + "\n" + LIMITS;
    }
    private static String label(int page) { return "\n[第" + page + "页]\n"; }
    private static int safeEnd(String text, int max) {
        int to = Math.max(0, Math.min(text.length(), max));
        return to > 0 && to < text.length() && Character.isHighSurrogate(text.charAt(to - 1)) ? to - 1 : to;
    }
}
