package com.minion.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置：classpath 默认值 + 外部覆盖（jar 同目录 config.properties，首启自动生成） */
public class Config {

    public static final String EXTERNAL_FILE_NAME = "config.properties";
    private static final String DEFAULT_RESOURCE = "/config.properties";

    private final Map<String, String> props = new HashMap<String, String>();
    /** classpath 默认值快照（不含外部文件覆盖）：FeatureFlags 来源追溯用（§13.1） */
    private final Map<String, String> resourceDefaults = new HashMap<String, String>();
    private final Path externalFile;

    private Config(Path externalFile) { this.externalFile = externalFile; }

    public static Config load() { return load(jarDir()); }

    /** @param overrideDir 外部配置文件所在目录（测试注入） */
    public static Config load(Path overrideDir) {
        return load(overrideDir, DEFAULT_RESOURCE);
    }

    /** @param defaultResource 默认值资源路径（测试注入纯净默认值，避免读到本机真实配置） */
    static Config load(Path overrideDir, String defaultResource) {
        Config c = new Config(overrideDir.resolve(EXTERNAL_FILE_NAME));
        loadResource(c.props, defaultResource);
        loadResource(c.resourceDefaults, defaultResource);
        if (Files.exists(c.externalFile)) {
            loadFile(c.props, c.externalFile);
        } else {
            try {
                Files.createDirectories(overrideDir);
                String defaults = new String(
                        readResource(defaultResource), StandardCharsets.UTF_8);
                Files.write(c.externalFile, defaults.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("[minion] 无法生成外部配置文件: " + e.getMessage());
            }
        }
        return c;
    }

    /** jar 所在目录（workspace.json/model.json/会话目录的基准） */
    public static Path jarDir() {
        try {
            Path p = Paths.get(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return p.getParent() != null ? p.getParent() : Paths.get(".");
        } catch (Exception e) { return Paths.get("."); }
    }

    private static byte[] readResource(String name) throws IOException {
        java.io.InputStream in = Config.class.getResourceAsStream(name);
        if (in == null) throw new IOException("missing resource " + name);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void loadResource(Map<String, String> m, String resource) {
        try { loadLines(m, new String(readResource(resource), StandardCharsets.UTF_8).split("\\r?\\n")); }
        catch (IOException e) { throw new IllegalStateException("默认配置缺失", e); }
    }

    private static void loadFile(Map<String, String> m, Path f) {
        try { loadLines(m, Files.readAllLines(f, StandardCharsets.UTF_8).toArray(new String[0])); }
        catch (IOException e) { System.err.println("[minion] 读取外部配置失败: " + e.getMessage()); }
    }

    private static void loadLines(Map<String, String> m, String[] lines) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf('=');
            if (i <= 0) continue;
            m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
    }

    public String get(String key, String def) {
        String v = props.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    /** classpath 默认值（无外部覆盖时）；FeatureFlags 判断「显式配置 vs 默认」用（§13.1） */
    public String defaultOf(String key) { return resourceDefaults.get(key); }

    public String skillsDir()    { return get("skills.dir", "./skills"); }
    /** 所有工作空间共用的只读 Markdown 知识库目录；留空则只使用项目知识库。 */
    public String sharedKnowledgeDir() { return get("knowledge.shared.dir", ""); }
    /** Python/Anaconda 解释器；留空时自动探测 MINION_PYTHON、Conda 和常见安装目录。 */
    public String pythonPath()   { return get("python.path", ""); }
    public String ocrPath() { return get("ocr.path", "tesseract"); }
    public String ocrLanguage() { return get("ocr.language", "chi_sim+eng"); }
    public String officePath() { return get("office.path", "soffice"); }

    /** 空间外读：true 时 Read/Grep/Glob 可读取工作区外文件（写入类工具不受影响，仍受限） */
    public boolean readAllowOutside() { return Boolean.parseBoolean(get("paths.read.allowOutside", "false")); }
    /** 空间外写：true 时 Write/Edit 越界写放行至高危确认链（会话放行/确认跳过/白名单/弹框）；false（默认）时越界写直接拒绝 */
    public boolean writeAllowOutside() { return Boolean.parseBoolean(get("paths.write.allowOutside", "false")); }
    /** 工具空输出占位：true 时成功空输出发送「输出内容为空」占位（服务端校验通过+模型可识别）；默认 false */
    public boolean emptyOutputPlaceholder() { return Boolean.parseBoolean(get("agent.emptyOutput.placeholder", "false")); }
    public boolean confirmSkip() { return Boolean.parseBoolean(get("confirm.skip", "false")); }
    /**
     * MCP 工具确认缺口应急回退（默认 false，开发说明 v2.4 §3.7 P0）：
     * 默认时 MCP 工具按高危确认（整 server 白名单键 {@code mcp:<server>} 或单工具名放行）；
     * true 时回退 V68 旧行为（MCP 永不确认），仅限应急排障，不作为长期配置。
     */
    public boolean mcpConfirmSkip() { return Boolean.parseBoolean(get("mcp.confirm.skip", "false")); }

    /**
     * 权限引擎 v2（默认 true，开发说明 v2.4 §4.1，属 0.3 明示安全修复）：
     * 13 步统一裁决管线（deny 前置、内容级 deny 免疫 bypass、模式映射）。
     * false 仅用于 legacy 对照排障。
     */
    /** Bash/PowerShell/CMD 分析器（默认 true，v2.4 §4.4 明示安全修复）；false 回退 DangerousCommands 黑名单 */
    public boolean bashAnalyzerEnabled() { return Boolean.parseBoolean(get("bash.analyzer.enabled", "true")); }

    /** v2.4 §9.1：transcript JSONL 双写层（默认 false=legacy 单写；replica-offline 开启双写） */
    public boolean transcriptJsonl() { return Boolean.parseBoolean(get("session.transcript.jsonl", "false")); }

    /**
     * v2.4 §3.1/§4.5 Read/Edit/Write 的 CC 语义：Read 去重 stub、read-before-write、
     * 整文件哈希冲突校验。默认 false 完整保留 V68（14.3 开关表）。读状态登记本身
     * 无条件（纯簿记不影响行为），仅消费侧受此开关控制。
     */
    public boolean readCcSemantics() { return Boolean.parseBoolean(get("read.ccSemantics", "false")); }

    /**
     * v2.4 §4.6 SecretStore 加固（默认 true，0.3 明示安全修复例外）：随机主密钥 + DPAPI/PBKDF2
     * + AES/GCM + 旧 CBC 数据迁移。false 完整回退旧实现（可预测密钥 CBC，仅应急排障）。
     */
    public boolean secretStoreV2() { return Boolean.parseBoolean(get("secret.store.v2", "true")); }

    /**
     * v2.4 §4.8 SSH TOFU（默认 true，0.3 明示安全修复例外）：首次连接未知主机警告并询问
     * （确认后记录 known_hosts），指纹变化按中间人拒绝。false 回退旧 StrictHostKeyChecking=no。
     */
    public boolean sshStrictHostKey() { return Boolean.parseBoolean(get("ssh.strictHostKey", "true")); }

    /**
     * v2.4 §4.7 CC 语义 Hook 系统（hooks.enabled 默认 false=legacy 不启用；replica-offline 开启）。
     * 启用后仍需会话内批准 cc-hooks.json 快照；hooks.disableAll 为应急总闸（true 时强制关）。
     */
    public boolean hooksEnabled() { return Boolean.parseBoolean(get("hooks.enabled", "false")); }

    /** §4.7 应急总闸：true 时 Hook 系统整体关闭（优先于 hooks.enabled） */
    public boolean hooksDisableAll() { return Boolean.parseBoolean(get("hooks.disableAll", "false")); }

    /** §4.7 超时/失败行为：仅 ask|deny（无 continue 放行分支）；非法值回落 ask */
    public String hooksTimeoutBehavior() {
        String v = get("hooks.timeoutBehavior", "ask").toLowerCase(java.util.Locale.ROOT);
        return "deny".equals(v) ? "deny" : "ask";
    }

    /** v2.4 §1.4 max_tokens 截断两级恢复总开关（默认 true；现状已有 hint 续写，此为增强不收紧） */
    public boolean llmAutoContinueEnabled() { return Boolean.parseBoolean(get("llm.autoContinue.enabled", "true")); }

    /** §1.4 升档目标（默认 64000，受模型 maxOutputTokens 上限约束） */
    public int llmEscalatedMaxTokens() { return (int) longOf("llm.escalatedMaxTokens", 64000); }

    /** §1.4 续写次数上限（默认 12=V68 现状；cc 语义可配 3） */
    public int llmMaxContinueRecovery() { return (int) longOf("llm.maxContinueRecovery", 12); }

    /** v2.4 §1.5 token 预算（默认 true；无预算输入时零影响） */
    public boolean budgetEnabled() { return Boolean.parseBoolean(get("budget.enabled", "true")); }

    /**
     * v2.4 §2.2 元数据驱动并行（tools.parallel.metadataDriven 默认 false=沿用按名判定；
     * replica-offline 开启）。批大小上限 tools.maxConcurrency 仅新调度器生效（replica 默认 2）。
     */
    public boolean toolsParallelMetadataDriven() { return Boolean.parseBoolean(get("tools.parallel.metadataDriven", "false")); }

    private long longOf(String key, long fallback) {
        try { return Long.parseLong(get(key, String.valueOf(fallback)).trim()); }
        catch (Exception e) { return fallback; }
    }

    public boolean permissionEngineV2() { return Boolean.parseBoolean(get("permission.engine.v2", "true")); }
    /** read-only / workspace-write / full；未知值回落 workspace-write。 */
    public String permissionMode() { String mode=get("permission.mode","workspace-write").toLowerCase(); return mode.matches("read-only|workspace-write|full")?mode:"workspace-write"; }
    public String contextOutputMode() { return get("context.outputMode", "balanced"); }
    public String contextReadMode() { return get("context.readMode", "chunked"); }
    /** 输入框发送键：true=Enter 发送（Ctrl+Enter 换行）；false=Ctrl+Enter 发送（Enter 换行）；无键（空/旧配置）默认 true */
    public boolean enterSends() { return Boolean.parseBoolean(get("input.enterSends", "true")); }
    /** 工作台主题 id；未知值由 Theme 统一回落深夜黑。 */
    public String uiTheme() { return get("ui.theme", "obsidian"); }
    public Set<String> whitelistTools()    { return csv(get("confirm.whitelist.tools", "")); }
    public Set<String> whitelistCommands() { return csv(get("confirm.whitelist.commands", "")); }
    private static Set<String> csv(String s) {
        Set<String> set = new HashSet<String>();
        for (String part : s.split(",")) {
            if (!part.trim().isEmpty()) set.add(part.trim().toLowerCase());
        }
        return set;
    }

    public Path externalFile() { return externalFile; }

    /** Host-owned budgets, never adjustable by a model tool call. */
    public int taskContinuationWindows() { return positiveBudget("agent.task.continuationWindows", 96, 10000); }
    private int positiveBudget(String key, int fallback, int maximum) {
        try { int n = Integer.parseInt(get(key, String.valueOf(fallback))); return n > 0 && n <= maximum ? n : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }

    /**
     * 运行时写回配置：更新内存 + 重写外部 config.properties（保留注释行，替换/追加 key 行）。
     * 实时生效核对：confirmSkip/whitelist/readAllowOutside/writeAllowOutside 每次使用即读 Config → 立即生效；
     * skills.dir 由新会话 buildCtx 读取 → 新会话生效。
     */
    public void set(String key, String value) {
        props.put(key, value);
        try {
            List<String> lines = Files.exists(externalFile)
                    ? Files.readAllLines(externalFile, StandardCharsets.UTF_8)
                    : new java.util.ArrayList<String>();
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            for (String line : lines) {
                if (line.trim().startsWith(key + "=")) {
                    sb.append(key).append('=').append(value).append('\n');
                    replaced = true;
                } else {
                    sb.append(line).append('\n');
                }
            }
            if (!replaced) sb.append(key).append('=').append(value).append('\n');
            Files.write(externalFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[minion] 写入配置失败: " + e.getMessage());
        }
    }

    /** 追加白名单（去重）。section 形如 confirm.whitelist.tools */
    public void appendWhitelist(String section, String value) {
        String v = value.trim().toLowerCase();
        if (v.isEmpty()) return;
        try {
            String existing = props.containsKey(section) ? props.get(section) : "";
            Set<String> set = csv(existing);
            if (set.contains(v)) return;
            List<String> lines = Files.exists(externalFile)
                    ? Files.readAllLines(externalFile, StandardCharsets.UTF_8)
                    : new java.util.ArrayList<String>();
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(v).append(" added by minion ").append(new java.util.Date()).append('\n');
            boolean replaced = false;
            for (String line : lines) {
                if (line.trim().startsWith(section + "=")) {
                    String sep = line.trim().endsWith("=") || set.isEmpty() ? "" : ",";
                    sb.append(section).append('=').append(existing)
                      .append(sep).append(v).append('\n');
                    replaced = true;
                } else {
                    sb.append(line).append('\n');
                }
            }
            if (!replaced) {
                sb.append(section).append('=').append(existing)
                  .append(existing.isEmpty() ? "" : ",").append(v).append('\n');
            }
            Files.write(externalFile, sb.toString().getBytes(StandardCharsets.UTF_8));
            props.put(section, set.isEmpty() ? v : existing + "," + v);
        } catch (IOException e) {
            System.err.println("[minion] 写入白名单失败: " + e.getMessage());
        }
    }
}
