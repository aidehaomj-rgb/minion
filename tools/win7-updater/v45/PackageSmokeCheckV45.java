import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.*;
import com.minion.core.tools.ProcessCleanup;

/** Explicit offline QA against an actual patched JAR, never included in the patch payload. */
public class PackageSmokeCheckV45 {
    public static void main(String[] args)throws Exception {
        if(args.length>0&&"sleep".equals(args[0])){Thread.sleep(30000);return;}
        if(!System.getProperty("java.version").startsWith("1.8."))throw new AssertionError("Java8 required");
        // Exercise the application's first native API entry, before explicitly initializing JNA.
        String java=Paths.get(System.getProperty("java.home"),"bin","java.exe").toString();
        Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),"PackageSmokeCheckV45","sleep").start();
        try {
            long pid=ProcessCleanup.pidOf(child);if(pid<=0)throw new AssertionError("Native PID lookup failed");
            ProcessCleanup.terminate(child);if(!child.waitFor(6,TimeUnit.SECONDS))throw new AssertionError("Native process cleanup failed");
            System.out.println("PASS native PID/cleanup: "+pid);
        }finally{if(child.isAlive())child.destroyForcibly();}
        for(String name:new String[]{"javafx.application.Application","com.minion.Main","com.sun.jna.Native",
                "okhttp3.OkHttpClient","com.google.gson.Gson","com.mysql.cj.jdbc.Driver","org.postgresql.Driver",
                "oracle.jdbc.OracleDriver","com.jcraft.jsch.JSch","com.minion.core.agent.TaskWorkflow",
                "com.minion.core.agent.TaskValidation","com.minion.core.agent.ChildTaskHost",
                "com.minion.core.agent.ChildTaskCoordinator","com.minion.core.agent.ToolSchemaRouter",
                "com.minion.core.context.ProjectMemoryStore","com.minion.core.llm.ModelCapabilityStore",
                "com.minion.core.tools.browser.BrowserActionTool","com.minion.core.tools.dev.ArtifactVerificationTool",
                "com.minion.core.tools.dev.ValidationRunner","com.minion.gui.ReliabilityPanel"}) {
            Class.forName(name);System.out.println("PASS class: "+name);
        }
        java.lang.reflect.Method about=Class.forName("com.minion.gui.dialog.SettingsDialog").getDeclaredMethod("aboutText");
        about.setAccessible(true);String text=about.invoke(null).toString();
        if(!text.contains("V45")||text.contains("@")||text.contains("联系方式")||text.contains("作者："))throw new AssertionError("About/version/contact check failed");
        Path configRoot=Files.createTempDirectory("minion-v45-default-resource-");
        try {
            com.minion.core.config.Config config=com.minion.core.config.Config.load(configRoot);
            if(config.skillsDir().isEmpty()||!Files.isRegularFile(config.externalFile()))throw new AssertionError("Default config resource could not be read");
            System.out.println("PASS actual default config resource from patched JAR");
        }finally{Files.deleteIfExists(configRoot.resolve("config.properties"));Files.deleteIfExists(configRoot);}
        Path file=Files.createTempFile("minion-v45-pdf-",".pdf");
        try {
            try(PDDocument doc=new PDDocument()){doc.addPage(new PDPage());doc.save(file.toFile());}
            try(PDDocument doc=PDDocument.load(file.toFile())){if(doc.getNumberOfPages()!=1)throw new AssertionError("PDF roundtrip failed");}
        }finally{Files.deleteIfExists(file);}
        System.out.println("PASS V45 patched JAR: Java8/JavaFX, dependencies/features, PDF, native cleanup, version and no personal contact");
        System.out.println("Real Win7, model services and business acceptance are NOT certified by this smoke test.");
    }
}
