package com.minion.core.agent;

import com.minion.core.llm.LlmClient;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;

/** Fresh child connection and independent cwd; factory is provided by the session manager. */
public final class AgentRuntime implements AutoCloseable {
    public final LlmClient client;
    public final String model;
    public final int maxContext;
    public final double threshold;
    public final int outputReserveTokens;
    public final ToolRegistry tools;
    public final Workspace workspace;
    public final boolean owned;
    public AgentRuntime(LlmClient client,String model,int max,double threshold,ToolRegistry tools,Workspace workspace,boolean owned) {
        this(client,model,max,threshold,8192,tools,workspace,owned);
    }
    public AgentRuntime(LlmClient client,String model,int max,double threshold,int outputReserveTokens,ToolRegistry tools,Workspace workspace,boolean owned) {
        this.client=client; this.model=model; maxContext=max; this.threshold=threshold; this.tools=tools; this.workspace=workspace; this.owned=owned;
        this.outputReserveTokens=outputReserveTokens;
    }
    public void close() { if(owned) client.close(); }
}
