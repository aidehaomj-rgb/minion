package com.minion.core.tools.python;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Win7 兼容 Python 运行时：优先显式配置，再探测 Anaconda 与 PATH。 */
public final class PythonRuntime {
    public static final int MAX_OUTPUT_CHARS = 60_000;
    private final String configuredPath;

    public PythonRuntime(String configuredPath) { this.configuredPath = configuredPath == null ? "" : configuredPath.trim(); }

    public Path resolve() {
        Set<String> candidates = new LinkedHashSet<String>();
        add(candidates, configuredPath);
        add(candidates, System.getenv("MINION_PYTHON"));
        String conda = System.getenv("CONDA_PREFIX");
        if (conda != null) add(candidates, Paths.get(conda, "python.exe").toString());
        String home = System.getProperty("user.home", "");
        add(candidates, Paths.get(home, "Anaconda3", "python.exe").toString());
        add(candidates, Paths.get(home, "Miniconda3", "python.exe").toString());
        add(candidates, "C:\\ProgramData\\Anaconda3\\python.exe");
        add(candidates, "C:\\Anaconda3\\python.exe");
        for (String c : candidates) {
            try {
                Path p = Paths.get(c).toAbsolutePath().normalize();
                if (Files.isRegularFile(p)) return p;
            } catch (Exception ignored) { }
        }
        return findOnPath();
    }

    public RunResult status(Path cwd) throws IOException {
        Path python = require();
        String code = "import sys\nmods=['pandas','numpy','openpyxl','xlrd','matplotlib','sqlite3','PyPDF2','pdfminer','flask','requests','PyInstaller']\n"
                + "print('executable='+sys.executable)\nprint('version='+sys.version.replace('\\n',' '))\n"
                + "results=[]\n"
                + "for m in mods:\n"
                + " try:\n  x=__import__(m); v=getattr(x,'__version__','ok'); results.append(m+':ok('+str(v)+')')\n"
                + " except Exception as e: results.append(m+':error('+type(e).__name__+': '+str(e)+')')\n"
                + "print('modules='+','.join(results))\n"
                + "print('sqlite_dll='+__import__('os').path.join(sys.prefix,'Library','bin','sqlite3.dll'))";
        return run(Arrays.asList(python.toString(), "-X", "utf8", "-c", code), cwd, 30);
    }

    public RunResult runCode(String code, Path cwd, int timeoutSeconds) throws IOException {
        Path tmpDir = cwd.resolve(".minion").resolve("tmp");
        Files.createDirectories(tmpDir);
        Path script = Files.createTempFile(tmpDir, "python-", ".py");
        try {
            Files.write(script, code.getBytes(StandardCharsets.UTF_8));
            return run(Arrays.asList(require().toString(), "-X", "utf8", "-u", script.toString()), cwd, timeoutSeconds);
        } finally { Files.deleteIfExists(script); }
    }

    public RunResult runFile(Path script, List<String> arguments, Path cwd, int timeoutSeconds) throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(require().toString()); cmd.add("-X"); cmd.add("utf8"); cmd.add("-u"); cmd.add(script.toString());
        if (arguments != null) cmd.addAll(arguments);
        return run(cmd, cwd, timeoutSeconds);
    }

    public Path require() throws IOException {
        Path p = resolve();
        if (p == null) throw new IOException("未找到 Python。请在设置中配置 python.path（例如 C:\\ProgramData\\Anaconda3\\python.exe）");
        return p;
    }

    private static RunResult run(List<String> command, Path cwd, int timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        configurePythonEnvironment(pb.environment(), command.isEmpty() ? null : Paths.get(command.get(0)));
        Process process = pb.start();
        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (out) {
                        if (out.length() < MAX_OUTPUT_CHARS) out.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) { }
        });
        reader.setDaemon(true); reader.start();
        boolean finished;
        try { finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); finished = false; }
        if (!finished) {
            killTree(process);
            return new RunResult(-1, text(out) + "\n[Python 执行超时，已终止]", true);
        }
        try { reader.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new RunResult(process.exitValue(), text(out), false);
    }

    private static String text(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString();
            return s.length() >= MAX_OUTPUT_CHARS ? s + "\n[输出已截断]" : s;
        }
    }

    private static void killTree(Process process) {
        try {
            long pid = pidOf(process);
            if (pid > 0 && System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F").start().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) { }
        process.destroy();
        try { process.destroyForcibly(); } catch (Throwable ignored) { }
    }

    /** Java 8 无 Process.pid()；Windows JDK8 ProcessImpl 保存 long handle，取不到时仍可 destroy。 */
    private static long pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            long handle = f.getLong(p);
            Class<?> kernel = Class.forName("java.lang.ProcessImpl");
            java.lang.reflect.Method m = kernel.getDeclaredMethod("getProcessId", long.class);
            m.setAccessible(true);
            return ((Number) m.invoke(null, handle)).longValue();
        } catch (Exception ignored) { return -1; }
    }

    private static Path findOnPath() {
        for (String name : new String[]{"python.exe", "python3.exe"}) {
            try {
                Process p = new ProcessBuilder("where", name).redirectErrorStream(true).start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = br.readLine();
                    if (line != null && Files.isRegularFile(Paths.get(line.trim()))) return Paths.get(line.trim()).toAbsolutePath();
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static void add(Set<String> set, String value) { if (value != null && !value.trim().isEmpty()) set.add(value.trim()); }

    /**
     * Conda 在 Windows 7/Python 3.7 下依靠 PATH 查找 sqlite3.dll、OpenSSL 等本机 DLL。
     * 从 IDE/JAR 启动时通常没有执行 activate.bat，因此在每个 Python 子进程中显式补齐。
     */
    static void configurePythonEnvironment(Map<String, String> env, Path executable) {
        if (env == null || executable == null) return;
        Path prefix = executable.toAbsolutePath().normalize().getParent();
        if (prefix == null) return;
        List<Path> candidates = Arrays.asList(prefix, prefix.resolve("DLLs"),
                prefix.resolve("Library").resolve("bin"), prefix.resolve("Scripts"),
                prefix.resolve("Library").resolve("usr").resolve("bin"));
        StringBuilder path = new StringBuilder();
        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) continue;
            if (path.length() > 0) path.append(File.pathSeparator);
            path.append(candidate);
        }
        String old = env.get("PATH");
        if (old == null) old = env.get("Path");
        if (old != null && !old.isEmpty()) {
            if (path.length() > 0) path.append(File.pathSeparator);
            path.append(old);
        }
        if (path.length() > 0) env.put("PATH", path.toString());
        if (Files.isDirectory(prefix.resolve("conda-meta")) && !env.containsKey("CONDA_PREFIX")) {
            env.put("CONDA_PREFIX", prefix.toString());
        }
    }

    public static final class RunResult {
        public final int exitCode; public final String output; public final boolean timedOut;
        public RunResult(int exitCode, String output, boolean timedOut) { this.exitCode = exitCode; this.output = output; this.timedOut = timedOut; }
        public boolean ok() { return exitCode == 0 && !timedOut; }
    }
}
