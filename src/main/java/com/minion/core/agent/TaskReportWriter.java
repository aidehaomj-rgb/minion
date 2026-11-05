package com.minion.core.agent;

import com.minion.core.diagnostics.SecretRedactor;
import com.minion.core.llm.Message;
import com.minion.core.tools.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** 将实际执行过工具的任务自动沉淀为工作区 Markdown，不再额外调用模型。 */
final class TaskReportWriter {
    private TaskReportWriter() { }

    static Path write(Workspace workspace, Session session, int fromIndex, List<String> tools,
                      boolean completed, long elapsedMs) throws Exception {
        Path dir=workspace.cwd().resolve(".minion").resolve("reports");Files.createDirectories(dir);
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path file=dir.resolve(stamp+"-task.md");
        String request="",answer="";
        for(int i=Math.max(0,fromIndex);i<session.messages.size();i++){
            Message m=session.messages.get(i);
            if(request.isEmpty()&&m.role==Message.Role.USER&&!m.summary&&!m.toolImage&&m.content!=null)request=m.content;
            if(m.role==Message.Role.ASSISTANT&&m.content!=null&&!m.content.trim().isEmpty())answer=m.content;
        }
        StringBuilder md=new StringBuilder();
        md.append("# 任务执行记录\n\n")
          .append("- 时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n')
          .append("- 状态：").append(completed?"模型报告完成（请核对交付物）":"本轮已结束，任务完成情况未确认").append('\n')
          .append("- 耗时：").append(String.format(java.util.Locale.ROOT,"%.1f 秒",elapsedMs/1000.0)).append('\n')
          .append("- 会话：").append(session.id==null?"":session.id).append("\n\n## 原始目标\n\n")
          .append(clean(request)).append("\n\n## 执行步骤\n\n");
        for(String tool:tools)md.append("- ").append(clean(tool)).append('\n');
        md.append("\n## 验证证据（最近结束核验时，不是永久有效）\n\n");
        com.google.gson.JsonArray validation=session.taskState.lastValidationSnapshot;
        if(validation==null||validation.size()==0)md.append("未记录实际验证报告；不能凭回复、文件存在或本报告认定测试通过。\n");
        else for(com.google.gson.JsonElement item:validation){com.google.gson.JsonObject r=item.getAsJsonObject();
            md.append("- 类型: ").append(value(r,"kind")).append("；状态: ").append(value(r,"status"))
              .append("；当时源码/报告版本有效: ").append(value(r,"currentSourceAndReport"))
              .append("；测试数: ").append(value(r,"tests")).append("；失败: ").append(value(r,"failures"))
              .append("；错误: ").append(value(r,"errors")).append("；跳过: ").append(value(r,"skipped"))
              .append("\n  完整日志: ").append(clean(value(r,"logPath"))).append("\n  机器报告: ").append(clean(value(r,"reportPath"))).append('\n');
        }
        md.append("\nsyntax_check仅语法；build仅构建；command_succeeded/零测试/未配置/全部跳过不等于功能验证通过。文件更新后需重新验证，完整业务准确性仍需原始资料或人工金标准。\n");
        md.append("\n## 执行结果\n\n").append(answer.isEmpty()?"本轮没有生成最终答复。":clean(answer))
          .append("\n\n## 后续复用建议\n\n")
          .append("如本次流程需要重复执行，可让 Agent 将本记录及相关脚本封装为 Skill。\n");
        Files.write(file,md.toString().getBytes(StandardCharsets.UTF_8));return file;
    }
    private static String clean(String value){String s=SecretRedactor.redact(value==null?"":value);return s.length()>20000?s.substring(0,20000)+"\n\n[内容过长，报告已截断]":s;}
    private static String value(com.google.gson.JsonObject object,String key){return !object.has(key)||object.get(key).isJsonNull()?"unknown":object.get(key).getAsString();}
}
