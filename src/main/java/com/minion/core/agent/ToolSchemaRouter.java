package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.context.TokenCounter;
import java.util.*;

/** 请求级按需schema，不是权限系统。始终从已通过插件gate的schema选择，未知扩展保持兼容。 */
public final class ToolSchemaRouter {
    private static final Set<String> OFFICE=new HashSet<String>(Arrays.asList("excel","document"));
    private static final Set<String> DATABASE=new HashSet<String>(Arrays.asList("database","sqlite","dbmysql","dbpostgres","dboracle"));
    private static final Set<String> BROWSER=new HashSet<String>(Arrays.asList("browser","browseraction","browsereval","browserdebug","browserscreenshot"));
    private final Set<String> requested=new LinkedHashSet<String>();
    private String signature="";
    private final Map<String,JsonObject> catalogue=new LinkedHashMap<String,JsonObject>();
    private int cacheHits,available=-1,selected=-1,beforeTokens=-1,afterTokens=-1;
    public synchronized void reset(){requested.clear();}
    public synchronized void remember(String name){if(name!=null&&requested.size()<512)requested.add(name.toLowerCase(Locale.ROOT));}
    public synchronized List<JsonObject> select(List<JsonObject> enabled,String currentTask) {
        refresh(enabled);String hint=currentTask==null?"":currentTask.toLowerCase(Locale.ROOT);
        boolean office=hint.matches("(?s).*(?:excel|xlsx|csv|pandas|表格|统计|数据分析|word|docx|pdf|ppt|幻灯|文档|材料|报告).*"),
                database=hint.matches("(?s).*(?:sql|数据库|sqlite|oracle|mysql|postgres).*"),
                browser=hint.matches("(?s).*(?:浏览器|网页|页面|网站|chrome|html|前端).*"),
                artwork=hint.matches("(?s).*(?:绘图|画图|图片|海报|图表|封面|svg|png|生图).*" );
        List<JsonObject> result=new ArrayList<JsonObject>();
        for(Map.Entry<String,JsonObject> item:catalogue.entrySet()) {
            String name=item.getKey();boolean heavy=OFFICE.contains(name)||DATABASE.contains(name)||BROWSER.contains(name)||"artwork".equals(name);
            if(!heavy||requested.contains(name)||(office&&OFFICE.contains(name))||(database&&DATABASE.contains(name))||(browser&&BROWSER.contains(name))||(artwork&&"artwork".equals(name)))result.add(item.getValue().deepCopy());
        }
        available=catalogue.size();selected=result.size();beforeTokens=TokenCounter.estimate(enabled.toString());afterTokens=TokenCounter.estimate(result.toString());return result;
    }
    private void refresh(List<JsonObject> enabled) {
        String next=StopGate.digest(enabled.toString());if(next.equals(signature)){cacheHits++;return;}
        signature=next;catalogue.clear();for(JsonObject schema:enabled){String name=schema.getAsJsonObject("function").get("name").getAsString();catalogue.put(name.toLowerCase(Locale.ROOT),schema.deepCopy());}
    }
    public synchronized JsonObject discover(List<JsonObject> enabled,String query,String names,int offset,int limit) {
        refresh(enabled);JsonObject out=new JsonObject();JsonArray chosen=new JsonArray(),matches=new JsonArray();
        for(String name:(names==null?"":names).split("[,，;；\\s]+"))if(!name.isEmpty()) {
            String key=name.toLowerCase(Locale.ROOT);if(!catalogue.containsKey(key))throw new IllegalArgumentException("工具不存在或已停用: "+name);
            remember(key);chosen.add(catalogue.get(key).getAsJsonObject("function").get("name").getAsString());
        }
        String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);int index=0,total=0;
        for(Map.Entry<String,JsonObject> item:catalogue.entrySet()) {
            JsonObject fn=item.getValue().getAsJsonObject("function");String description=fn.has("description")?fn.get("description").getAsString():"";
            if(!needle.isEmpty()&&!item.getKey().contains(needle)&&!description.toLowerCase(Locale.ROOT).contains(needle))continue;
            if(index++>=Math.max(0,offset)&&matches.size()<Math.max(1,Math.min(20,limit))) {
                JsonObject row=new JsonObject();row.add("name",fn.get("name").deepCopy());row.addProperty("description",description.substring(0,Math.min(220,description.length())));matches.add(row);
                if(!needle.isEmpty())remember(item.getKey());
            }total++;
        }
        out.add("selected",chosen);out.add("matches",matches);out.addProperty("total",total);out.addProperty("nextOffset",Math.min(total,Math.max(0,offset)+matches.size()));
        out.addProperty("meaning","匹配或显式选择的工具完整schema将在下次请求提供；不改变权限/插件开关，不执行工具");return out;
    }
    public synchronized JsonObject stats(){JsonObject out=new JsonObject();out.addProperty("available",available);out.addProperty("selected",selected);out.addProperty("cacheHits",cacheHits);out.addProperty("estimatedSchemaTokensBefore",beforeTokens);out.addProperty("estimatedSchemaTokensAfter",afterTokens);out.addProperty("tokenScope","local_estimate_not_server_usage");return out;}
}
