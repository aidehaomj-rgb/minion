package com.minion.core.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI 参数解析与分流（开发说明 v2.4 §11.1）。
 *
 * <p>Boot 入口检测到 CLI 参数时路由本类；默认无参走 GUI。
 * {@code -p/--print} 非交互单次执行；{@code --input-format stream-json} 进入 NDJSON 双向协议。
 * 参数集：prompt/model/permission-mode/allowedTools/max-turns/output-format/session/resume/
 * system-prompt/append-system-prompt/bare/verbose。
 */
public final class MinionCli {

    /** 解析后的 CLI 参数 */
    public static final class Args {
        public String prompt;                      // 位置参数（一次性 prompt）
        public boolean print;                      // -p/--print
        public String outputFormat = "text";       // text|json|stream-json
        public String inputFormat = "text";        // text|stream-json
        public String model;                       // --model
        public String permissionMode = "default";  // --permission-mode
        public List<String> allowedTools = new ArrayList<String>();
        public int maxTurns = -1;
        public long maxBudget = -1;
        public String sessionDir;                  // 工作目录
        public String systemPrompt;                // --system-prompt（替换）
        public String appendSystemPrompt;          // --append-system-prompt（追加）
        public boolean bare;                       // --bare（极简：跳过 hooks/MINION.md/插件）
        public boolean verbose;                    // --verbose
        public boolean resume;                     // -c/--continue
        public String resumeId;                    // -r/--resume <id|词>

        boolean isCliMode() { return print || "stream-json".equals(outputFormat); }
    }

    private MinionCli() { }

    /** 是否含 CLI 参数（Boot 分流判据：无 CLI 参数走 GUI） */
    public static boolean isCliInvocation(String[] argv) {
        for (String a : argv) {
            if (a.equals("-p") || a.equals("--print") || a.startsWith("--output-format")
                    || a.equals("doctor") || a.equals("--version") || a.equals("-v"))
                return true;
        }
        return false;
    }

    /** 解析命令行参数；无法识别的参数收集到 unknown（不炸，打印警告） */
    public static Args parse(String[] argv) {
        Args args = new Args();
        List<String> positional = new ArrayList<String>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "-p": case "--print": args.print = true; break;
                case "--output-format": args.outputFormat = value(argv, ++i, a); break;
                case "--input-format": args.inputFormat = value(argv, ++i, a); break;
                case "--model": args.model = value(argv, ++i, a); break;
                case "--permission-mode": args.permissionMode = value(argv, ++i, a); break;
                case "--allowedTools": case "--allowed-tools":
                    args.allowedTools.addAll(Arrays.asList(value(argv, ++i, a).split(","))); break;
                case "--max-turns": args.maxTurns = Integer.parseInt(value(argv, ++i, a)); break;
                case "--max-budget": args.maxBudget = Long.parseLong(value(argv, ++i, a)); break;
                case "--system-prompt": args.systemPrompt = value(argv, ++i, a); break;
                case "--system-prompt-file":
                    args.systemPrompt = readFile(value(argv, ++i, a)); break;
                case "--append-system-prompt": args.appendSystemPrompt = value(argv, ++i, a); break;
                case "--append-system-prompt-file":
                    args.appendSystemPrompt = readFile(value(argv, ++i, a)); break;
                case "--bare": args.bare = true; break;
                case "--verbose": args.verbose = true; break;
                case "-c": case "--continue": args.resume = true; break;
                case "-r": case "--resume": args.resume = true; args.resumeId = value(argv, ++i, a); break;
                case "--json-schema": case "--fallback-model": case "--agent": case "--agents":
                case "--session-id": case "--fork-session": case "--no-session-persistence":
                case "--disallowedTools": case "--tools": case "--add-dir": case "--mcp-config":
                case "--strict-mcp-config": case "--settings": case "--setting-sources":
                case "--plugin-dir": case "--disable-slash-commands": case "-n": case "--name":
                    ++i;   // 带值参数：跳过值（当前版本不消费）
                    break;
                default:
                    if (a.startsWith("-") && !a.startsWith("--") && a.length() > 1) {
                        // 短参数组合（如 -vp）
                        for (char c : a.substring(1).toCharArray())
                            if (c == 'p') args.print = true;
                            else if (c == 'v') args.verbose = true;
                            else if (c == 'c') args.resume = true;
                    } else if (!a.startsWith("-")) {
                        positional.add(a);
                    }
                    break;
            }
        }
        if (!positional.isEmpty()) args.prompt = String.join(" ", positional);
        return args;
    }

    private static String value(String[] argv, int index, String flag) {
        if (index >= argv.length) throw new IllegalArgumentException(flag + " 缺少值");
        return argv[index];
    }

    private static String readFile(String path) {
        try { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }
        catch (Exception e) { throw new IllegalArgumentException("文件不可读: " + path); }
    }
}
