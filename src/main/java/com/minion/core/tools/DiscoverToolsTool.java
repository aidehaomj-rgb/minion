package com.minion.core.tools;
import com.google.gson.JsonObject;
import com.minion.core.agent.ToolSchemaRouter;

/** 仅主会话发现已启用工具；不是插件启用器，不执行命令，不引入新权限。 */
public final class DiscoverToolsTool implements Tool {
    private final ToolRegistry registry;private final ToolSchemaRouter router;
    public DiscoverToolsTool(ToolRegistry registry,ToolSchemaRouter router){this.registry=registry;this.router=router;}
    public String name(){return "DiscoverTools";}
    public String description(){return "按需查找工具：query关键词会选择本页命中工具，names可逗号列出精确工具名，完整schema在下一请求可用；空query仅分页目录。仅限已启用工具，不执行动作、不修改权限";}
    public JsonObject schema(){return SchemaGenerator.objectSchema(description(),new String[]{"query","names","offset","limit"},new String[]{});}
    public ToolResult execute(JsonObject args){try{return ToolResult.success(router.discover(registry.schemas(),text(args,"query"),text(args,"names"),number(args,"offset",0),number(args,"limit",10)).toString());}catch(RuntimeException invalid){return ToolResult.errorWithoutSideEffects(invalid.getMessage());}}
    private static String text(JsonObject a,String k){return a.has(k)&&!a.get(k).isJsonNull()?a.get(k).getAsString():"";}
    private static int number(JsonObject a,String k,int d){return a.has(k)?a.get(k).getAsInt():d;}
}
