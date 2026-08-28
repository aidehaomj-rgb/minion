package com.minion.core.tools.security;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.util.Locale;

/** 权限策略管理；默认 workspace-write，兼顾可用性与工作区边界。 */
public final class PermissionTool implements Tool {
    private final Config config;
    public PermissionTool(Config config){this.config=config;}
    @Override public String name(){return "Permission";}
    @Override public String description(){return "权限策略：status/set/audit；模式 read-only、workspace-write（默认）、full";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("权限策略",new String[]{"action","mode","lines"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){return "set".equalsIgnoreCase(text(args,"action"));}
    @Override public ToolResult execute(JsonObject args)throws Exception{String a=text(args,"action").toLowerCase(Locale.ROOT);if("status".equals(a))return ToolResult.success("权限模式: "+config.permissionMode()+"\nread-only=拒绝修改；workspace-write=工作区内修改需按风险确认；full=不弹高危确认（仍受路径守卫）");if("set".equals(a)){String mode=text(args,"mode").toLowerCase(Locale.ROOT);if(!mode.matches("read-only|workspace-write|full"))return ToolResult.error("mode 仅支持 read-only/workspace-write/full");config.set("permission.mode",mode);DiagnosticLog.info("permission","模式切换为 "+mode);return ToolResult.success("权限模式已切换为 "+mode);}if("audit".equals(a))return ToolResult.success(String.join("\n",DiagnosticLog.tail(integer(args,"lines",100))));return ToolResult.error("未知 action（支持 status/set/audit）");}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
}
