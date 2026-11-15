package com.minion.core.tools;
import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.Locale;
import static org.junit.Assert.*;

public class WindowsAppIdentityTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void stableIdentityHasNoVersionNumber(){assertEquals("Minion.AI.Desktop",WindowsAppIdentity.APP_ID);assertTrue(WindowsAppIdentity.APP_ID.length()<128);}
    @Test public void launcherUsesOnlyExistingExplicitFile()throws Exception {
        String prior=System.getProperty("minion.launcher.path");try{
            Path p=tmp.getRoot().toPath().resolve("not-present.exe");System.setProperty("minion.launcher.path",p.toString());assertNull(WindowsAppIdentity.launcher());
            Files.write(p,new byte[]{1});assertEquals(p.toAbsolutePath(),WindowsAppIdentity.launcher());
        }finally{if(prior==null)System.clearProperty("minion.launcher.path");else System.setProperty("minion.launcher.path",prior);}
    }
    @Test public void actualWindowsProcessIdentityCanBeReadBack(){
        Assume.assumeTrue(System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"));
        assertTrue(WindowsAppIdentity.lastError(),WindowsAppIdentity.initialize());assertTrue(WindowsAppIdentity.initialize());
        WindowsAppIdentity.Shell shell=Native.load("shell32",WindowsAppIdentity.Shell.class);WindowsAppIdentity.Ole ole=Native.load("ole32",WindowsAppIdentity.Ole.class);
        PointerByReference ref=new PointerByReference();assertEquals(0,shell.GetCurrentProcessExplicitAppUserModelID(ref));
        try{assertEquals(WindowsAppIdentity.APP_ID,ref.getValue().getWideString(0));}finally{ole.CoTaskMemFree(ref.getValue());}
        assertEquals(5,WindowsAppIdentity.propertyKey(5).getInt(16));
    }
}
