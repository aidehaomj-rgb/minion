package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.*;
import java.nio.file.*;
import java.util.*;

/** Explicit stage checkpoints and review. Only the orchestrator gets this tool. */
public final class AgentControlTool implements Tool {
    private final AgentLoop loop;
    private final Workspace workspace;
    public AgentControlTool(AgentLoop loop,Workspace workspace) { this.loop=loop; this.workspace=workspace; }
    public String name() { return "AgentControl"; }
    public String description() { return "登记阶段断点/处理范围/证据文件，核对子Agent结果。action=status/checkpoint/verify/review/refresh_checkpoint；review需childId和note；输出文件合法更新且实际复核完成后，用refresh_checkpoint及note刷新原断点并清除下一步，不能代替业务验证"; }
    public JsonObject schema() { return SchemaGenerator.objectSchema(description(),new String[]{"action","nextStep","processedRange","files","childId","note"},new String[]{"action"}); }
    public ToolResult execute(JsonObject args) throws Exception {
        TaskState state=loop.session().taskState; String action=text(args,"action");
        if("status".equals(action)) return ToolResult.success(state.render(loop.session().todos)+"\n实时断点核验:\n"+loop.authorizedCheckpointStatus());
        if("verify".equals(action)) return ToolResult.success(loop.authorizedCheckpointStatus());
        if("refresh_checkpoint".equals(action)) {
            String note=text(args,"note").trim();
            if(note.isEmpty()) return ToolResult.error("refresh_checkpoint需note说明实际复核及文件合法更新的原因；不能仅凭完成声明刷新");
            synchronized(state) {
                if(state.checkpointFiles.isEmpty()) return ToolResult.error("没有已登记断点文件；请用checkpoint登记实际交付文件");
                List<FileEvidence> refreshed=new ArrayList<FileEvidence>();
                for(FileEvidence old:state.checkpointFiles) {
                    Path p=Paths.get(old.path).toAbsolutePath().normalize();
                    if(!loop.evidencePathAllowed(p)) return ToolResult.error("断点路径权限不允许，未刷新: "+p);
                    FileEvidence e=FileEvidence.capture(p);
                    if(!"present".equals(e.status)) return ToolResult.error("断点文件不存在，未刷新，原记录保留: "+p);
                    refreshed.add(e);
                }
                state.checkpoint("",state.processedRange,refreshed);
                state.recordEvidence("root","checkpoint-review",true,note,null);
            }
            return ToolResult.success("复核后断点已刷新，下一步已清除；哈希仅核验文件版本，不代表业务正确。\n"+loop.authorizedCheckpointStatus());
        }
        if("checkpoint".equals(action)) {
            List<FileEvidence> files=new ArrayList<FileEvidence>();
            for(String raw:text(args,"files").split("[\\r\\n;；]+")) if(!raw.trim().isEmpty()) {
                Path p=PathsGuard.resolve(workspace.cwd().toString(),raw.trim()).toAbsolutePath().normalize();
                if(!loop.evidencePathAllowed(p)) return ToolResult.error("断点证据需位于工作区、会话临时目录或已允许目录: "+p);
                FileEvidence e=FileEvidence.capture(p); if(!"present".equals(e.status)) return ToolResult.error("断点文件不存在，未保存完成声明: "+p); files.add(e);
                if(files.size()>30) return ToolResult.error("每个断点最多30个文件，请分阶段登记");
            }
            state.checkpoint(text(args,"nextStep"),text(args,"processedRange"),files);
            return ToolResult.success("断点已保存（存在/哈希核验，不代表业务结论正确）\n"+loop.authorizedCheckpointStatus());
        }
        if("review".equals(action)) {
            if(text(args,"note").trim().isEmpty()) return ToolResult.error("review需note说明实际核对的证据");
            if(!state.reviewChild(text(args,"childId"))) return ToolResult.error("只能核对当前任务已返回的子Agent");
            state.recordEvidence("root","child-review",true,text(args,"note"),null); return ToolResult.success("子Agent已登记核对");
        }
        return ToolResult.error("action支持status/checkpoint/verify/review/refresh_checkpoint");
    }
    private static String text(JsonObject o,String key) { return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():""; }
}
