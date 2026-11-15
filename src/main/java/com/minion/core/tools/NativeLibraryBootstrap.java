package com.minion.core.tools;

import java.io.InputStream;
import java.nio.file.*;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** JNA旧版jar:URL在安装路径含!时会截错；首次需要原生进程API时按ZipFile解出DLL。 */
public final class NativeLibraryBootstrap {
    private static boolean attempted;
    private NativeLibraryBootstrap() { }

    public static synchronized void prepare() {
        if(attempted)return;attempted=true;
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"))return;
        try {
            // false：必须在Native静态初始化前准备，否则失败后的类不能再次初始化。
            Class<?> nativeClass=Class.forName("com.sun.jna.Native",false,NativeLibraryBootstrap.class.getClassLoader());
            Path archive=Paths.get(nativeClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(!Files.isRegularFile(archive)||archive.toString().indexOf('!')<0)return;
            String arch=System.getProperty("os.arch","").toLowerCase(Locale.ROOT);
            String folder=arch.equals("amd64")||arch.equals("x86_64")?"win32-x86-64":arch.equals("x86")||arch.equals("i386")?"win32-x86":null;
            if(folder==null)return;
            try(ZipFile zip=new ZipFile(archive.toFile())) {
                ZipEntry entry=zip.getEntry("com/sun/jna/"+folder+"/jnidispatch.dll");
                if(entry==null||entry.getSize()>8L*1024*1024)return;
                Path dir=Files.createTempDirectory("minion-jna-");dir.toFile().deleteOnExit();
                Path dll=dir.resolve("jnidispatch.dll");
                try(InputStream input=zip.getInputStream(entry)){Files.copy(input,dll);}
                dll.toFile().deleteOnExit();
                String prior=System.getProperty("jna.boot.library.path","");
                System.setProperty("jna.boot.library.path",dir.toAbsolutePath()+(prior.isEmpty()?"":java.io.File.pathSeparator+prior));
            }
        }catch(Exception ignored){/* 沿用JNA原有加载/降级，不增加启动扫描或阻止应用启动。 */}
    }
}
