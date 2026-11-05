package com.minion.core.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 有新工具证据才扩展续接；状态查询、待办勾选和重复读取不能无限刷新预算。 */
public final class ContinuationBudget {
    public static final int MAX_WINDOWS = 24;
    public static final int MAX_IDLE_WINDOWS = 3;
    private static final int MAX_RECEIPTS = 512;
    public int evidenceVersion, continuedVersion, idleWindows;
    public List<String> seen = new ArrayList<String>();

    public synchronized void observe(String tool, String args, boolean ok, String output, FileEvidence file) {
        if (!ok || tool == null || Arrays.asList("TodoWrite", "AgentControl", "Context", "Skill", "AskUserQuestion", "task","DiscoverTools").contains(tool)) return;
        if (seen == null) seen = new ArrayList<String>();
        if (seen.size() >= MAX_RECEIPTS) return;
        String content=file != null && file.sha256 != null ? file.path + ":" + file.sha256 : stableOutput(tool,output);
        String signature = StopGate.digest(tool + "\n" + args + "\n" + content);
        if (!seen.contains(signature)) { seen.add(signature); evidenceVersion++; }
    }
    private static String stableOutput(String tool,String output) {
        if(!"Build".equals(tool)&&!"ArtifactVerification".equals(tool))return String.valueOf(output);
        try {
            com.google.gson.JsonObject raw=com.google.gson.JsonParser.parseString(output).getAsJsonObject(),stable=new com.google.gson.JsonObject();
            for(String key:new String[]{"type","kind","action","status","scope","sourceDigest","sourceDigestAfter","tests","failures","errors","skipped","path","sha256","url","bodySha256","statusCode"})
                if(raw.has(key))stable.add(key,raw.get(key));
            // UUID、日志路径、检测时间、耗时不是新的业务进展。
            return stable.toString();
        }catch(RuntimeException unknown){return String.valueOf(output);}
    }

    public synchronized boolean permits(int windows) {
        return windows < MAX_WINDOWS && (windows < MAX_IDLE_WINDOWS || evidenceVersion > continuedVersion);
    }
    public synchronized void continued() {
        idleWindows = evidenceVersion > continuedVersion ? 0 : idleWindows + 1;
        continuedVersion = evidenceVersion;
    }
    public synchronized void manualResume() { idleWindows = 0; continuedVersion = evidenceVersion; }
    public synchronized String describe(int used) {
        return used + "/" + MAX_WINDOWS + "窗口；无新证据 " + idleWindows + "/" + MAX_IDLE_WINDOWS
                + "；去重工具证据 " + evidenceVersion + "（不等于业务已完成）";
    }
}
