import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.*;
import com.minion.core.tools.ProcessCleanup;
public class PackageSmokeCheck {
 public static void main(String[] args)throws Exception {
  if(args.length>0 && args[0].equals("sleep")){Thread.sleep(30000);return;}
  if(!System.getProperty("java.version").startsWith("1.8."))throw new IllegalStateException("Java 8 required");
  System.out.println("Java: "+System.getProperty("java.version"));
  for(String c:new String[]{"javafx.application.Application","com.minion.Main","okhttp3.OkHttpClient","com.google.gson.Gson","org.apache.pdfbox.pdmodel.PDDocument","com.mysql.cj.jdbc.Driver","org.postgresql.Driver","oracle.jdbc.OracleDriver","com.jcraft.jsch.JSch","com.sun.jna.Native"}){Class.forName(c);System.out.println("OK class: "+c);}
  for(String c:new String[]{"com.minion.core.storage.SessionIndex","com.minion.core.storage.SessionEventJournal","com.minion.core.agent.StopGate","com.minion.gui.TaskDashboard"}){Class.forName(c);System.out.println("OK V43 feature: "+c);}
  Path f=Files.createTempFile("minion-pdf-check-",".pdf");
  try{try(PDDocument d=new PDDocument()){d.addPage(new PDPage());d.save(f.toFile());}try(PDDocument d=PDDocument.load(f.toFile())){if(d.getNumberOfPages()!=1)throw new AssertionError();}}finally{Files.deleteIfExists(f);}
  boolean win=System.getProperty("os.name").startsWith("Windows");
  String java=Paths.get(System.getProperty("java.home"),"bin",win?"java.exe":"java").toString();
  Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),"PackageSmokeCheck","sleep").start();
  try{long pid=ProcessCleanup.pidOf(child);if(pid<=0)throw new AssertionError("PID lookup failed");ProcessCleanup.terminate(child);if(!child.waitFor(6,TimeUnit.SECONDS))throw new AssertionError("Child cleanup failed");System.out.println("OK process PID/termination: "+pid);}finally{child.destroyForcibly();}
  System.out.println("PASS: Java/JavaFX classes, JDBC/SSH libraries, PDF read/write, process cleanup");
  System.out.println("GUI rendering and remote service connectivity need manual testing.");
 }
}
