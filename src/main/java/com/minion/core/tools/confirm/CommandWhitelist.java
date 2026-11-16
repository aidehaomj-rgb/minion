package com.minion.core.tools.confirm;

import com.minion.core.tools.DangerousCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bash 命令白名单匹配。
 *  单词条目 = 旧语义（首 token 精确匹配，链式命令不收紧，兼容既有配置）；
 *  多词条目 = 前缀规则（前 N 个 token 归一化后逐一精确相等，且命令必须是
 *  不含 shell 元字符的单一简单命令——堵 `del /q x && rm -rf y` 借前缀绕过的洞）。
 *  只有高危命令才会走到白名单检查（BashTool.isHighRisk = DangerousCommands.isDangerous），
 *  前缀规则的价值是窄化危险命令：`del /q` 比单词 `del` 窄。 */
public final class CommandWhitelist {

    private CommandWhitelist() { }

    public static boolean matches(String command, Set<String> rules) {
        if (command == null || command.trim().isEmpty() || rules == null || rules.isEmpty()) return false;
        String first = DangerousCommands.firstToken(command);
        List<String> tokens = null;
        boolean metaChecked = false, hasMeta = false;
        for (String rule : rules) {
            if (rule == null || rule.trim().isEmpty()) continue;
            List<String> parts = tokenize(rule);
            if (parts.isEmpty()) continue;
            if (parts.size() == 1) {
                if (first.equals(normalize(parts.get(0), true))) return true;
                continue;
            }
            if (!metaChecked) { hasMeta = hasShellMeta(command); metaChecked = true; }
            if (hasMeta) continue;
            if (tokens == null) tokens = tokenize(command);
            if (tokens.size() < parts.size()) continue;
            boolean hit = true;
            for (int i = 0; i < parts.size(); i++) {
                if (!normalize(tokens.get(i), i == 0).equals(normalize(parts.get(i), i == 0))) {
                    hit = false;
                    break;
                }
            }
            if (hit) return true;
        }
        return false;
    }

    /** 首 token 复用 firstToken 归一化（剥引号/basename/.exe/小写）；
     *  其余 token 仅剥引号+小写——不做 basename，避免规则 `del /q *.tmp` 意外匹配 `output\*.tmp`。 */
    private static String normalize(String token, boolean isFirst) {
        if (isFirst) return DangerousCommands.firstToken(token);
        return stripQuotes(token).toLowerCase(Locale.ROOT);
    }

    private static String stripQuotes(String token) {
        if (token.length() >= 2
                && (token.charAt(0) == '"' || token.charAt(0) == '\'')
                && token.charAt(token.length() - 1) == token.charAt(0)) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    /** 引号感知的空白分词：引号内的空白保留为 token 的一部分（支持带空格的路径参数）。 */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0; else cur.append(c);
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 引号外的 shell 元字符 = 链式/重定向/子shell，前缀规则一律不命中。
     *  引号内按字面量处理（cmd 语义）；未闭合引号视为可疑，同样拦截。 */
    static boolean hasShellMeta(String command) {
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (";&|<>`()".indexOf(c) >= 0 || c == '\n' || c == '\r') return true;
        }
        return quote != 0;
    }
}