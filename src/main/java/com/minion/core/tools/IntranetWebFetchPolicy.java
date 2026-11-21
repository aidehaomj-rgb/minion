package com.minion.core.tools;

import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 内网 WebFetch 白名单（开发说明 v2.4 §3.9，tools.webfetch.intranet.enabled 默认 false）。
 *
 * <p>白名单条目（tools.webfetch.intranet.allowlist，逗号分隔）：域名后缀（.corp.local
 * 或 portal.internal）、单 IP、CIDR（10.20.0.0/16）。公网地址不受白名单约束（既有
 * SSRF 规则继续放行公网）；内网/回环地址必须命中白名单条目才放行。
 * <b>每跳重定向同样校验</b>（30x Location 逐跳过检，防公网页面重定向到内网绕过）。
 */
public final class IntranetWebFetchPolicy {

    private final List<String> domainSuffixes = new ArrayList<String>();
    private final List<long[]> cidrs = new ArrayList<long[]>();       // [network, mask]（IPv4 位串）
    private final List<String> exactIps = new ArrayList<String>();

    public IntranetWebFetchPolicy(String allowlistCsv) {
        if (allowlistCsv == null) return;
        for (String raw : allowlistCsv.split("[,，]")) {
            String entry = raw.trim().toLowerCase();
            if (entry.isEmpty()) continue;
            if (entry.contains("/")) parseCidr(entry);
            else if (entry.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) exactIps.add(entry);
            else domainSuffixes.add(entry.startsWith(".") ? entry : "." + entry);
        }
    }

    private void parseCidr(String cidr) {
        try {
            String[] parts = cidr.split("/");
            long ip = ipv4ToLong(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) return;
            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            cidrs.add(new long[]{ip & mask, mask});
        } catch (Exception ignored) { }
    }

    private static long ipv4ToLong(String ip) throws Exception {
        String[] o = ip.split("\\.");
        if (o.length != 4) throw new IllegalArgumentException("not ipv4");
        return (Long.parseLong(o[0]) << 24) | (Long.parseLong(o[1]) << 16)
                | (Long.parseLong(o[2]) << 8) | Long.parseLong(o[3]);
    }

    public boolean isEmpty() {
        return domainSuffixes.isEmpty() && cidrs.isEmpty() && exactIps.isEmpty();
    }

    /** 该 URL 是否允许抓取（公网按既有 SSRF 规则；内网须命中白名单） */
    public boolean allows(String url) {
        if (WebFetchTool.isPrivateHost(url)) return whitelisted(url);
        return true;
    }

    /** 白名单命中：域名后缀 / 精确 IP / CIDR 包含 */
    boolean whitelisted(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        if (exactIps.contains(host)) return true;
        for (String suffix : domainSuffixes)
            if (host.endsWith(suffix) || host.equals(suffix.substring(1))) return true;
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.getAddress().length == 4) {
                long ip = ipv4ToLong(addr.getHostAddress());
                for (long[] cidr : cidrs)
                    if ((ip & cidr[1]) == cidr[0]) return true;
            }
        } catch (Exception unresolved) { }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String host = new URL(url).getHost();
            if (host == null || host.isEmpty()) return null;
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            return host.toLowerCase();
        } catch (Exception invalid) {
            return null;
        }
    }
}
