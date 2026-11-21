package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;

/** 派发子 agent。由 AgentLoop 提供执行器。 */
public class TaskTool implements Tool {

    /** 仅登记到独立调度器并立即返回；禁止在主工具池同步等待子任务。 */
    public interface Dispatcher { ToolResult submit(JsonObject args); }
    private final Dispatcher dispatcher;
    private final AgentLoop legacyLoop;

    public TaskTool(AgentLoop loop) { this.dispatcher = null; this.legacyLoop=loop; }
    public TaskTool(Dispatcher dispatcher) { this.dispatcher = java.util.Objects.requireNonNull(dispatcher); this.legacyLoop=null; }

    @Override
    public String name() { return "task"; }

    @Override
    public String description() { return "派发子任务，继承主模型，禁止model。推荐提供revision/stepId/scope相对路径前缀数组/dependsOn数组/acceptance，进入异步有界队列立即返回；通过AgentControl child_status/child_wait/child_review读取和复核，不能把排队当完成。worker范围唯一，限定内置单文件工具；requiresMerge=true用干净scope的worktree，isolatedOutput=true用独立目录。旧description/prompt入口仍同步兼容。"; }

    @Override
    public JsonObject schema() {
        JsonObject schema=SchemaGenerator.objectSchema("登记子 agent 任务",
                new String[]{"description", "prompt", "mode", "allowedTools", "subagent_type", "run_in_background", "isolatedOutput", "stepId", "scope", "dependsOn", "acceptance", "timeoutMillis", "requiresMerge", "revision"},
                dispatcher==null?new String[]{"description"}:new String[]{"description", "stepId", "scope", "dependsOn", "acceptance"});
        JsonObject properties=schema.getAsJsonObject("properties");
        for(String key:new String[]{"scope","dependsOn"}){JsonObject p=new JsonObject();p.addProperty("type","array");JsonObject item=new JsonObject();item.addProperty("type","string");p.add("items",item);properties.add(key,p);}
        for(String key:new String[]{"isolatedOutput","requiresMerge","run_in_background"})properties.getAsJsonObject(key).addProperty("type","boolean");
        properties.getAsJsonObject("timeoutMillis").addProperty("type","integer");
        properties.getAsJsonObject("revision").addProperty("type","integer");
        schema.addProperty("additionalProperties",false);return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (args==null) return ToolResult.errorWithoutSideEffects("缺少任务参数");
        if (args.has("model"))
            return ToolResult.error("子Agent模型已锁定为主会话模型，不能单独指定model");
        if(dispatcher==null&&legacyLoop!=null) {
            if(args.has("stepId")||args.has("scope")||args.has("dependsOn")||args.has("revision")||args.has("requiresMerge")){
                for(String key:new String[]{"description","stepId","scope","dependsOn","acceptance","revision"})if(!args.has(key)||args.get(key).isJsonNull())return ToolResult.errorWithoutSideEffects("结构化子任务缺少 "+key);
                return legacyLoop.submitCoordinatedChild(args);
            }
            if(!args.has("description")||args.get("description").isJsonNull())return ToolResult.errorWithoutSideEffects("缺少description");
            String text=legacyLoop.runSubAgent(args);
            if(text==null||text.startsWith("子Agent失败")||text.contains("【状态】失败/中断")||text.startsWith("子Agent已在运行"))return ToolResult.error(text==null?"子Agent未返回结果":text);
            return ToolResult.success(text);
        }
        for(String key:new String[]{"description","stepId","scope","dependsOn","acceptance"})if(!args.has(key)||args.get(key).isJsonNull())return ToolResult.errorWithoutSideEffects("缺少 "+key+" 参数");
        if(dispatcher==null)return ToolResult.errorWithoutSideEffects("子任务协调器尚未接线：需宿主注入TaskTool(Dispatcher)，旧字符串runner不能确认真实成功；本次未派发");
        try{ToolResult result=dispatcher.submit(args.deepCopy());return result==null?ToolResult.error("子任务调度器未返回回执"):result;}
        catch(Exception e){return ToolResult.error("子任务登记异常，需核对是否已登记，不能盲目重试: "+e.getMessage());}
    }
}
