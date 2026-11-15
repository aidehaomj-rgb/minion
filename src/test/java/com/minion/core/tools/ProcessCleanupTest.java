package com.minion.core.tools;

import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ProcessCleanupTest {
    @Test public void resolvesNativePidAndTerminatesActualProcess() throws Exception {
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        Process p=new ProcessBuilder(windows?new String[]{"cmd.exe","/d","/c","ping -n 30 127.0.0.1 > nul"}
                :new String[]{"sleep","30"}).start();
        try {
            assertTrue("必须使用真实进程 ID，而不是错误反射接口",ProcessCleanup.pidOf(p)>0);
            ProcessCleanup.terminate(p);
            assertTrue(p.waitFor(6,TimeUnit.SECONDS));
        } finally {p.destroyForcibly();}
    }
}
