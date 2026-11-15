package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.math.BigDecimal;
import com.minion.core.tools.dev.ArtifactVerifier;

/** Deterministic checks approved in the GUI, scoped to one task version. */
public final class AcceptanceRules {
    private static final List<String> TYPES=Arrays.asList("file_exists","file_hash","file_stable","image_dimensions","executable_target","json_count","table_rows","table_columns","unique_column","table_sum","children_reviewed","tool_success");
    public static void validate(JsonArray rules) {
        if(rules.size()>32)throw new IllegalArgumentException("最多32条规则");
        for(JsonElement item:rules){if(!item.isJsonObject())throw new IllegalArgumentException("每条规则必须为对象");JsonObject r=item.getAsJsonObject();String type=str(r,"type");
            if(!TYPES.contains(type))throw new IllegalArgumentException("不支持规则: "+type);
            if(!Arrays.asList("children_reviewed","tool_success").contains(type)&&str(r,"path").isEmpty())throw new IllegalArgumentException("缺少path");
            if(Arrays.asList("json_count","table_rows").contains(type)&&(!r.has("count")||r.get("count").getAsInt()<0))throw new IllegalArgumentException("count必须为非负整数");
            if("file_hash".equals(type)&&!str(r,"sha256").matches("(?i)[0-9a-f]{64}"))throw new IllegalArgumentException("sha256非法");
            if("table_columns".equals(type)&&(!r.has("columns")||!r.get("columns").isJsonArray()))throw new IllegalArgumentException("columns必须为数组");
            if("unique_column".equals(type)&&str(r,"column").isEmpty())throw new IllegalArgumentException("缺少column");
            if("tool_success".equals(type)&&str(r,"tool").isEmpty())throw new IllegalArgumentException("缺少tool");
            if("image_dimensions".equals(type))for(String k:Arrays.asList("width","height"))if(!r.has(k)||r.get(k).getAsBigDecimal().compareTo(BigDecimal.ZERO)<=0||r.get(k).getAsBigDecimal().stripTrailingZeros().scale()>0)throw new IllegalArgumentException(k+"必须为正整数");
            if("executable_target".equals(type)&&(!Arrays.asList("windows","macos").contains(str(r,"platform"))||!Arrays.asList("x86","x64","arm64").contains(str(r,"architecture"))))throw new IllegalArgumentException("目标platform/architecture非法（只验证头部，不验证运行）");
            if("table_sum".equals(type)){if(str(r,"column").isEmpty()||str(r,"sum").isEmpty())throw new IllegalArgumentException("table_sum需要column/sum");new BigDecimal(str(r,"sum"));if(r.has("tolerance")&&new BigDecimal(str(r,"tolerance")).signum()<0)throw new IllegalArgumentException("tolerance不能为负");}
        }
    }
    public static JsonObject evaluate(JsonArray rules,Workspace w,TaskState state,java.util.function.Predicate<Path> allowed) {
        JsonObject report=new JsonObject();JsonArray results=new JsonArray();String overall="passed";
        for(JsonElement element:rules){JsonObject r=element.getAsJsonObject(),result=new JsonObject();String type=str(r,"type"),status="unknown",detail="";
            result.add("rule",r.deepCopy());try {
                boolean ok=false;
                if("children_reviewed".equals(type)){ok=true;for(TaskState.Child c:state.childSnapshot())ok&="reviewed".equals(c.status)||"validated".equals(c.status);}
                else if("tool_success".equals(type)){synchronized(state){for(TaskState.Evidence e:state.evidence)if(Objects.equals(state.id,e.taskId)&&"root".equals(e.scope)&&e.success&&str(r,"tool").equals(e.tool))ok=true;}}
                else {Path p=PathsGuard.resolve(w.cwd().toString(),str(r,"path")).toAbsolutePath().normalize();ArtifactVerifier.checkPath(p,allowed);
                    result.addProperty("path",p.toString());if("file_exists".equals(type))ok=Files.isRegularFile(p);
                    else if(!Files.isRegularFile(p)){status="failed";detail="文件不存在";}
                    else {if(Files.size(p)>50L*1024*1024)throw new IllegalArgumentException("验收文件超过50MB");result.addProperty("actualSha256",FileEvidence.digest(p));
                        if("file_hash".equals(type))ok=str(r,"sha256").equalsIgnoreCase(result.get("actualSha256").getAsString());
                        else if("file_stable".equals(type)){JsonObject stable=ArtifactVerifier.stableFile(p,r.has("stableMs")?r.get("stableMs").getAsInt():500,r.has("timeoutMs")?r.get("timeoutMs").getAsInt():10000,allowed);result.add("stableFile",stable);result.addProperty("scope","stable_file_sha256_not_download_or_runtime");ok=true;}
                        else if("image_dimensions".equals(type)){JsonObject image=ArtifactVerifier.image(p);result.add("image",image);result.addProperty("scope","image_dimensions_not_fonts_or_overflow");ok=image.get("width").getAsDouble()==r.get("width").getAsDouble()&&image.get("height").getAsDouble()==r.get("height").getAsDouble();}
                        else if("executable_target".equals(type)){JsonObject executable=ArtifactVerifier.executable(p);result.add("executable",executable);result.addProperty("scope","target_header_only_not_executable_runtime");ok=str(r,"platform").equals(str(executable,"platform"))&&str(r,"architecture").equals(str(executable,"architecture"));}
                        else if("json_count".equals(type)){if(Files.size(p)>10L*1024*1024)throw new IllegalArgumentException("JSON超过10MB");JsonElement json=JsonParser.parseString(new String(Files.readAllBytes(p),StandardCharsets.UTF_8));if(!json.isJsonArray())throw new IllegalArgumentException("JSON不是数组");int count=json.getAsJsonArray().size();result.addProperty("actualCount",count);ok=count==r.get("count").getAsInt();}
                        else {TableFacts table=TableFacts.read(p,str(r,"sheet"));result.addProperty("actualCount",table.rows.size());result.add("actualColumns",new Gson().toJsonTree(table.headers));
                            if("table_rows".equals(type))ok=table.rows.size()==r.get("count").getAsInt();
                            if("table_columns".equals(type)){ok=true;for(JsonElement c:r.getAsJsonArray("columns"))ok&=table.headers.contains(c.getAsString());}
                            if("unique_column".equals(type)){int index=table.headers.indexOf(str(r,"column"));if(index<0)throw new IllegalArgumentException("找不到列");Set<String> seen=new HashSet<String>();ok=true;for(List<String> row:table.rows){String v=index<row.size()?row.get(index):"";if(v.trim().isEmpty()||!seen.add(v))ok=false;}}
                            if("table_sum".equals(type)){int index=table.headers.indexOf(str(r,"column"));if(index<0)throw new IllegalArgumentException("找不到列");BigDecimal sum=BigDecimal.ZERO;for(List<String> row:table.rows){if(index>=row.size()||row.get(index).trim().isEmpty())throw new IllegalArgumentException("汇总列存在空值");sum=sum.add(new BigDecimal(row.get(index).trim()));}BigDecimal tolerance=r.has("tolerance")?new BigDecimal(str(r,"tolerance")):BigDecimal.ZERO;result.addProperty("actualSum",sum.toPlainString());result.addProperty("formulaCalculationVerified",false);result.addProperty("scope","stored_numeric_values_sum_not_formula_recalculation");ok=sum.subtract(new BigDecimal(str(r,"sum"))).abs().compareTo(tolerance)<=0;}
                        }
                        ArtifactVerifier.checkPath(p,allowed);if(!result.get("actualSha256").getAsString().equals(FileEvidence.digest(p)))throw new IllegalArgumentException("验收过程中文件改变，旧结论失效");
                    }
                }
                if(detail.isEmpty()){status=ok?"passed":"failed";detail=ok?"规则通过（仅证明该规则）":"规则未通过";}
            }catch(Exception e){detail=e.getClass().getSimpleName()+": "+e.getMessage();}
            result.addProperty("status",status);result.addProperty("detail",detail);result.addProperty("runtimeVerified",false);result.addProperty("claimBoundary","仅证明当前规则；file_exists/PE头/工具成功不是业务正确或可运行证明");results.add(result);
            if("unknown".equals(status))overall="unknown";else if("failed".equals(status)&&!"unknown".equals(overall))overall="failed";
        }
        report.addProperty("status",overall);report.add("results",results);return report;
    }
    static String str(JsonObject r,String key){return r.has(key)&&!r.get(key).isJsonNull()?r.get(key).getAsString():"";}
}
