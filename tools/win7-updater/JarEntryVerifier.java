import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Java 8 content manifest for timestamp-independent Win7 incremental updates. */
public final class JarEntryVerifier {
    private JarEntryVerifier() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !("write".equals(args[0]) || "verify".equals(args[0]))) {
            System.err.println("Usage: JarEntryVerifier <write|verify> <jar> <manifest.tsv>");
            System.exit(2);
        }
        Path jar = Paths.get(args[1]);
        Path manifest = Paths.get(args[2]);
        Map<String, String> actual = digestEntries(jar);
        if ("write".equals(args[0])) {
            try (BufferedWriter out = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : actual.entrySet()) {
                    out.write(entry.getValue());
                    out.write('\t');
                    out.write(entry.getKey());
                    out.newLine();
                }
            }
            System.out.println("Wrote " + actual.size() + " entry hashes");
            return;
        }
        Map<String, String> expected = new TreeMap<String, String>();
        try (BufferedReader in = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab != 64 || line.length() == 65)
                    throw new IOException("Invalid entry manifest line");
                String hash = line.substring(0, tab);
                String name = line.substring(tab + 1);
                if (!hash.matches("[0-9a-f]{64}") || expected.put(name, hash) != null)
                    throw new IOException("Invalid or duplicate entry manifest item: " + name);
            }
        }
        if (!actual.equals(expected)) {
            for (String name : expected.keySet())
                if (!expected.get(name).equals(actual.get(name)))
                    throw new IOException("JAR entry differs or is missing: " + name);
            for (String name : actual.keySet())
                if (!expected.containsKey(name))
                    throw new IOException("Unexpected JAR entry: " + name);
            throw new IOException("JAR entry count differs");
        }
        System.out.println("Verified " + actual.size() + " entry hashes");
    }

    private static Map<String, String> digestEntries(Path jar) throws Exception {
        Map<String, String> entries = new TreeMap<String, String>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> iterator = zip.entries();
            byte[] buffer = new byte[32768];
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.indexOf('\t') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
                    throw new IOException("Unsafe JAR entry name: " + name);
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                try (InputStream in = zip.getInputStream(entry)) {
                    int count;
                    while ((count = in.read(buffer)) != -1)
                        if (count > 0) sha.update(buffer, 0, count);
                }
                StringBuilder hex = new StringBuilder(64);
                for (byte b : sha.digest()) hex.append(String.format("%02x", b & 255));
                if (entries.put(name, hex.toString()) != null)
                    throw new IOException("Duplicate JAR entry: " + name);
            }
        }
        return entries;
    }
}
