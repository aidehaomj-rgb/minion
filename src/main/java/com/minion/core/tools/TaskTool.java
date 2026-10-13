package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;

/** 派发子 agent。由 AgentLoop 提供执行器。 */
public class TaskTool implements Tool {

    private final AgentLoop loop;

    public TaskTool(AgentLoop loop) { this.loop = loop; }

    @Override
    public String name() { return "task"; }

    @Override
    public String description() { return "派发独立子任务。mode=explore只读探索/worker可写；allowedTools逗号分隔白名单；model指定已配置模型标识。description任务，prompt补充要求。每个Agent指定不重叠范围和输出文件"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("派发子 agent 任务",
                new String[]{"description", "prompt", "mode", "allowedTools", "model"}, new String[]{"description"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("description")) return ToolResult.error("缺少 description 参数");
        String result = loop.runSubAgent(args);
        return ToolResult.success(result);
    }
}
