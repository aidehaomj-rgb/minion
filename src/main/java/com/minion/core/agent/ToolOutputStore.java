package com.minion.core.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** Keep full tool output on disk; send a bounded preview and a real Read-able pointer. */
public final class ToolOutputStore {
    private final Path dir;
    public ToolOutputStore(String workDir, String scope) {
        dir=Paths.get(workDir).toAbsolutePath().normalize().resolve(".minion/tool-results")
                .resolve(scope.replaceAll("[^a-zA-Z0-9_-]","_"));
    }
    public String forContext(String output) {
        if(output==null||output.length()<=8000) return output;
        try {
            Files.createDirectories(dir);
            Path file=dir.resolve(UUID.randomUUID().toString()+".txt");
            Files.write(file,output.getBytes(StandardCharsets.UTF_8));
            return output.substring(0,4200)+"\n\n[工具输出共 "+output.length()+" 字符；中间省略，完整内容已保存: "
                    +file+"。需要时用 Read 分段读取，不要据省略部分推断数据。]\n\n"
                    +output.substring(output.length()-1800);
        } catch(Exception e) { return output; } // Never silently discard evidence if disk is unavailable.
    }
    /** 显式 Read full 请求才放宽预算，其他工具继续节省上下文。 */
    public String forContext(String output, com.minion.core.llm.ToolCall call) {
        try {
            if (call != null && "Read".equals(call.name)
                    && com.minion.core.tools.ReadTool.fullOf(com.minion.core.tools.ToolArguments.parseObject(call.arguments))
                    && output != null && output.length() <= com.minion.core.tools.ReadTool.FULL_MAX_OUTPUT_CHARS)
                return output;
        } catch (RuntimeException ignored) { }
        return forContext(output);
    }
}
