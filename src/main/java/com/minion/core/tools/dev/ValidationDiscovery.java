package com.minion.core.tools.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.python.PythonRuntime;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 只在用户显式执行 Build 时解析命令；detect 不运行解释器或项目脚本。 */
final class ValidationDiscovery {
    static final class Plan {
        final List<String> command = new ArrayList<String>();
        final List<Path> reports = new ArrayList<Path>();
        String runner = "", problem = "", problemStatus = "not_configured";
        Charset encoding = Charset.defaultCharset();
        Path unittestResult;
    }

    private ValidationDiscovery() { }

    /** 仅清本项目有对应.py源码的__pycache__，避免3.7秒级mtime缓存让新代码测试旧字节码。 */
    static int freshPythonBytecode(final Path root) throws IOException {
        final int[] count={0},visited={0};
        Files.walkFileTree(root,new java.nio.file.SimpleFileVisitor<Path>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes a)throws IOException {
                if(!dir.equals(root)){String name=dir.getFileName().toString();
                    if((SourceVersion.excluded(name)&&!"__pycache__".equals(name))||Files.isSymbolicLink(dir))return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    requireInside(root,dir);
                }return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes a)throws IOException {
                if(++visited[0]>20000)throw new IOException("Python缓存检查超过20000项，请收窄项目范围");
                String name=file.getFileName().toString();int tag=name.lastIndexOf(".cpython-");
                if("__pycache__".equals(file.getParent().getFileName().toString())&&name.endsWith(".pyc")&&tag>0&&!Files.isSymbolicLink(file)) {
                    Path source=file.getParent().getParent().resolve(name.substring(0,tag)+".py");
                    if(Files.isRegularFile(source)&&!Files.isSymbolicLink(source)){requireInside(root,file);requireInside(root,source);if(Files.deleteIfExists(file))count[0]++;}
                }return java.nio.file.FileVisitResult.CONTINUE;
            }
        });return count[0];
    }

    static String detect(Path root) throws IOException {
        if (Files.isRegularFile(root.resolve("pom.xml"))) return "maven";
        if (Files.isRegularFile(root.resolve("app/src/main/AndroidManifest.xml"))) return "android";
        for (String name : Arrays.asList("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"))
            if (Files.isRegularFile(root.resolve(name))) return "gradle";
        if (Files.isRegularFile(root.resolve("package.json"))) return "npm";
        if (Files.exists(root.resolve("build.cmd")) && Files.exists(root.resolve("main.py"))) return "pyinstaller";
        for (String name : Arrays.asList("pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "main.py", "app.py", "pytest.ini", "tox.ini"))
            if (Files.isRegularFile(root.resolve(name))) return "python";
        if (hasPython(root)) return "python";
        if (Files.isRegularFile(root.resolve("index.html"))) return "web";
        return "unknown";
    }

    static Plan plan(Path root, Path run, String kind, String customCommand, PythonRuntime python) throws IOException {
        Plan p = new Plan();
        if ("command".equals(kind)) {
            if (customCommand.trim().isEmpty()) p.problem = "command 缺少命令";
            else { p.command.addAll(shell(customCommand)); p.runner = "shell"; }
            return p;
        }
        Path config = root.resolve(".minion/validation.json");
        if (Files.exists(config)) {
            requireInside(root, config);
            JsonObject all = json(config);
            if (!all.has("version") || all.get("version").getAsInt() != 1) throw new IOException("validation.json 需要 version=1");
            if (all.has(kind)) {
                JsonObject entry = all.getAsJsonObject(kind);
                JsonElement cmd = entry.get("command");
                if (cmd == null) throw new IOException("validation.json " + kind + " 缺少 command");
                if (cmd.isJsonArray()) {
                    for (JsonElement arg : cmd.getAsJsonArray()) p.command.add(arg.getAsString());
                    p.command.replaceAll(s -> "${python}".equals(s) ? requirePython(python) : s);
                    p.command.replaceAll(s -> s.replace("${runDir}", run.toString()));
                } else p.command.addAll(shell(cmd.getAsString().replace("${runDir}", run.toString())));
                if (p.command.isEmpty() || p.command.get(0).trim().isEmpty()) throw new IOException("配置命令不能为空");
                if (entry.has("reports")) for (JsonElement report : entry.getAsJsonArray("reports")) {
                    Path path = root.resolve(report.getAsString().replace("${runDir}", run.toString())).normalize();
                    requireInside(root, path);
                    p.reports.add(path);
                }
                if (entry.has("encoding")) p.encoding = Charset.forName(entry.get("encoding").getAsString());
                p.runner = "project_config";
                return p;
            }
        }
        String project = detect(root);
        if ("syntax_check".equals(kind)) {
            if (!hasPython(root)) { p.problem = "未发现 Python 源文件；没有可执行的 syntax_check"; return p; }
            pythonPlan(p, root, run, python, true);
        } else if ("maven".equals(project)) {
            Path wrapper = root.resolve(windows() ? "mvnw.cmd" : "mvnw");
            p.command.add(Files.isRegularFile(wrapper) ? wrapper.toString() : windows() ? "mvn.cmd" : "mvn");
            p.command.add("-o");
            if ("test".equals(kind)) {
                p.command.add("test");
                p.command.add("-Dsurefire.reportsDirectory=" + run.resolve("junit"));
                p.reports.add(run.resolve("junit"));
                // 项目可能硬编码插件报告路径；只采纳本次变化的报告。
                p.reports.addAll(nativeReportDirectories(root, "maven"));
            } else { p.command.add("-DskipTests"); p.command.add("package"); }
            p.runner = "maven";
        } else if ("gradle".equals(project) || "android".equals(project)) {
            Path wrapper = root.resolve(windows() ? "gradlew.bat" : "gradlew");
            if (!Files.isRegularFile(wrapper)) {
                p.problemStatus = "blocked";
                p.problem = "缺少项目 wrapper: " + wrapper + "；不自动调用不匹配的全局 Gradle。需要离线发行包/依赖";
                return p;
            }
            p.command.add(wrapper.toString());
            p.command.add("--offline");
            p.command.add("--no-daemon");
            p.command.add("--rerun-tasks");
            p.command.add("--no-build-cache");
            p.command.add("android".equals(project) ? ("test".equals(kind) ? ":app:testDebugUnitTest" : ":app:assembleDebug")
                    : "test".equals(kind) ? "test" : "build");
            if ("test".equals(kind)) p.reports.addAll(nativeReportDirectories(root, "gradle"));
            p.runner = "gradle";
        } else if ("npm".equals(project)) {
            JsonObject pkg = json(root.resolve("package.json"));
            JsonObject scripts = pkg.has("scripts") && pkg.get("scripts").isJsonObject() ? pkg.getAsJsonObject("scripts") : new JsonObject();
            if (!scripts.has(kind) || scripts.get(kind).isJsonNull() || scripts.get(kind).getAsString().trim().isEmpty()) {
                p.problem = "package.json 没有实际 scripts." + kind + "；不猜测命令、不安装依赖";
                return p;
            }
            p.command.add(windows() ? "npm.cmd" : "npm");
            p.command.add("--offline"); p.command.add("run"); p.command.add(kind);
            p.runner = "npm_script";
            p.encoding = StandardCharsets.UTF_8;
        } else if ("python".equals(project) || "pyinstaller".equals(project)) {
            if ("test".equals(kind)) {
                if (!hasTestHint(root)) { p.problem = "未发现测试文件/测试配置；not_configured。compileall 只能使用 syntax_check"; return p; }
                pythonPlan(p, root, run, python, false);
            } else if ("pyinstaller".equals(project)) {
                p.command.add(python.require().toString());
                p.command.addAll(Arrays.asList("-m", "PyInstaller", "--noconfirm", "--onefile", "main.py"));
                p.runner = "pyinstaller";
                p.encoding = StandardCharsets.UTF_8;
            } else p.problem = "Python 未配置构建；请配置 .minion/validation.json，语法检查用 syntax_check";
        } else p.problem = "该项目没有 " + kind + " 验证配置；请配置 .minion/validation.json 或使用 command";
        return p;
    }

    private static void pythonPlan(Plan p, Path root, Path run, PythonRuntime python, boolean syntax) throws IOException {
        Path script = run.resolve("python-validation.py");
        p.unittestResult = run.resolve("unittest.json");
        Path junit = run.resolve("pytest.xml");
        p.reports.add(junit);
        Files.write(script, pythonScript(syntax).getBytes(StandardCharsets.UTF_8));
        p.command.addAll(Arrays.asList(python.require().toString(), "-X", "utf8", "-u", script.toString(),
                junit.toString(), p.unittestResult.toString(), run.getFileName().toString()));
        p.runner = syntax ? "compileall" : "python_auto";
        p.encoding = StandardCharsets.UTF_8;
    }

    // 仅标准库脚本；pytest 只能使用已安装模块，不执行 pip。Python 3.7 可运行。
    static String pythonScript(boolean syntax) {
        String common = "import os, sys, json, importlib.util\n"
                + "sys.path.insert(0, os.getcwd())\n"
                + "excluded=set(" + new com.google.gson.Gson().toJson(SourceVersion.excludedDirectories()) + ")\n";
        if (syntax) return common + "import compileall\n"
                + "ok=True\ncount=0\n"
                + "for base, dirs, files in os.walk('.'):\n"
                + " dirs[:]=[d for d in dirs if d not in excluded and not os.path.islink(os.path.join(base,d))]\n"
                + " for name in files:\n"
                + "  if name.endswith('.py'):\n"
                + "   count+=1\n   ok=compileall.compile_file(os.path.join(base,name),force=True,quiet=0) and ok\n"
                + "print('syntax_files='+str(count))\nsys.exit(0 if ok and count else 1)\n";
        return common + "if importlib.util.find_spec('pytest') is not None:\n"
                + " import pytest\n"
                + " args=['--junitxml='+sys.argv[1], '-p', 'no:cacheprovider']\n"
                + " for name in sorted(excluded):\n  args.extend(['--ignore='+name, '--ignore-glob=*/'+name+'/*'])\n"
                + " sys.exit(pytest.main(args))\n"
                + "else:\n import unittest\n"
                // 3.7还会传namespace参数；透传可选参数兼容不同stdlib，不能假设3.11签名。
                + " class SourceLoader(unittest.TestLoader):\n"
                + "  def _find_test_path(self,full_path,pattern,*args,**kwargs):\n"
                + "   if os.path.isdir(full_path) and os.path.basename(full_path) in excluded:\n    return None,False\n"
                + "   return super()._find_test_path(full_path,pattern,*args,**kwargs)\n"
                + " loader=SourceLoader()\n suite=loader.discover('.')\n"
                + " if suite.countTestCases()==0 and os.path.isdir('tests'):\n  suite=SourceLoader().discover('tests')\n"
                + " result=unittest.TextTestRunner(stream=sys.stdout,verbosity=2).run(suite)\n"
                + " data={'runId':sys.argv[3],'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)}\n"
                + " with open(sys.argv[2],'w',encoding='utf-8') as f:\n  json.dump(data,f)\n"
                + " sys.exit(0 if result.wasSuccessful() else 1)\n";
    }

    static List<Path> nativeReportDirectories(Path root, String project) throws IOException {
        List<Path> dirs = new ArrayList<Path>();
        // 包括尚未创建的根报告目录；模块报告目录在执行后再次发现。
        if ("maven".equals(project)) { dirs.add(root.resolve("target/surefire-reports")); dirs.add(root.resolve("target/failsafe-reports")); }
        else dirs.add(root.resolve("build/test-results"));
        // 不扫描 target 中的历史包/整个 node_modules：只在源目录的输出根登记报告位置。
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (!path.equals(root)) {
                    String name = path.getFileName().toString();
                    if ("maven".equals(project) && "target".equals(name)) {
                        requireInside(root, path);
                        dirs.add(path.resolve("surefire-reports")); dirs.add(path.resolve("failsafe-reports"));
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    if ("gradle".equals(project) && "build".equals(name)) {
                        requireInside(root, path); dirs.add(path.resolve("test-results"));
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    if (SourceVersion.excluded(name)) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    requireInside(root, path);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return dirs;
    }

    private static boolean hasPython(Path root) throws IOException {
        try (Stream<Path> s = sourceFiles(root)) { return s.anyMatch(path -> path.toString().endsWith(".py")); }
    }
    private static boolean hasTestHint(Path root) throws IOException {
        for (String name : Arrays.asList("pytest.ini", "tox.ini", "tests", "test")) if (Files.exists(root.resolve(name))) return true;
        for (String name : Arrays.asList("pyproject.toml", "setup.cfg")) if (Files.isRegularFile(root.resolve(name))) {
            String config = new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
            if (config.contains("[tool.pytest.ini_options]") || config.contains("[tool:pytest]")) return true;
        }
        try (Stream<Path> s = sourceFiles(root)) {
            return s.anyMatch(path -> { String n = path.getFileName().toString(); return n.endsWith(".py") && (n.startsWith("test") || n.endsWith("_test.py")); });
        }
    }
    private static Stream<Path> sourceFiles(Path root) throws IOException {
        // 不进入依赖/输出树；先构建文件清单，避免 Files.walk 过滤后仍遍历大型 node_modules。
        List<Path> files = new ArrayList<Path>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                return !d.equals(root) && SourceVersion.excluded(d.getFileName().toString())
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                if (a.isRegularFile()) files.add(f);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return files.stream();
    }

    static List<String> shell(String command) { return windows() ? Arrays.asList("cmd.exe", "/d", "/s", "/c", com.minion.core.tools.WindowsShell.frame(command)) : Arrays.asList("sh", "-lc", command); }
    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"); }
    static void requireInside(Path root, Path path) throws IOException {
        if (!PathsGuard.inside(root.toString(), path) || Files.isSymbolicLink(path)) throw new IOException("验证路径越界/符号链接: " + path);
    }
    static JsonObject json(Path path) throws IOException {
        if (Files.size(path) > 1024 * 1024) throw new IOException("配置/计数 JSON 过大: " + path);
        try { return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "")).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("无法解析 JSON: " + path + ": " + e.getMessage(), e); }
    }
    private static String requirePython(PythonRuntime python) {
        try { return python.require().toString(); }
        catch (IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }
}
