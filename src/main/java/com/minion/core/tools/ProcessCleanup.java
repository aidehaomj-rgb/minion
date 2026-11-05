package com.minion.core.tools;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
/** Java 8 Windows ProcessImpl exposes a handle, not a getProcessId method. */
public final class ProcessCleanup {
    private ProcessCleanup() { }
    public interface Kernel32 extends StdCallLibrary { int GetProcessId(Pointer process); }
    private static final class WindowsApi {
        static final Kernel32 INSTANCE = load();
        private static Kernel32 load(){NativeLibraryBootstrap.prepare();return Native.load("kernel32", Kernel32.class);}
    }
    public static long pidOf(Process p) {
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                java.lang.reflect.Field field = p.getClass().getDeclaredField("handle");field.setAccessible(true);
                return Integer.toUnsignedLong(WindowsApi.INSTANCE.GetProcessId(new Pointer(field.getLong(p))));
            }
            java.lang.reflect.Field field=p.getClass().getDeclaredField("pid");field.setAccessible(true);
            return ((Number)field.get(p)).longValue();
        } catch (Exception | LinkageError e) { return -1; }
    }
    public static void terminate(Process p) {
        boolean interrupted=Thread.interrupted();
        try {
            long pid=pidOf(p);
            if(pid>0 && p.isAlive() && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                Process killer=new ProcessBuilder("taskkill.exe","/PID",Long.toString(pid),"/T","/F").redirectErrorStream(true).start();
                try { if(!killer.waitFor(5,TimeUnit.SECONDS)) killer.destroyForcibly(); }
                finally { killer.getInputStream().close(); }
            }
        } catch(InterruptedException e) { interrupted=true; }
        catch(Exception ignored) { }
        finally { p.destroyForcibly(); if(interrupted)Thread.currentThread().interrupt(); }
    }
}
