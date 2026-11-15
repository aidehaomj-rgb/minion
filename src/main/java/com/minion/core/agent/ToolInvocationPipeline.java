package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import java.util.function.BooleanSupplier;

/** Shared root/child tool lifecycle; does not reinterpret a successful operation as never executed. */
public final class ToolInvocationPipeline {
    public interface Invocation { ToolResult invoke() throws Exception; }
    private ToolInvocationPipeline(){}
    public static ToolResult run(String tool,JsonObject args,SessionHooks hooks,JsonObject metadata,
                                 BooleanSupplier current,Invocation body) throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("tool cancelled");
        if(!current.getAsBoolean())return ToolResult.errorWithoutSideEffects("工具归属已改变，未执行");
        if(hooks!=null){JsonObject before=hooks.run("before_tool",tool,args,metadata);
            if(!passed(before))return ToolResult.error("工具未执行；前置Hook失败（Hook自身可能已产生副作用）: "+before);}
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("tool cancelled after hook");
        if(!current.getAsBoolean())return ToolResult.error("工具归属在前置Hook后改变，工具未执行；请核对Hook副作用");
        ToolResult result=body.invoke();
        // Cancellation can arrive while a provider ignores interrupts. Do not launch another process.
        if(!current.getAsBoolean()||Thread.currentThread().isInterrupted())return result;
        if(hooks!=null){JsonObject post=metadata==null?new JsonObject():metadata.deepCopy();post.addProperty("toolOk",result.ok);
            JsonObject after=hooks.run("after_tool",tool,args,post);
            if(!passed(after)){
                ToolResult failed=ToolResult.error("工具已执行，原结果ok="+result.ok+"；后置Hook未通过，禁止把本操作当成未执行而自动重放。\n原结果:\n"+result.output+"\nHook:\n"+after);
                failed.images=result.images;return failed;
            }}
        return result;
    }
    public static boolean passed(JsonObject report){return report!=null&&report.has("status")&&"passed".equals(report.get("status").getAsString());}
}
