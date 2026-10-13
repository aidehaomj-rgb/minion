package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;

/** 上游独立子代理通道适配；不覆盖本地主循环、恢复、压缩或任务状态。 */
public final class ChildAgentUi implements AgentUi {
    private final AgentUi parent;
    private final String id;
    private boolean ended;
    public ChildAgentUi(AgentUi parent, String id) { this.parent=parent;this.id=id; }
    private void emit(String kind,String text,Object data) { parent.onChildEvent(id,kind,text,data); }
    @Override public void onSubAgentStart(String text) { emit("START",text,null); }
    @Override public void onThinking(String text) { emit("THINKING",text,null); }
    @Override public void onSubAgentDelta(String text) { emit("CONTENT",text,null); }
    @Override public void onContent(String text) { emit("CONTENT",text,null); }
    @Override public void onResponseEnd() { emit("RESPONSE_END",null,null); }
    @Override public void onToolCall(String name,JsonObject args) { emit("TOOL_CALL",name,args==null?"{}":args.toString()); }
    @Override public void onToolResult(String name,ToolResult result) {
        emit("TOOL_RESULT",name,result==null?"ok":(result.ok?"ok\n":"error:")+(result.output==null?"":result.output));
    }
    @Override public synchronized void onSubAgentDone(String text) { finish(text); }
    public synchronized void finish(String text) { if(!ended){ended=true;emit("DONE",text,null);} }
    @Override public void onWarning(String text) { emit("WARNING",text,null); }
    @Override public void onError(String text) { emit("ERROR",text,null); }
    @Override public void onRetryProgress(RetryProgress p) {
        // 子任务重试不得覆盖主任务的运行指示器。
        if(p!=null && p.attempt>0)emit("WARNING","子任务重试第 "+p.attempt+" 次: "+(p.body==null?"":p.body),null);
    }
}
