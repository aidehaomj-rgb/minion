package com.minion.core.tools.context;

import com.google.gson.JsonObject;
import com.minion.core.agent.Session;
import com.minion.core.context.TokenCounter;
import com.minion.core.config.Config;
import com.minion.core.llm.Message;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

/** 给模型和用户提供可解释的上下文占用与组成。 */
public final class ContextTool implements Tool {
    private final Session session; private volatile int maxTokens; private final Config config;
    public ContextTool(Session session,int maxTokens){this(session,maxTokens,null);}
    public ContextTool(Session session,int maxTokens,Config config){this.session=session;this.maxTokens=maxTokens;this.config=config;}
    public void setMaxTokens(int maxTokens){this.maxTokens=maxTokens;}
    @Override public String name(){return "Context";}
    @Override public String description(){return "上下文分析：status/messages，显示估算 token、摘要/技能/工具/图片组成，便于决定何时压缩";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("上下文统计",new String[]{"action","limit","outputMode","readMode"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){return "policy".equalsIgnoreCase(text(args,"action"));}
    @Override public ToolResult execute(JsonObject args){String a=text(args,"action");if("status".equals(a)){int tokens=TokenCounter.estimateMessages(session.messages),summary=0,pinned=0,tools=0,images=0;for(Message m:session.messages){if(m.summary)summary++;if(m.pinned)pinned++;if(m.role==Message.Role.TOOL)tools++;if(m.images!=null)images+=m.images.size();}return ToolResult.success("会话: "+session.id+"\n消息: "+session.messages.size()+"（摘要 "+summary+"，技能 "+pinned+"，工具结果 "+tools+"，图片 "+images+"）\n估算 token: "+tokens+" / "+maxTokens+" ("+(maxTokens<=0?0:tokens*100/maxTokens)+"%)\n输出策略: "+(config==null?"balanced":config.contextOutputMode())+"；读取策略: "+(config==null?"chunked":config.contextReadMode())+"\n需要主动压缩可使用 /compact");}if("messages".equals(a)){int limit=Math.max(1,Math.min(integer(args,"limit",30),200)),from=Math.max(0,session.messages.size()-limit);StringBuilder out=new StringBuilder();for(int i=from;i<session.messages.size();i++){Message m=session.messages.get(i);out.append(i+1).append(". ").append(m.role).append(m.summary?" [摘要]":"").append(m.pinned?" [技能]":"").append("  ").append(TokenCounter.estimate(m.content)).append(" tokens\n");}return ToolResult.success(out.length()==0?"暂无消息":out.toString().trim());}if("policy".equals(a)){if(config==null)return ToolResult.error("配置不可用");String output=text(args,"outputMode"),read=text(args,"readMode");if(!output.isEmpty()&&!output.matches("compact|balanced|verbose"))return ToolResult.error("outputMode 支持 compact/balanced/verbose");if(!read.isEmpty()&&!read.matches("chunked|full"))return ToolResult.error("readMode 支持 chunked/full");if(!output.isEmpty())config.set("context.outputMode",output);if(!read.isEmpty())config.set("context.readMode",read);return ToolResult.success("上下文策略已更新");}return ToolResult.error("未知 action（支持 status/messages/policy）");}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
}
