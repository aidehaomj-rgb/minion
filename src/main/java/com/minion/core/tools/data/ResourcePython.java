package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.python.PythonRuntime;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** 固定内置脚本 + 临时JSON参数文件，避免Windows命令行长度限制。 */
final class ResourcePython {
    static ToolResult run(PythonRuntime python,Path cwd,String scriptName,JsonObject payload)throws Exception {
        Path dir=cwd.resolve(".minion/tmp");
        if(!com.minion.core.tools.PathsGuard.inside(cwd.toString(),dir))return ToolResult.error("内部临时目录指向工作目录外，未执行工作流");
        Files.createDirectories(dir);
        Path script=Files.createTempFile(dir,"suite-",".py"),args=Files.createTempFile(dir,"args-",".json");
        try {
            try(InputStream in=ResourcePython.class.getResourceAsStream("/workflows/"+scriptName)) {
                if(in==null)return ToolResult.error("缺少内置工作流资源，请检查更新包: "+scriptName);
                Files.copy(in,script,StandardCopyOption.REPLACE_EXISTING);
            }
            payload.addProperty("resultPath",args.toAbsolutePath().toString()+".result.txt");
            Files.write(args,payload.toString().getBytes(StandardCharsets.UTF_8));
            PythonRuntime.RunResult result=python.runFile(script,Collections.singletonList(args.toString()),cwd,180);
            return result.ok()?ToolResult.success(result.output):ToolResult.error("本地工作流失败，未验证交付；请检查依赖及参数\n"+result.output);
        } finally {Files.deleteIfExists(script);Files.deleteIfExists(args);}
    }
}
