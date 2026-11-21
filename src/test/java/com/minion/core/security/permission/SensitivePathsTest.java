package com.minion.core.security.permission;

import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.*;

/** §4.2 敏感路径验收：DANGEROUS 清单、目录组成段、Windows 规避形态（尾点/尾空格/DOS 设备/ADS/大小写） */
public class SensitivePathsTest {

    @Test public void dangerousFilesHit() {
        for (String f : new String[]{".gitconfig", ".gitmodules", ".bashrc", ".bash_profile",
                ".zshrc", ".zprofile", ".profile", ".ripgreprc", ".mcp.json"})
            assertTrue(f, SensitivePaths.hits(Paths.get("C:/work/" + f)));
    }

    @Test public void dangerousDirsHitAsPathComponent() {
        assertTrue(SensitivePaths.hits(Paths.get("repo/.git/hooks/pre-commit")));
        assertTrue(SensitivePaths.hits(Paths.get("repo/.git")));
        assertTrue(SensitivePaths.hits(Paths.get("p/.vscode/settings.json")));
        assertTrue(SensitivePaths.hits(Paths.get("p/.idea/workspace.xml")));
        assertTrue(SensitivePaths.hits(Paths.get("p/.minion/settings.json")));   // Minion 自身配置
        assertTrue(SensitivePaths.hits(Paths.get(".git/HEAD")));
    }

    @Test public void windowsEvasionFormsNormalized() {
        // 尾点/尾空格 + 大小写（尾空格在 Windows 上无法构造为 Path——走字符串入口按 fail-closed 命中）
        assertTrue(SensitivePaths.hits(Paths.get("repo/.Bashrc.")));
        assertTrue(SensitivePaths.hits(Paths.get("repo/.bashrc..")));
        assertTrue(SensitivePaths.hits("repo/.GIT "));
        // ADS 流（含冒号，Windows 无法构造为 Path——字符串入口归一化后命中）
        assertTrue(SensitivePaths.hits("repo/.gitconfig::$DATA"));
        assertTrue(SensitivePaths.hits("repo/.git\\config:$DATA"));
        // DOS 设备名（含 扩展名/尾点 规避）
        assertTrue(SensitivePaths.hits(Paths.get("repo/CON")));
        assertTrue(SensitivePaths.hits(Paths.get("repo/com1.")));
        assertTrue(SensitivePaths.hits(Paths.get("repo/LPT9.txt")));
    }

    /** 合法相近名不误伤：前缀相似但非精确设备名/非敏感名 */
    @Test public void legitimateNamesPass() {
        assertFalse(SensitivePaths.hits(Paths.get("repo/src/Main.java")));
        assertFalse(SensitivePaths.hits(Paths.get("repo/notes.txt")));
        assertFalse(SensitivePaths.hits(Paths.get("repo/console.log")));    // console ≠ CON
        assertFalse(SensitivePaths.hits(Paths.get("repo/nul_notes.md")));   // nul_notes ≠ NUL
        assertFalse(SensitivePaths.hits(Paths.get("repo/config.json")));    // 非 .mcp.json
        assertFalse(SensitivePaths.hits(Paths.get("repo/gitignore.md")));   // 无点前缀
        assertFalse(SensitivePaths.hits((String) null));
        assertFalse(SensitivePaths.hits(Paths.get("")));
    }

    /** 字符串入口：畸形路径 fail-closed 按敏感处理 */
    @Test public void malformedStringFailsClosed() {
        assertTrue(SensitivePaths.hits("a\u0000b"));
    }
}
