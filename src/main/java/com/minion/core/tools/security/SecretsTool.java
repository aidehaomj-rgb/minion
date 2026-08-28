package com.minion.core.tools.security;

import com.google.gson.JsonObject;
import com.minion.core.security.SecretStore;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.util.List;
import java.util.Locale;

/** 密钥值永不回显；数据库等工具按 connectionRef 在内部读取。 */
public final class SecretsTool implements Tool {
    private final SecretStore store;
    public SecretsTool(SecretStore store){this.store=store;}
    @Override public String name(){return "Secrets";}
    @Override public String description(){return "本机加密密钥库：status/list/set/delete/exists；值不会在工具结果或日志中回显";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("密钥管理",new String[]{"action","name","value"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return "set".equals(a)||"delete".equals(a);}
    @Override public ToolResult execute(JsonObject args)throws Exception{String a=text(args,"action").toLowerCase(Locale.ROOT),name=text(args,"name");if("status".equals(a))return ToolResult.success("加密密钥库: "+store.file()+"\n绑定当前 Windows 用户和电脑；工具输出不会显示明文");if("list".equals(a)){List<String> names=store.list();return ToolResult.success(names.isEmpty()?"密钥库为空":String.join("\n",names));}if("exists".equals(a))return ToolResult.success(store.get(name)==null?"不存在":"已配置");if("set".equals(a)){String value=text(args,"value");if(value.isEmpty())return ToolResult.error("set 缺少 value");store.set(name,value);return ToolResult.success("已加密保存: "+name);}if("delete".equals(a))return ToolResult.success(store.delete(name)?"已删除: "+name:"密钥不存在: "+name);return ToolResult.error("未知 action（支持 status/list/set/delete/exists）");}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
}
