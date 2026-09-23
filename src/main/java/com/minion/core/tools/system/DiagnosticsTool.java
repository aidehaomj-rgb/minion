package com.minion.core.tools.system;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.diagnostics.SecretRedactor;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;
import com.minion.core.tools.plugin.BrowserConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 离线环境诊断；不联网、不输出密钥。 */
public final class DiagnosticsTool implements Tool {
    private final Config config;
    private final Workspace workspace;
    private final PythonRuntime python;
    private final Path jarDir;
    private final BrowserConfig browser;

    public DiagnosticsTool(Config config, Workspace workspace, PythonRuntime python, Path jarDir) {
        this(config, workspace, python, jarDir, null);
    }

    public DiagnosticsTool(Config config, Workspace workspace, PythonRuntime python, Path jarDir,
                           BrowserConfig browser) {
        this.config = config;
        this.workspace = workspace;
        this.python = python;
        this.jarDir = jarDir;
        this.browser = browser;
    }

    @Override public String name() { return "Diagnostics"; }
    @Override public String description() { return "检查 Win7 Agent 的 Java、Python/Anaconda、SQLite DLL、PyPDF2、pdfminer、PDFBox、Office 库、Chrome、Git、Node、配置和目录，并可导出脱敏报告"; }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("本机离线诊断", new String[]{"action", "outputPath"}, new String[]{"action"});
    }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = str(args, "action").toLowerCase(Locale.ROOT);
        if (!"run".equals(action) && !"export".equals(action) && !"status".equals(action)) {
            return ToolResult.error("未知 action: " + action + "（支持 run/status/export）");
        }
        String report = report();
        DiagnosticLog.info("diagnostics", report);
        if ("export".equals(action)) {
            String requested = str(args, "outputPath");
            Path out = requested.isEmpty()
                    ? workspace.cwd().resolve("minion-diagnostics-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt")
                    : workspace.cwd().resolve(requested).normalize().toAbsolutePath();
            if (!out.startsWith(workspace.cwd().toAbsolutePath().normalize())) return ToolResult.error("报告只能导出到当前工作区");
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, SecretRedactor.redact(report).getBytes(StandardCharsets.UTF_8));
            return ToolResult.success(report + "\n\n报告已导出: " + out);
        }
        return ToolResult.success(report);
    }

    private String report() {
        StringBuilder sb = new StringBuilder("Minion 离线诊断\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        sb.append("系统: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version")).append(" | ")
                .append(System.getProperty("java.home")).append('\n');
        sb.append("JavaFX: ").append(classAvailable("javafx.application.Application") ? "ok" : "missing").append('\n');
        sb.append("Java PDFBox: ").append(classAvailable("org.apache.pdfbox.pdmodel.PDDocument")
                ? "ok（内置 PDF 解析可用，不依赖 Python）" : "missing").append('\n');
        sb.append("jarDir: ").append(jarDir.toAbsolutePath()).append('\n');
        sb.append("工作区: ").append(workspace.workDir()).append(" | ")
                .append(Files.isDirectory(workspace.cwd()) ? "ok" : "missing").append('\n');
        appendExecutable(sb, "Git", "git", "--version");
        appendExecutable(sb, "Chrome", resolvedBrowser(), "--version");
        appendExecutable(sb, "Node", "node", "--version");
        appendExecutable(sb, "PowerShell", "powershell", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()");
        try {
            PythonRuntime.RunResult r = python.status(workspace.cwd());
            sb.append("Python: ").append(r.ok() ? "ok" : "error").append('\n').append(indent(r.output));
        } catch (Exception e) {
            sb.append("Python: missing/error | ").append(e.getMessage()).append('\n');
        }
        sb.append("浏览器配置: port=").append(browser == null ? 9222 : browser.port)
                .append(" headless=").append(browser != null && browser.headless).append('\n');
        sb.append("读越界: ").append(config.readAllowOutside()).append(" | 跳过高危确认: ")
                .append(config.confirmSkip()).append('\n');
        sb.append("日志: ").append(DiagnosticLog.file() == null ? "未初始化" : DiagnosticLog.file()).append('\n');
        sb.append("模型连通性: 请发送一条测试消息；API错误会进入诊断日志（密钥已脱敏）\n");
        return SecretRedactor.redact(sb.toString()).trim();
    }

    private String resolvedBrowser() {
        String p = browser == null ? "" : browser.path;
        return p == null || p.trim().isEmpty() ? "chrome" : p;
    }

    private static void appendExecutable(StringBuilder sb, String label, String... command) {
        String result = runShort(command);
        sb.append(label).append(": ").append(result == null ? "missing" : result).append('\n');
    }

    private static String runShort(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroy(); return "timeout"; }
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line = br.readLine();
            br.close();
            return line == null || line.trim().isEmpty() ? "exit=" + p.exitValue() : line.trim();
        } catch (Exception e) { return null; }
    }

    private static boolean classAvailable(String name) {
        try { Class.forName(name); return true; } catch (Throwable e) { return false; }
    }
    private static String indent(String text) { return text == null ? "" : "  " + text.trim().replace("\n", "\n  ") + "\n"; }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}
