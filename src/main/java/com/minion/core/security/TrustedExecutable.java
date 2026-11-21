package com.minion.core.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一项待信任复核的可执行配置（v2.4 §10.9）：Hook 命令、MCP stdio 启动命令、
 * LSP server 命令、statusLine、apiKeyHelper、Skill 内联 shell、插件声明命令。
 *
 * <p>指纹 = SHA-256(scope、type、id、command、args、env、cwd、引用脚本 realpath+内容哈希)，
 * 任一变化即指纹变化——git pull/外部编辑/插件升级后的新内容必须重新批准。
 * 引用脚本从命令串中按空白分词提取（仅取 root 下真实存在的文件，上限 16 个、单文件 4MiB）。
 */
public final class TrustedExecutable {

    public static final String TYPE_HOOK = "hook";
    public static final String TYPE_MCP = "mcp";
    public static final String TYPE_PLUGIN = "plugin";
    public static final String TYPE_LSP = "lsp";
    public static final String TYPE_STATUSLINE = "statusline";
    public static final String TYPE_SKILL_SHELL = "skillshell";
    public static final String TYPE_APIKEYHELPER = "apikeyhelper";

    /** 引用脚本提取上限（分词数）；超出部分不纳入指纹（命令串本身已整体入指纹） */
    private static final int MAX_SCRIPT_TOKENS = 16;
    /** 单个引用脚本参与哈希的大小上限 */
    private static final long MAX_SCRIPT_BYTES = 4L * 1024 * 1024;

    public final String type;
    public final String id;
    /** 作用域：project / user / local / enterprise（入指纹；作用域变化≠同一信任） */
    public final String scope;
    public final String command;
    public final List<String> args;
    public final Map<String, String> env;
    public final String cwd;
    /** 内容纳入指纹的脚本（realpath + SHA-256；缺失记 MISSING） */
    public final List<Path> referencedScripts;

    private TrustedExecutable(String type, String id, String scope, String command,
            List<String> args, Map<String, String> env, String cwd, List<Path> referencedScripts) {
        this.type = type;
        this.id = id;
        this.scope = scope;
        this.command = command;
        this.args = args == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(args));
        this.env = env == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new TreeMap<String, String>(env));
        this.cwd = cwd == null ? "" : cwd;
        this.referencedScripts = referencedScripts == null ? Collections.<Path>emptyList()
                : Collections.unmodifiableList(new ArrayList<Path>(referencedScripts));
    }

    public static TrustedExecutable of(String type, String id, String scope, String command,
            List<String> args, Map<String, String> env, String cwd, List<Path> referencedScripts) {
        return new TrustedExecutable(type, id, scope, command, args, env, cwd, referencedScripts);
    }

    /** v1 指纹规范：逐字段拼接后 SHA-256（hex）；字段分隔 \n、列表 \u0001、脚本段 realpath\u0002hash */
    public String fingerprint() {
        StringBuilder c = new StringBuilder(256);
        c.append("v1\n").append(n(scope)).append('\n').append(n(type)).append('\n').append(n(id)).append('\n')
                .append(n(command)).append('\n');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) c.append('\u0001');
            c.append(n(args.get(i)));
        }
        c.append('\n');
        boolean first = true;
        for (Map.Entry<String, String> e : env.entrySet()) {   // TreeMap：键序稳定
            if (!first) c.append('\u0001');
            first = false;
            c.append(n(e.getKey())).append('=').append(n(e.getValue()));
        }
        c.append('\n').append(n(cwd)).append('\n');
        List<String> scriptLines = new ArrayList<String>();
        for (Path p : referencedScripts) {
            String real;
            try { real = p.toRealPath().toString(); }
            catch (java.io.IOException missing) { real = p.toAbsolutePath().normalize().toString() + ":GONE"; }
            scriptLines.add(real + '\u0002' + ProjectTrustStore.scriptHash(p));
        }
        java.util.Collections.sort(scriptLines);
        for (String line : scriptLines) c.append(line).append('\n');
        return sha256Hex(c.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 展示摘要（信任对话框 / 变更对比用）：命令 + 参数，截断 200 字符 */
    public String preview() {
        StringBuilder sb = new StringBuilder();
        sb.append(n(command));
        for (String a : args) sb.append(' ').append(a);
        String s = sb.toString();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /** 稳定短 id：event|matcher|command 等长键压成 24 位 hex，避免 trust.json 出现超长键 */
    public static String shortId(String raw) {
        return sha256Hex(raw.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    /**
     * 从命令串（含参数）提取引用脚本：按空白分词、剥引号，取 root 下真实存在的普通文件
     * （≤16 个）。脚本路径变化或内容变化都会改变指纹。
     */
    public static List<Path> referencedScripts(String commandLike, Path root) {
        List<Path> out = new ArrayList<Path>();
        if (commandLike == null || root == null) return out;
        String[] tokens = commandLike.trim().split("\\s+");
        for (int i = 0; i < tokens.length && i < MAX_SCRIPT_TOKENS; i++) {
            String t = tokens[i].replaceAll("^[\"']|[\"']$", "");
            if (t.isEmpty()) continue;
            Path p = resolve(root, t);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static Path resolve(Path root, String token) {
        try {
            Path p = Paths.get(token);
            if (!p.isAbsolute()) p = root.resolve(p);
            p = p.toAbsolutePath().normalize();
            return Files.isRegularFile(p) && Files.size(p) <= MAX_SCRIPT_BYTES ? p : null;
        } catch (Exception e) { return null; }
    }

    private static String n(String s) { return s == null ? "" : s; }

    /** SHA-256 hex（公共工具：HookSystem 校验和、UI 展示、测试） */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder(d.length * 2);
            for (byte b : d) s.append(String.format(Locale.ROOT, "%02x", b & 255));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
