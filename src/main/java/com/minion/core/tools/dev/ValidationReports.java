package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 只计本次新建或内容/时间变化的报告，不从 exit=0 或旧控制台摘要推测测试成功。 */
final class ValidationReports {
    static final class Counts {
        long tests, failures, errors, skipped;
        void add(Counts c) { tests += c.tests; failures += c.failures; errors += c.errors; skipped += c.skipped; }
    }
    static final class Evidence {
        Counts counts;
        int staleFiles;
        final List<Path> files = new ArrayList<Path>();
    }

    private ValidationReports() { }

    static Map<Path, String> snapshot(Path root, List<Path> directories) throws IOException {
        Map<Path, String> result = new LinkedHashMap<Path, String>();
        for (Path file : xmlFiles(root, directories)) result.put(file, signature(file));
        return result;
    }

    static Evidence collect(Path root, List<Path> locations, Map<Path, String> before) throws IOException {
        Evidence evidence = new Evidence();
        for (Path file : xmlFiles(root, locations)) {
            if (signature(file).equals(before.get(file))) { evidence.staleFiles++; continue; }
            Counts c = parseJUnit(file);
            if (evidence.counts == null) evidence.counts = new Counts();
            evidence.counts.add(c);
            evidence.files.add(file);
        }
        return evidence;
    }

    static Counts unittest(Path path, String runId) throws IOException {
        JsonObject json = ValidationDiscovery.json(path);
        if (!json.has("runId") || !runId.equals(json.get("runId").getAsString())) throw new IOException("unittest 报告 runId 不属于本次执行");
        Counts c = new Counts();
        try {
            c.tests = json.get("tests").getAsLong(); c.failures = json.get("failures").getAsLong();
            c.errors = json.get("errors").getAsLong(); c.skipped = json.get("skipped").getAsLong();
        } catch (RuntimeException e) { throw new IOException("unittest 计数缺失", e); }
        validate(c);
        return c;
    }

    static Counts parseJUnit(Path path) throws IOException {
        if (Files.size(path) > 20L * 1024 * 1024) throw new IOException("JUnit 报告超过 20MB，未解析，不能认定测试通过: " + path);
        try (InputStream in = Files.newInputStream(path)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false); f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            Element doc = builder.parse(in).getDocumentElement();
            if (!"testsuite".equals(doc.getTagName()) && !"testsuites".equals(doc.getTagName())) throw new IOException("不是 JUnit 测试报告: " + path);
            return suite(doc);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("JUnit 解析失败: " + path + ": " + e.getMessage(), e); }
    }

    private static Counts suite(Element e) throws IOException {
        Counts c = new Counts();
        boolean nested = false;
        NodeList children = e.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && ("testsuite".equals(n.getNodeName()) || "testsuites".equals(n.getNodeName()))) {
                nested = true; c.add(suite((Element) n));
            }
        }
        if (nested) {
            // 不重复相加，也不允许父节点的额外错误被子节点的零错误吞掉。
            if (e.hasAttribute("tests") && number(e, "tests") != c.tests) throw new IOException("JUnit 父子测试计数不一致");
            c.failures = Math.max(c.failures, number(e, "failures"));
            c.errors = Math.max(c.errors, number(e, "errors"));
            c.skipped = Math.max(c.skipped, number(e, "skipped"));
            validate(c);
            return c;
        }
        if (e.hasAttribute("tests")) {
            c.tests = number(e, "tests"); c.failures = number(e, "failures"); c.errors = number(e, "errors");
            c.skipped = e.hasAttribute("skipped") ? number(e, "skipped") : number(e, "disabled");
        } else {
            for (int i = 0; i < children.getLength(); i++) if (children.item(i) instanceof Element && "testcase".equals(children.item(i).getNodeName())) {
                Element test = (Element) children.item(i); c.tests++;
                if (test.getElementsByTagName("failure").getLength() > 0) c.failures++;
                else if (test.getElementsByTagName("error").getLength() > 0) c.errors++;
                else if (test.getElementsByTagName("skipped").getLength() > 0) c.skipped++;
            }
        }
        // 即便 suite 错写 failures=0，实际失败节点仍应阻断。
        c.failures = Math.max(c.failures, e.getElementsByTagName("failure").getLength());
        c.errors = Math.max(c.errors, e.getElementsByTagName("error").getLength());
        c.skipped = Math.max(c.skipped, e.getElementsByTagName("skipped").getLength());
        validate(c);
        return c;
    }
    private static long number(Element e, String key) throws IOException {
        try { return e.hasAttribute(key) ? Long.parseLong(e.getAttribute(key)) : 0; }
        catch (NumberFormatException ex) { throw new IOException("非法 JUnit 计数 " + key, ex); }
    }
    private static void validate(Counts c) throws IOException {
        if (c.tests < 0 || c.failures < 0 || c.errors < 0 || c.skipped < 0 || c.failures + c.errors + c.skipped > c.tests)
            throw new IOException("不一致的测试计数");
    }

    private static List<Path> xmlFiles(Path root, List<Path> locations) throws IOException {
        Map<Path, Boolean> result = new LinkedHashMap<Path, Boolean>();
        for (Path path : locations) {
            ValidationDiscovery.requireInside(root, path);
            if (!Files.exists(path)) continue;
            if (Files.isRegularFile(path)) { result.put(path.toRealPath(), true); continue; }
            try (Stream<Path> stream = Files.walk(path, 30)) {
                // 不跟随符号链接；每个真实报告再次核对工作区边界。
                for (java.util.Iterator<Path> it = stream.iterator(); it.hasNext();) {
                    Path file = it.next();
                    if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".xml")) continue;
                    ValidationDiscovery.requireInside(root, file);
                    result.put(file.toRealPath(), true);
                }
            }
        }
        return new ArrayList<Path>(result.keySet());
    }
    private static String signature(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toString() + ":" + Files.size(file) + ":" + SourceVersion.fileDigest(file);
    }
}
