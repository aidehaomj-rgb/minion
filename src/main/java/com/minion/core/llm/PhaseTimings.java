package com.minion.core.llm;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.concurrent.TimeUnit;

/** 主流程可选打点接口；未调用的阶段是 unknown，不是零耗时。 */
public final class PhaseTimings {
    public enum Phase { QUEUE, TOOL, RETRIEVAL, COMPRESSION, FINAL_VERIFICATION, MODEL_REQUEST, FIRST_DELTA, LOCK_WAIT, EXTERNAL_WAIT }
    private final EnumMap<Phase,Long> millis=new EnumMap<Phase,Long>(Phase.class);
    private final EnumMap<Phase,Long> counts=new EnumMap<Phase,Long>(Phase.class);
    public final class Span implements AutoCloseable {
        private final Phase phase;private final long start=System.nanoTime();private boolean closed;
        private Span(Phase phase) { this.phase=phase; }
        @Override public synchronized void close() {
            if(!closed){closed=true;record(phase,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
        }
    }
    public Span begin(Phase phase) { if(phase==null)throw new IllegalArgumentException("阶段不能为空");return new Span(phase); }
    public synchronized void record(Phase phase,long elapsedMs) {
        if(phase==null||elapsedMs<0)throw new IllegalArgumentException("无效阶段耗时");
        millis.put(phase,(millis.containsKey(phase)?millis.get(phase):0)+elapsedMs);
        counts.put(phase,(counts.containsKey(phase)?counts.get(phase):0)+1);
    }
    public synchronized JsonObject snapshot() {
        JsonObject out=new JsonObject();
        for(Phase phase:Phase.values()) {
            JsonObject o=new JsonObject();o.addProperty("elapsedMs",millis.containsKey(phase)?millis.get(phase):-1);
            o.addProperty("observations",counts.containsKey(phase)?counts.get(phase):0);out.add(phase.name(),o);
        }
        return out;
    }
    public synchronized void reset() { millis.clear();counts.clear(); }
}
