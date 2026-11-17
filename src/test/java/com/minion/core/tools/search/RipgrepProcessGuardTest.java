package com.minion.core.tools.search;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RipgrepProcessGuardTest {

    public static final class SlowProcess {
        public static void main(String[] args) throws Exception {
            long millis = args.length == 0 ? 30_000L : Long.parseLong(args[0]);
            Thread.sleep(millis);
        }
    }

    @Test
    public void timeout_terminatesBlockedProcess() throws Exception {
        RipgrepSidecar.Guard guard = start(200L);
        try {
            assertTrue("超时后进程必须被终止", guard.process().waitFor(5, TimeUnit.SECONDS));
            assertTrue(guard.timedOut());
            assertFalse(guard.cancelled());
            try {
                guard.throwIfAborted();
                fail("超时必须抛出 IOException");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("超过"));
            }
        } finally {
            guard.close();
        }
    }

    @Test
    public void interrupt_terminatesBlockedProcessAndPreservesFlag() throws Exception {
        RipgrepSidecar.Guard guard = start(30_000L);
        Thread.currentThread().interrupt();
        try {
            assertTrue("中断后必须标记取消", waitFor(() -> guard.cancelled(), 5_000L));
            assertTrue("中断后必须实际终止子进程",
                    waitFor(() -> !guard.process().isAlive(), 5_000L));
            try {
                guard.throwIfAborted();
                fail("取消必须抛出 InterruptedException");
            } catch (InterruptedException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {
            Thread.interrupted();
            guard.close();
        }
    }

    private static RipgrepSidecar.Guard start(long timeoutMs) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path java = Paths.get(System.getProperty("java.home"), "bin",
                windows ? "java.exe" : "java");
        return RipgrepSidecar.startGuarded(new ProcessBuilder(
                java.toString(), "-cp", System.getProperty("java.class.path"),
                SlowProcess.class.getName(), "30000"), "Grep", timeoutMs);
    }

    private static boolean waitFor(Check check, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (check.ok()) return true;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        return check.ok();
    }

    private interface Check {
        boolean ok() throws Exception;
    }
}
