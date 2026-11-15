package com.minion.core.tools;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** 实际子JVM从含!路径fat jar加载JNA，不能只验证安装器复制成功。 */
public class NativeLibraryBootstrapTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void jnaNativePidWorksWhenJarIsInChineseSpaceBangPath()throws Exception {
        Assume.assumeTrue(System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"));
        Path source=Paths.get("target/minion-0.1.0.jar").toAbsolutePath();Assume.assumeTrue("需要既有离线fat jar作JNA资源夹具",Files.isRegularFile(source));
        Path folder=tmp.newFolder("中文 空格 ! 路径").toPath();Path jar=folder.resolve("minion.jar");Files.copy(source,jar);
        String classes=Paths.get(NativeLibraryBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests=Paths.get(NativeLibraryBootstrapTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String cp=tests+java.io.File.pathSeparator+classes+java.io.File.pathSeparator+jar;
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",cp,PidCheck.class.getName()).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();Thread reader=new Thread(()->{try{byte[] b=new byte[4096];int n;while((n=process.getInputStream().read(b))!=-1)output.write(b,0,n);}catch(Exception ignored){}});reader.setDaemon(true);reader.start();
        try {assertTrue("原生API子进程超时",process.waitFor(30,TimeUnit.SECONDS));reader.join(3000);String text=new String(output.toByteArray(),"UTF-8");assertEquals(text,0,process.exitValue());assertTrue(text,text.contains("NATIVE_PID_OK"));}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
    public static class PidCheck {
        public static void main(String[] args)throws Exception {
            Process child=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),"-version").redirectErrorStream(true).start();
            long pid=ProcessCleanup.pidOf(child);if(pid<=0)throw new AssertionError("从含!的jar加载原生DLL失败");
            ProcessCleanup.terminate(child);child.waitFor(5,TimeUnit.SECONDS);System.out.println("NATIVE_PID_OK "+pid);
        }
    }
}
