package com.minion.core.llm;

import java.util.*;
import java.util.regex.Pattern;

/** 每次响应独立、内存有界的工具承诺循环检测；不把重复文本当成实际执行。 */
public final class RepetitionGuard implements StreamHandler {
    public static final String FINISH = "repetition_guard";
    public static final int MAX_RECOVERIES = 3;
    public static final String HINT = "[流式循环恢复] 上次响应连续重复工具执行承诺，已由客户端结束该响应；没有执行该次工具。"
            + "保持当前任务及已确认进度，只推进尚未完成的一步。需要工具时生成一个完整合法的工具调用，不要复述计划，不重复已完成操作。";
    private final StreamHandler sink;
    private final Detector content = new Detector(), thinking = new Detector();
    private boolean toolSeen, triggered;
    public RepetitionGuard(StreamHandler sink) { this.sink = sink; }
    public boolean triggered() { return triggered; }
    public boolean shouldStopResponse() { return triggered || sink.shouldStopResponse(); }
    public void onToolCallDelta() { toolSeen = true; triggered = false; sink.onToolCallDelta(); }
    public void onContent(String delta) {
        if (triggered) return;
        sink.onContent(delta);
        if (!toolSeen && content.add(delta)) triggered = true;
    }
    public void onThinking(String delta) {
        if (triggered) return;
        sink.onThinking(delta);
        if (!toolSeen && thinking.add(delta)) triggered = true;
    }
    public void onUsage(Usage usage) { sink.onUsage(usage); }
    public void onError(LlmException error) { sink.onError(error); }
    public void onFinish(String reason, Usage usage, List<ToolCall> calls) {
        // 完整工具结果优先；旧客户端可能没有增量通知，不能丢弃已返回的合法动作。
        if (calls != null && !calls.isEmpty()) { toolSeen = true; triggered = false; }
        sink.onFinish(triggered ? FINISH : reason, usage, calls);
    }
    static final class Detector {
        private static final Pattern TOOL = Pattern.compile("(?i)(bash|task|python|write|edit|read|工具)");
        private static final Pattern ACTION = Pattern.compile("(?i)(我.{0,12}(调用|执行)|现在.{0,8}(调用|执行)|先调用|让我.{0,8}调用|破除.*循环|i.{0,20}(call|execut)|executing now|execute tool call)");
        private final StringBuilder pending = new StringBuilder();
        private final Deque<String> recent = new ArrayDeque<String>();
        private boolean inCode;
        boolean add(String delta) {
            if (delta == null) return false;
            for (int i=0;i<delta.length();i++) {
                char c=delta.charAt(i); pending.append(c);
                if (c=='\n' || pending.length()>=160) {
                    String s=pending.toString().trim(); pending.setLength(0);
                    if(s.contains("```")) { inCode=!inCode; recent.clear(); continue; }
                    if(inCode || s.startsWith(">") || s.startsWith("|") || s.startsWith("{")) { recent.clear(); continue; }
                    if(s.isEmpty()) continue;
                    if(!TOOL.matcher(s).find() || !ACTION.matcher(s).find()) { recent.clear(); continue; }
                    s=s.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]", "");
                    if(s.length()<20) { recent.clear(); continue; }
                    recent.addLast(s); if(recent.size()>6) recent.removeFirst();
                    if(recent.size()==6) {
                        int total=0, similar=0; String first=recent.peekFirst();
                        for(String line:recent) { total+=line.length(); if(similarity(first,line)>=.55) similar++; }
                        if(total>=120 && similar>=5) return true;
                    }
                }
            }
            return false;
        }
        private static double similarity(String a,String b) {
            Set<String> x=grams(a), y=grams(b); int common=0;
            for(String s:x) if(y.contains(s)) common++;
            return 2.0*common/(x.size()+y.size());
        }
        private static Set<String> grams(String s) {
            Set<String> out=new HashSet<String>();
            for(int i=0;i+1<s.length();i++) out.add(s.substring(i,i+2));
            return out;
        }
    }
}
