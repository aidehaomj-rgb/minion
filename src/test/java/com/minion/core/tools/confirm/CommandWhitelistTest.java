package com.minion.core.tools.confirm;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class CommandWhitelistTest {

    private static Set<String> rules(String... entries) {
        return new HashSet<String>(Arrays.asList(entries));
    }

    // ---- 旧单词条目：语义不变 ----

    @Test
    public void legacySingleToken_matches() {
        assertTrue(CommandWhitelist.matches("rm -rf x", rules("rm")));
        assertFalse(CommandWhitelist.matches("del x", rules("rm")));
    }

    @Test
    public void legacySingleToken_chainNotTightened() {
        // 用户确认：旧条目不收紧，链式命令仍按首 token 放行
        assertTrue(CommandWhitelist.matches("rm -rf x && del y", rules("rm")));
    }

    @Test
    public void legacySingleToken_normalizesPathAndExe() {
        assertTrue(CommandWhitelist.matches("C:\\Windows\\System32\\taskkill.exe /f /im a.exe",
                rules("taskkill")));
    }

    // ---- 前缀规则 ----

    @Test
    public void prefix_matchesWithExtraArgs() {
        assertTrue(CommandWhitelist.matches("del /q output\\*.tmp", rules("del /q")));
        assertTrue(CommandWhitelist.matches("del /q", rules("del /q")));
    }

    @Test
    public void prefix_tokenExactNotStringPrefix() {
        assertFalse(CommandWhitelist.matches("del /qq x", rules("del /q")));
        assertFalse(CommandWhitelist.matches("del /s x", rules("del /q")));
        assertFalse(CommandWhitelist.matches("taskkill /f /im chrome.exe",
                rules("taskkill /f /im excel.exe")));
    }

    @Test
    public void prefix_shorterCommandDoesNotMatch() {
        assertFalse(CommandWhitelist.matches("del", rules("del /q")));
    }

    @Test
    public void prefix_caseInsensitive_andExeOnFirstToken() {
        assertTrue(CommandWhitelist.matches("TASKKILL /F /IM EXCEL.EXE",
                rules("taskkill /f /im excel.exe")));
        assertTrue(CommandWhitelist.matches("\"C:\\Windows\\System32\\taskkill.exe\" /F /IM excel.exe",
                rules("taskkill /f /im excel.exe")));
    }

    @Test
    public void prefix_quotedArgWithSpaces() {
        assertTrue(CommandWhitelist.matches("del /q \"my file.tmp\"", rules("del /q \"my file.tmp\"")));
        assertFalse(CommandWhitelist.matches("del /q \"other file.tmp\"", rules("del /q \"my file.tmp\"")));
    }

    @Test
    public void prefix_nonFirstTokenKeepsPathNoBasename() {
        // 非首 token 不做 basename：*.tmp 不等于 output\*.tmp
        assertFalse(CommandWhitelist.matches("del /q output\\*.tmp", rules("del /q *.tmp")));
        assertTrue(CommandWhitelist.matches("del /q output\\*.tmp", rules("del /q output\\*.tmp")));
    }

    // ---- 链式守卫（仅前缀规则）----

    @Test
    public void prefix_chainMetachars_rejected() {
        String[] chains = {
                "del /q x && rm -rf y",
                "del /q x; rm -rf y",
                "del /q x | findstr y",
                "del /q x > out.txt",
                "del /q x < in.txt",
                "del /q `whoami`",
                "del /q $(whoami)",
                "del /q x\nrm -rf y",
                "del /q x & rm -rf y",
        };
        for (String c : chains) {
            assertFalse("应拦截链式命令: " + c, CommandWhitelist.matches(c, rules("del /q")));
        }
    }

    @Test
    public void prefix_quotedMetacharsAreLiteral() {
        assertTrue(CommandWhitelist.matches("del /q \"a&b.txt\"", rules("del /q")));
        assertTrue(CommandWhitelist.matches("del /q \"a;b.txt\"", rules("del /q")));
    }

    @Test
    public void prefix_unclosedQuote_rejected() {
        assertFalse(CommandWhitelist.matches("del /q \"x && rm -rf y", rules("del /q")));
    }

    @Test
    public void mixedRules_legacyStillWinsOverPrefixGuard() {
        // 同一配置里既有旧单词又有前缀：链式命令被前缀拒绝但旧单词仍放行
        assertTrue(CommandWhitelist.matches("del /q x && rm y", rules("del /q", "del")));
        assertFalse(CommandWhitelist.matches("del /q x && rm y", rules("del /q")));
    }

    // ---- 边界 ----

    @Test
    public void nullAndEmpty_safe() {
        assertFalse(CommandWhitelist.matches(null, rules("rm")));
        assertFalse(CommandWhitelist.matches("", rules("rm")));
        assertFalse(CommandWhitelist.matches("   ", rules("rm")));
        assertFalse(CommandWhitelist.matches("rm x", null));
        assertFalse(CommandWhitelist.matches("rm x", Collections.<String>emptySet()));
        assertFalse(CommandWhitelist.matches("rm x", rules("", "  ")));
    }

    @Test
    public void tokenize_keepsQuotedSpaces() {
        assertEquals(Arrays.asList("del", "/q", "my file.tmp"),
                CommandWhitelist.tokenize("del /q \"my file.tmp\""));
        assertEquals(Arrays.asList("git", "status"), CommandWhitelist.tokenize("  git   status  "));
    }
}