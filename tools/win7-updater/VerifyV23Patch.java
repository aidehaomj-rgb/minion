import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Load every shipped class from the patched V22 JAR, not target/classes. */
public final class VerifyV23Patch {
    public static void main(String[] args) throws Exception {
        int count = 0;
        try (ZipFile patch = new ZipFile(args[0])) {
            Enumeration<? extends ZipEntry> entries = patch.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) continue;
                Class<?> type = Class.forName(name.substring(0, name.length()-6).replace('/', '.'),
                        false, VerifyV23Patch.class.getClassLoader());
                type.getDeclaredConstructors();
                type.getDeclaredMethods();
                type.getDeclaredFields();
                count++;
            }
        }
        System.out.println("Verified patched class linkage: " + count);
    }
}
