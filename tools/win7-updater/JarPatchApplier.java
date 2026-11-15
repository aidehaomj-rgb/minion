import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** JDK 8 compatible JAR entry patcher used by the Win7 incremental updater. */
public final class JarPatchApplier {
    private static final int BUFFER_SIZE = 32768;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: JarPatchApplier <source.jar> <patch.zip> <output.jar>");
            System.exit(2);
        }
        File source = new File(args[0]);
        File patch = new File(args[1]);
        File output = new File(args[2]);
        if (!source.isFile() || !patch.isFile()) {
            System.err.println("Source JAR or patch ZIP does not exist.");
            System.exit(3);
        }

        Map<String, byte[]> replacements = readPatch(patch);
        ZipFile input = new ZipFile(source);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output));
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry old = entries.nextElement();
                if (replacements.containsKey(old.getName())) continue;
                ZipEntry copy = new ZipEntry(old.getName());
                copy.setTime(old.getTime());
                out.putNextEntry(copy);
                if (!old.isDirectory()) copy(input.getInputStream(old), out, buffer);
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> replacement : replacements.entrySet()) {
                ZipEntry entry = new ZipEntry(replacement.getKey());
                out.putNextEntry(entry);
                out.write(replacement.getValue());
                out.closeEntry();
            }
        } finally {
            try { out.close(); } finally { input.close(); }
        }
        // 小更新包若误带 jar 工具自动生成的空 MANIFEST.MF，会把主程序入口覆盖掉。
        // 移动 .new 到正式 JAR 前验证最终清单，失败时由更新脚本保留旧文件。
        ZipFile verified = new ZipFile(output);
        try {
            ZipEntry entry = verified.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) throw new IllegalStateException("Patched JAR has no manifest.");
            InputStream manifestStream = verified.getInputStream(entry);
            try {
                Manifest manifest = new Manifest(manifestStream);
                String mainClass = manifest.getMainAttributes().getValue("Main-Class");
                if (!"com.minion.Boot".equals(mainClass))
                    throw new IllegalStateException("Patched JAR has no Minion Main-Class.");
            } finally { manifestStream.close(); }
        } finally { verified.close(); }
    }

    private static Map<String, byte[]> readPatch(File patch) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        ZipInputStream in = new ZipInputStream(new FileInputStream(patch));
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                copy(in, bytes, buffer);
                result.put(entry.getName().replace('\\', '/'), bytes.toByteArray());
            }
        } finally { in.close(); }
        if (result.isEmpty()) throw new IllegalArgumentException("Patch ZIP is empty.");
        return result;
    }

    private static void copy(InputStream in, java.io.OutputStream out, byte[] buffer) throws Exception {
        int n;
        while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
    }
}
