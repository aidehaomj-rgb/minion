package com.minion.core.tools.data;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.ProcessCleanup;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owner-configured offline CLI only. Never infer a command from a document or a model argument. */
final class GdConverter implements AutoCloseable {
    private static final long MAX_INPUT=50L*1024*1024;
    final Path stage, input, output;

    static boolean configured(Config config){return config!=null&&!config.get("gd.converter.path","").trim().isEmpty();}

    private GdConverter(Path stage,String format){
        this.stage=stage;input=stage.resolve("source.gd");output=stage.resolve("export."+format);
    }

    static GdConverter convert(Path source,Path tmp,Config config)throws IOException {
        Path executable;
        List<String> template=new ArrayList<>();
        String format=config.get("gd.converter.format","pdf").trim().toLowerCase(Locale.ROOT);
        try {
            executable=Paths.get(config.get("gd.converter.path","").trim());
            if(!executable.isAbsolute()||!Files.isRegularFile(executable)
                    ||!executable.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
                throw new IllegalArgumentException("需选择可信转换工具的绝对EXE路径；不执行CMD/BAT或文档内的命令");
            if(!Arrays.asList("pdf","ofd","txt").contains(format))throw new IllegalArgumentException("输出格式须为pdf、ofd或txt");
            String raw=config.get("gd.converter.args","[\"{input}\",\"{output}\"]");
            if(raw.length()>8000)throw new IllegalArgumentException("参数配置过长");
            for(JsonElement item:JsonParser.parseString(raw).getAsJsonArray()){
                if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())throw new IllegalArgumentException("参数必须是JSON字符串数组");
                String entry=item.getAsString();
                if(entry.chars().anyMatch(c->c<32)||entry.length()>2000||template.size()>=32)
                    throw new IllegalArgumentException("参数包含不允许字符或超长");
                template.add(entry);
            }
            if(Collections.frequency(template,"{input}")!=1||Collections.frequency(template,"{output}")!=1)
                throw new IllegalArgumentException("参数需各含一个独立的 {input} 和 {output}");
        }catch(RuntimeException invalid){throw new IOException("GD离线转换配置无效："+invalid.getMessage(),invalid);}
        Path work=tmp.getParent().getParent();
        if(!PathsGuard.inside(work.toString(),tmp))throw new IOException("GD临时目录越出工作区");
        Files.createDirectories(tmp);
        if(!PathsGuard.inside(work.toString(),tmp))throw new IOException("GD临时目录越出工作区");
        GdConverter conversion=new GdConverter(Files.createTempDirectory(tmp,"gd-convert-"),format);
        try {
            copyBounded(source,conversion.input);
            List<String> command=new ArrayList<>();command.add(executable.toRealPath().toString());
            for(String arg:template)command.add("{input}".equals(arg)?conversion.input.toString():"{output}".equals(arg)?conversion.output.toString():arg);
            Process child=new ProcessBuilder(command).directory(conversion.stage.toFile()).redirectErrorStream(true).start();
            Thread drain=new Thread(()->{
                try(InputStream stream=child.getInputStream()){byte[] b=new byte[8192];while(stream.read(b)!=-1){}}
                catch(IOException ignored){ }
            },"minion-gd-converter-output");
            drain.setDaemon(true);drain.start();
            try {
                child.getOutputStream().close();
                if(!child.waitFor(90,TimeUnit.SECONDS))throw new IOException("GD本地转换超时，已停止等待");
                if(child.exitValue()!=0)throw new IOException("GD本地转换未成功，退出码 "+child.exitValue()+"；请核对已配置程序及参数");
                if(!Files.isRegularFile(conversion.output,LinkOption.NOFOLLOW_LINKS)
                        ||!PathsGuard.inside(conversion.stage.toString(),conversion.output)
                        ||Files.size(conversion.output)==0||Files.size(conversion.output)>MAX_INPUT)
                    throw new IOException("转换程序未生成有效的本地导出文件");
                return conversion;
            }catch(InterruptedException cancel){Thread.currentThread().interrupt();throw new IOException("GD本地转换已取消",cancel);}
            finally{if(child.isAlive())ProcessCleanup.terminate(child);}
        }catch(IOException|RuntimeException failed){
            try{conversion.close();}catch(IOException closeFailure){failed.addSuppressed(closeFailure);}
            throw failed;
        }
    }

    private static void copyBounded(Path source,Path dest)throws IOException {
        try(InputStream in=Files.newInputStream(source);OutputStream out=Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW)){
            long total=0;byte[] buffer=new byte[16384];int n;
            while((n=in.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new IOException("GD转换已取消");
                if((total+=n)>MAX_INPUT)throw new IOException("GD文件超过50MB");
                out.write(buffer,0,n);
            }
        }
    }

    @Override public void close()throws IOException {
        // Clean up our known files only, never recursively delete converter-supplied paths.
        Files.deleteIfExists(output);
        Files.deleteIfExists(input);
        try{Files.deleteIfExists(stage);}catch(DirectoryNotEmptyException extra){/* unknown files are not owned */}
    }
}
