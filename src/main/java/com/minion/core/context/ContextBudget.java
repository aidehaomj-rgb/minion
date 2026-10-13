package com.minion.core.context;

import com.minion.core.llm.Message;
import com.google.gson.JsonObject;
import java.util.*;

/** Request snapshot, not a traversal of a live GUI-thread message array. */
public final class ContextBudget {
    public static String describe(List<Message> messages,List<JsonObject> schemas,int configured,int safe,int trigger,String profile) {
        int system=0,skills=0,summary=0,history=0,tools=0,reasoning=0,images=0,args=0,schema=0;
        for(Message m:messages) {
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
        return "请求上下文组成（本地粗估，非服务端窗口证明）\n系统/当前任务: "+system+"\n已加载技能: "+skills+"\n历史摘要: "+summary+"\n对话正文: "+history+"\n工具结果: "+tools+"\n历史思考: "+reasoning+"\n工具参数: "+args+"\n图片粗估: "+images+"\n工具定义: "+schema
            +"\n合计约: "+(system+skills+summary+history+tools+reasoning+images+args+schema)
            +"\n配置窗口: "+configured+"；安全窗口: "+safe+"；自动压缩线: "+trigger+"；策略: "+profile
            +"\n节省建议：知识库先search再read；长工具输出按路径分段；子Agent只返回证据摘要。";
    }
}
