package com.minion.core.tools.dev;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 隔离调度使用的有界 Git 进程；不经 shell，不把截断/超时伪装成完整输出。 */
public final class GitCommand {
    private GitCommand() { }
    public static final class Result {
        public int exitCode;
        public boolean truncated;
        public byte[] bytes;
        public String stderr;
        public String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }
    public static Result run(Path cwd, List<String> arguments, long timeoutMillis, int maxBytes) throws Exception {
        if (timeoutMillis <= 0 || maxBytes <= 0) throw new IOException("Git 操作超时/输出上限无效");
        List<String> command = new ArrayList<String>(); command.add("git"); command.addAll(arguments);
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        pb.environment().put("GIT_PAGER", "cat");
        final Process process = pb.start();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final ByteArrayOutputStream errors = new ByteArrayOutputStream();
        final boolean[] truncated = {false}; final IOException[] readError = {null};
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) synchronized (output) {
                    int keep = Math.min(n, Math.max(0, maxBytes - output.size()));
                    output.write(buffer, 0, keep); if (keep < n) truncated[0] = true;
                }
            } catch (IOException e) { synchronized (output) { readError[0] = e; } }
        }, "minion-git-output");
        reader.setDaemon(true); reader.start();
        Thread errorReader = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)synchronized(errors){errors.write(buffer,0,Math.min(n,Math.max(0,16384-errors.size())));}
            }catch(IOException e){synchronized(output){readError[0]=e;}}
        },"minion-git-errors");errorReader.setDaemon(true);errorReader.start();
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IOException("Git 操作超时；需核对可能产生的副作用");
            reader.join(1000);errorReader.join(1000);
            if (reader.isAlive()||errorReader.isAlive()) throw new IOException("Git 输出读取未结束，不能认定成功");
            synchronized (output) {
                if (readError[0] != null) throw readError[0];
                Result result = new Result(); result.exitCode = process.exitValue();
                result.truncated = truncated[0]; result.bytes = output.toByteArray();
                synchronized(errors){result.stderr=new String(errors.toByteArray(),StandardCharsets.UTF_8);}return result;
            }
        } finally {
            if (process.isAlive()) {
                process.destroy();
                // 不吞掉中断，也不无限等待忽略终止的进程。
                process.destroyForcibly();
            }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
        }
    }
}
