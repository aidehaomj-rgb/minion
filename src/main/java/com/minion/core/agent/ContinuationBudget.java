package com.minion.core.agent;

import java.util.ArrayList;
import java.util.List;

/** 有新工具证据才扩展续接；状态查询、待办勾选和重复读取不能无限刷新预算。 */
public final class ContinuationBudget {
    public static final int MAX_WINDOWS = 24;
    public static final int MAX_IDLE_WINDOWS = 3;
    private static final int MAX_RECEIPTS = 512;
    public int evidenceVersion, continuedVersion, idleWindows;
    public List<String> seen = new ArrayList<String>();
    private transient ProgressReceiptLedger ledger;
    public String ledgerError;
    /** Null keeps legacy serialized behavior; a null token limit means no cumulative cap. */
    public Integer windowLimit;
    public Long tokenLimit;
    public Long observedTaskTokens;

    public synchronized void bindLedger(ProgressReceiptLedger candidate) throws java.io.IOException {
        if (ledger == candidate) return;
        if (seen == null) seen = new ArrayList<String>();
        for (String signature : seen) candidate.add(signature);
        ledger = candidate; ledgerError = null;
        trimHot();
    }
    public synchronized void configure(int windows, long tokens) {
        if (windows < 1 || windows > 10000 || tokens < 0) throw new IllegalArgumentException("Invalid continuation windows or token limit");
        windowLimit = windows; tokenLimit = tokens == 0 ? null : tokens;
    }
    public synchronized long observedTokens() { return observedTaskTokens==null?0:observedTaskTokens; }
    public synchronized void usage(long tokens) { observedTaskTokens = Math.max(observedTokens(), Math.max(0, tokens)); }
    private void trimHot() { while (ledger != null && seen.size() > MAX_RECEIPTS) seen.remove(0); }

    public synchronized void observe(String tool, String args, boolean ok, String output, FileEvidence file) {
        if (!ok || ProgressEvidencePolicy.controlOnly(tool,args)) return;
        if (seen == null) seen = new ArrayList<String>();
        String content=file != null && file.sha256 != null ? file.path + ":" + file.sha256 : ProgressEvidencePolicy.stableOutput(tool,output);
        String signature = StopGate.digest(tool + "\n" + args + "\n" + content);
        if (seen.contains(signature)) return;
        try {
            boolean fresh = ledger == null || ledger.add(signature);
            seen.add(signature); trimHot();
            if (fresh) evidenceVersion++;
        } catch (java.io.IOException failed) { ledgerError = failed.getMessage(); }
    }
    public synchronized boolean permits(int windows) {
        return ledgerError == null && windows < (windowLimit == null ? MAX_WINDOWS : windowLimit)
                && (tokenLimit == null || observedTokens() < tokenLimit)
                && (windows < MAX_IDLE_WINDOWS || evidenceVersion > continuedVersion);
    }
    public synchronized void continued() {
        idleWindows = evidenceVersion > continuedVersion ? 0 : idleWindows + 1;
        continuedVersion = evidenceVersion;
    }
    public synchronized void manualResume() { idleWindows = 0; continuedVersion = evidenceVersion; }
    public synchronized String describe(int used) {
        return used + "/" + (windowLimit == null ? MAX_WINDOWS : windowLimit) + "窗口；无新证据 " + idleWindows + "/" + MAX_IDLE_WINDOWS
                + (tokenLimit == null ? "" : "；任务token " + observedTokens() + "/" + tokenLimit)
                + (ledgerError == null ? "" : "；进展记录不可用（不得据此完成）: " + ledgerError)
                + "；去重工具证据 " + evidenceVersion + "（不等于业务已完成）";
    }
}
