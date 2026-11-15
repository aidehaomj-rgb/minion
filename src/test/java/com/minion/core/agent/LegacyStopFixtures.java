package com.minion.core.agent;

/** 旧用例的脚本核验结果适配；只存在测试中，不作为生产结束判定。 */
public final class LegacyStopFixtures {
    public static String status(String candidate,boolean tools,boolean structured) {
        if(AgentLoop.looksLikeUserBlock(candidate)&&!AgentLoop.hasTaskCompleteMarker(candidate))return "waiting_user";
        if((tools||structured)&&!AgentLoop.hasTaskCompleteMarker(candidate)
                &&(structured||candidate.length()>=1000||AgentLoop.looksLikeInterimAnswer(candidate)||AgentLoop.looksLikeInterruptedReply(candidate)))return "continue";
        return "completed";
    }
}
