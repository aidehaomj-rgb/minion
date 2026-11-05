package com.minion.core.context;

import com.minion.core.llm.Message;
import com.google.gson.JsonObject;
import com.minion.core.llm.Usage;
import java.util.*;

/** Request snapshot, not a traversal of a live GUI-thread message array. */
public final class ContextBudget {
    /** 分项均为本地粗估，不能把总 usage 按比例摊成“服务端分项”。 */
    public static final class Estimate {
        public final int system, skills, summary, history, tools, reasoning, images, args, schema;
        private Estimate(int system,int skills,int summary,int history,int tools,int reasoning,int images,int args,int schema) {
            this.system=system;this.skills=skills;this.summary=summary;this.history=history;this.tools=tools;
            this.reasoning=reasoning;this.images=images;this.args=args;this.schema=schema;
        }
        public int total() { return system+skills+summary+history+tools+reasoning+images+args+schema; }
        public JsonObject toJson() {
            JsonObject o=new JsonObject();o.addProperty("source","local_estimate");
            o.addProperty("system",system);o.addProperty("skills",skills);o.addProperty("summary",summary);
            o.addProperty("textHistory",history);o.addProperty("toolResults",tools);o.addProperty("reasoning",reasoning);
            o.addProperty("images",images);o.addProperty("toolArguments",args);o.addProperty("toolSchemas",schema);
            o.addProperty("total",total());return o;
        }
    }
    public static Estimate estimate(List<Message> messages,List<JsonObject> schemas) {
        int system=0,skills=0,summary=0,history=0,tools=0,reasoning=0,images=0,args=0,schema=0;
        if(messages!=null) for(Message m:messages) {
            int text=TokenCounter.estimate(m.content)+4;
            if(m.role==Message.Role.SYSTEM) system+=text;
            else if(m.pinned) skills+=text;
            else if(m.summary) summary+=text;
            else if(m.role==Message.Role.TOOL) tools+=text;
            else history+=text;
            reasoning+=TokenCounter.estimate(m.reasoningContent);
            if(m.images!=null) images+=m.images.size()*com.minion.core.llm.ImagePart.IMAGE_TOKENS;
            if(m.toolCalls!=null) for(com.minion.core.llm.ToolCall call:m.toolCalls) args+=TokenCounter.estimate(call.name)+TokenCounter.estimate(call.arguments);
        }
        if(schemas!=null) for(JsonObject s:schemas) schema+=TokenCounter.estimate(s.toString());
        return new Estimate(system,skills,summary,history,tools,reasoning,images,args,schema);
    }
    public static String describe(List<Message> messages,List<JsonObject> schemas,int configured,int safe,int trigger,String profile) {
        Estimate e=estimate(messages,schemas);
        int system=e.system,skills=e.skills,summary=e.summary,history=e.history,tools=e.tools,
                reasoning=e.reasoning,images=e.images,args=e.args,schema=e.schema;
        return "请求上下文组成（本地粗估，非服务端窗口证明）\n系统/当前任务: "+system+"\n已加载技能: "+skills+"\n历史摘要: "+summary+"\n对话正文: "+history+"\n工具结果: "+tools+"\n历史思考: "+reasoning+"\n工具参数: "+args+"\n图片粗估: "+images+"\n工具定义: "+schema
            +"\n合计约: "+(system+skills+summary+history+tools+reasoning+images+args+schema)
            +"\n配置窗口: "+configured+"；安全窗口: "+safe+"；自动压缩线: "+trigger+"；策略: "+profile
            +"\n节省建议：知识库先search再read；长工具输出按路径分段；子Agent只返回证据摘要。";
    }

    /** 同请求展示接口；校准只展示总量比率，不修改 150K/窗口设置。 */
    public static String describeUsage(Estimate estimate, Usage usage) {
        String actual=usage==null?"unknown":(usage.estimated?"本地估算（不参与校准）":
                "服务端总输入="+usage.inputTokens+"，总输出="+usage.outputTokens);
        String ratio=usage!=null&&!usage.estimated&&usage.inputTokens>0&&estimate.total()>0
                ?String.format(Locale.ROOT,"%.3f",(double)usage.inputTokens/estimate.total()):"unknown";
        return "分项="+estimate.toJson()+"\nusage="+actual+"\n同请求总输入校准比="+ratio
                +"；分项实际token=unknown；不修改配置窗口或自动压缩线。";
    }
}
