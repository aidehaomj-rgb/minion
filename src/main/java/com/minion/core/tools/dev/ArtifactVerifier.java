package com.minion.core.tools.dev;

import com.google.gson.*;
import com.minion.core.tools.PathsGuard;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Predicate;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** 有界、无写入、无进程启动的确定性检查。通过仅证明列出的检查，不证明可运行。 */
public final class ArtifactVerifier {
    private ArtifactVerifier() { }
    public static final long MAX_FILE_BYTES = 256L * 1024 * 1024;

    public static JsonObject stableFile(Path path, int stableMs, int timeoutMs,
                                        Predicate<Path> allowed) throws Exception {
        int stable = Math.max(100, Math.min(stableMs, 5000));
        long end = System.nanoTime() + Math.max(stable + 100, Math.min(timeoutMs, 30000)) * 1000000L;
        BasicFileAttributes previous = null;
        long unchangedAt = System.nanoTime();
        while (System.nanoTime() < end) {
            checkPath(path, allowed);
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".crdownload") || name.endsWith(".part") || name.endsWith(".tmp"))
                throw new IOException("临时下载文件不能算完成");
            if (Files.isRegularFile(path)) {
                BasicFileAttributes now = Files.readAttributes(path, BasicFileAttributes.class);
                if (now.size() > MAX_FILE_BYTES) throw new IOException("文件超过256MB检查上限");
                if (!same(previous, now)) { previous = now; unchangedAt = System.nanoTime(); }
                if (System.nanoTime() - unchangedAt >= stable * 1000000L) {
                    String hash = sha256(path);
                    checkPath(path, allowed);
                    BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
                    if (!same(now, after)) { previous = after; unchangedAt = System.nanoTime(); continue; }
                    JsonObject out = new JsonObject();
                    out.addProperty("status", "passed"); out.addProperty("scope", "stable_file_sha256");
                    out.addProperty("path", path.toRealPath().toString()); out.addProperty("size", after.size());
                    out.addProperty("modifiedAt", after.lastModifiedTime().toMillis());
                    out.addProperty("sha256", hash); out.addProperty("stableMs", stable);
                    out.addProperty("runtimeVerified", false);
                    return out;
                }
            } else { previous = null; unchangedAt = System.nanoTime(); }
            pause(50);
        }
        throw new IOException("文件不存在或未稳定完成，不能认定已下载");
    }

    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return a != null && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && Objects.equals(a.fileKey(), b.fileKey());
    }

    public static void checkPath(Path p, Predicate<Path> allowed) throws IOException {
        if (allowed == null || !allowed.test(p.toAbsolutePath().normalize())) throw new IOException("路径未获授权: " + p);
        if (Files.exists(p) && !allowed.test(p.toRealPath())) throw new IOException("真实路径未获授权: " + p);
    }

    public static String sha256(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int n; long read = 0;
            while ((n = in.read(buffer)) != -1) {
                read += n; if (read > MAX_FILE_BYTES) throw new IOException("文件超过256MB检查上限");
                md.update(buffer, 0, n);
            }
        }
        return hex(md.digest());
    }

    /** Stored OOXML evidence only: no Office launch, pagination or formula evaluation. */
    public static JsonObject office(Path path, String action, JsonObject args) throws Exception {
        if (!"docx".equals(action) && !"xlsx".equals(action)) throw new IllegalArgumentException("Unsupported Office action");
        return OfficeArtifactVerifier.verify(path, action, args);
    }

    public static JsonObject image(Path path) throws Exception {
        JsonObject out = new JsonObject(); JsonArray fonts = new JsonArray();
        if (Files.size(path) > 20L * 1024 * 1024) throw new IOException("图像超过20MB检查上限");
        if (path.toString().toLowerCase(Locale.ROOT).endsWith(".svg")) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false); f.setExpandEntityReferences(false); f.setNamespaceAware(true);
            Document d;
            try (InputStream in = Files.newInputStream(path)) { d = f.newDocumentBuilder().parse(in); }
            Element root = d.getDocumentElement();
            if (!"svg".equals(root.getLocalName())) throw new IOException("不是SVG根元素");
            out.addProperty("format", "svg");
            out.addProperty("width", dimension(root.getAttribute("width")));
            out.addProperty("height", dimension(root.getAttribute("height")));
            out.addProperty("viewBox", root.getAttribute("viewBox"));
            Set<String> seen = new LinkedHashSet<String>();
            NodeList all = d.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                String font = ((Element) all.item(i)).getAttribute("font-family");
                if (!font.isEmpty() && seen.add(font)) fonts.add(font);
            }
            out.addProperty("fontListCoverage", "font-family_attributes_only_not_css");
        } else {
            try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
                if (in == null) throw new IOException("无法读取图像");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) throw new IOException("不支持的图像格式");
                ImageReader r = readers.next();
                try { r.setInput(in, true, true); out.addProperty("format", r.getFormatName());
                    out.addProperty("width", r.getWidth(0)); out.addProperty("height", r.getHeight(0));
                } finally { r.dispose(); }
            }
        }
        out.add("declaredFonts", fonts); out.addProperty("fontAvailability", "unknown");
        out.addProperty("overflow", "unknown"); out.addProperty("renderVerified", false);
        return out;
    }

    private static double dimension(String value) {
        try {
            if (!value.matches("[0-9]+(?:\\.[0-9]+)?(?:px)?")) return -1;
            return Double.parseDouble(value.replace("px", ""));
        } catch (Exception e) { return -1; }
    }

    public static JsonObject executable(Path p) throws Exception {
        String platform = "unknown", arch = "unknown";
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            if (f.length() >= 64 && f.readUnsignedByte() == 'M' && f.readUnsignedByte() == 'Z') {
                f.seek(0x3c); long offset = Integer.toUnsignedLong(Integer.reverseBytes(f.readInt()));
                if (offset <= f.length() - 24) {
                    f.seek(offset);
                    if (f.readInt() == 0x50450000) {
                        platform = "windows"; int machine = Short.toUnsignedInt(Short.reverseBytes(f.readShort()));
                        arch = machine == 0x8664 ? "x64" : machine == 0x14c ? "x86" : machine == 0xaa64 ? "arm64" : "unknown";
                    }
                }
            } else if (f.length() >= 8) {
                f.seek(0); int magic = f.readInt(); int cpu = f.readInt();
                if (magic == 0xfeedface || magic == 0xfeedfacf || magic == 0xcefaedfe || magic == 0xcffaedfe) {
                    platform = "macos";
                    if (magic == 0xcefaedfe || magic == 0xcffaedfe) cpu = Integer.reverseBytes(cpu);
                    arch = cpu == 0x01000007 ? "x64" : cpu == 0x0100000c ? "arm64" : cpu == 7 ? "x86" : "unknown";
                }
            }
        }
        JsonObject out = new JsonObject(); out.addProperty("generated", true);
        out.addProperty("platform", platform); out.addProperty("architecture", arch);
        out.addProperty("runtimeVerified", false); out.addProperty("buildLogVerified", false);
        out.addProperty("scope", "executable_header_only_not_launch_or_app_bundle_validation"); return out;
    }

    /** 默认精确主机白名单，无 DNS 猜测；重定向不跟随，不把 URL 当 shell 命令。 */
    public static boolean loopbackHttp(URI uri) {
        String h = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                && ("localhost".equalsIgnoreCase(h) || "127.0.0.1".equals(h) || "[::1]".equals(h) || "::1".equals(h));
    }

    public static JsonObject http(URI uri, Predicate<URI> allowed, String expectedText) throws Exception {
        if (uri.getUserInfo() != null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || allowed == null || !allowed.test(uri)) throw new IOException("HTTP目标未获授权（默认仅loopback HTTP）");
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(3000); c.setReadTimeout(3000);
        c.setRequestMethod("GET"); c.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code = c.getResponseCode(); JsonObject out = new JsonObject();
            out.addProperty("url", uri.toString()); out.addProperty("httpStatus", code);
            out.addProperty("scope", "http_response_not_browser_or_interaction");
            out.addProperty("redirectFollowed", false);
            InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 5000000000L;
            if (stream != null) try (InputStream in = stream) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (bytes.size() + n > 2 * 1024 * 1024 || System.nanoTime() > deadline) throw new IOException("HTTP响应超过2MB/5秒证据上限");
                    bytes.write(buf, 0, n);
                }
            }
            byte[] data = bytes.toByteArray();
            out.addProperty("bytes", data.length); out.addProperty("sha256", hex(MessageDigest.getInstance("SHA-256").digest(data)));
            boolean textOk = expectedText == null || expectedText.isEmpty()
                    || new String(data, java.nio.charset.StandardCharsets.UTF_8).contains(expectedText);
            out.addProperty("expectedTextMatched", textOk);
            out.addProperty("status", code >= 200 && code < 300 && textOk ? "passed" : "failed");
            out.addProperty("browserVerified", false); return out;
        } finally { c.disconnect(); }
    }

    static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format("%02x", b & 255)); return s.toString(); }
    public static void pause(int ms) throws IOException {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("验证已取消", e); }
    }
}
