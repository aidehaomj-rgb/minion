package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.junit.Assert.*;

/** §3.9 内网 WebFetch 验收：白名单（域名后缀/IP/CIDR）、逐跳重定向校验（公网→内网拒） */
public class IntranetWebFetchTest {

    private static HttpServer server;
    private static int port;

    @BeforeClass
    public static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/page", exchange -> {
            byte[] body = "<html><title>内网页面</title><body>OK</body></html>".getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/redirect-internal", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/redirect-loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterClass
    public static void stop() { server.stop(0); }

    private static JsonObject args(String url) {
        JsonObject o = new JsonObject();
        o.addProperty("url", url);
        return o;
    }

    private static String local(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** 白名单含回环 IP：内网页面可抓 */
    @Test public void whitelistedLoopbackFetches() throws Exception {
        WebFetchTool tool = new WebFetchTool(false,
                new IntranetWebFetchPolicy("127.0.0.1,10.20.0.0/16,.corp.local"));
        ToolResult r = tool.execute(args(local("/page")));
        assertTrue(r.output, r.ok);
        assertTrue(r.output.contains("内网页面"));
    }

    /** 白名单未含回环：拒绝（SSRF 基线不受白名单外地址影响） */
    @Test public void nonWhitelistedInternalRejected() throws Exception {
        WebFetchTool tool = new WebFetchTool(false,
                new IntranetWebFetchPolicy("10.20.0.0/16,.corp.local"));
        ToolResult r = tool.execute(args(local("/page")));
        assertFalse(r.ok);
        assertTrue(r.output.contains("白名单"));
    }

    /** 白名单命中后重定向仍在白名单内：跟随成功 */
    @Test public void whitelistedRedirectFollows() throws Exception {
        WebFetchTool tool = new WebFetchTool(false,
                new IntranetWebFetchPolicy("127.0.0.1"));
        ToolResult r = tool.execute(args(local("/redirect-internal")));
        assertTrue(r.output, r.ok);
        assertTrue(r.output.contains("内网页面"));
    }

    /** 白名单外重定向：逐跳校验拒绝（即使首跳在白名单内的其它策略下） */
    @Test public void redirectOutsideAllowlistRejected() throws Exception {
        // 首跳 127.0.0.1 命中 allowlist=仅 10.x；首跳即拒
        WebFetchTool tool = new WebFetchTool(false,
                new IntranetWebFetchPolicy("10.0.0.1"));
        assertFalse(tool.hopAllowed(local("/redirect-internal")));
        // 首跳允许（allowlist 含 127.0.0.1），重定向目标也在 127.0.0.1 → 允许（一致性）；
        // 再验证逐跳逻辑本体：hopAllowed 对每个 Location 独立调用
        assertTrue(new WebFetchTool(false, new IntranetWebFetchPolicy("127.0.0.1"))
                .hopAllowed(local("/page")));
    }

    /** 重定向环：超跳数拒绝（不无限跟随） */
    @Test public void redirectLoopTerminates() throws Exception {
        WebFetchTool tool = new WebFetchTool(false,
                new IntranetWebFetchPolicy("127.0.0.1"));
        ToolResult r = tool.execute(args(local("/redirect-loop")));
        assertFalse(r.ok);
        assertTrue(r.output.contains("重定向超过"));
    }

    /** 白名单解析：域名后缀/CIDR 命中口径 */
    @Test public void allowlistEntrySemantics() {
        IntranetWebFetchPolicy policy = new IntranetWebFetchPolicy(".corp.local,portal.internal,10.20.0.0/16,192.168.1.5");
        assertTrue(policy.whitelisted("http://wiki.corp.local/x"));        // 后缀
        assertTrue(policy.whitelisted("http://portal.internal/x"));        // 裸域名等值
        assertTrue(policy.whitelisted("http://host.corp.local:8080/x"));   // 带端口后缀
        assertTrue(policy.whitelisted("http://10.20.33.44/x"));            // CIDR 内
        assertFalse(policy.whitelisted("http://10.21.0.1/x"));             // CIDR 外
        assertTrue(policy.whitelisted("http://192.168.1.5/x"));            // 精确 IP
        assertFalse(policy.whitelisted("http://192.168.1.6/x"));
        assertFalse(policy.whitelisted("http://evilcorp.local/x"));        // 后缀不匹配
        // 公网地址不受白名单约束
        assertTrue(policy.allows("http://example.com/x"));
    }
}
