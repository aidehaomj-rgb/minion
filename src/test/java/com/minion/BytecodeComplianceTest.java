package com.minion;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/**
 * v2.4 §14.4 字节码合规验收（切片 40）：本项目与全部依赖 jar 的每个 class
 * major version ≤ 52（JDK8），且 magic 为 CAFEBABE。依赖清单取 pom 声明 +
 * classpath 实际 jar 位置（Properties classpath 读入）。
 */
public class BytecodeComplianceTest {

    @Test public void allClassesMajorVersionAtMost52() throws Exception {
        List<String> violations = new ArrayList<String>();
        int total = 0;
        // 本项目 classes
        String cp = System.getProperty("java.class.path");
        for (String entry : cp.split(";|:")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            java.io.File f = new java.io.File(trimmed);
            if (f.isDirectory()) {
                total += checkDirectory(f, f, violations);
            } else if (trimmed.endsWith(".jar") && f.exists()) {
                total += checkJar(f, violations);
            }
        }
        assertEquals("发现超 JDK8 字节码: " + violations, 0, violations.size());
        assertTrue("至少应扫描到本项目 class（实际 " + total + "）", total > 500);
    }

    private static int checkDirectory(java.io.File root, java.io.File dir,
            List<String> violations) {
        int count = 0;
        java.io.File[] files = dir.listFiles();
        if (files == null) return 0;
        for (java.io.File f : files) {
            if (f.isDirectory()) count += checkDirectory(root, f, violations);
            else if (f.getName().endsWith(".class")) {
                checkClass(f.getPath(), f.getAbsolutePath(), violations);
                count++;
            }
        }
        return count;
    }

    private static int checkJar(java.io.File jar, List<String> violations) throws Exception {
        int count = 0;
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                InputStream in = zip.getInputStream(e);
                DataInputStream data = new DataInputStream(in);
                try {
                    int magic = data.readInt();
                    data.readUnsignedShort();   // minor
                    int major = data.readUnsignedShort();
                    if (magic != 0xCAFEBABE || major > 52)
                        violations.add(jar.getName() + "!" + e.getName() + " major=" + major);
                } finally {
                    in.close();
                }
                count++;
            }
        } finally {
            zip.close();
        }
        return count;
    }

    private static void checkClass(String label, String path, List<String> violations) {
        try {
            InputStream in = new java.io.FileInputStream(path);
            DataInputStream data = new DataInputStream(in);
            try {
                int magic = data.readInt();
                data.readUnsignedShort();
                int major = data.readUnsignedShort();
                if (magic != 0xCAFEBABE || major > 52)
                    violations.add(label + " major=" + major);
            } finally {
                in.close();
            }
        } catch (Exception unreadable) {
            violations.add(label + " unreadable: " + unreadable.getMessage());
        }
    }
}
