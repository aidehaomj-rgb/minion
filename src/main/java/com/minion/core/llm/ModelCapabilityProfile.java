package com.minion.core.llm;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Map;

/** 显式合成探测证据；能力仅在记录的探测模式和有效期内成立。 */
public final class ModelCapabilityProfile {
    public enum Capability { TOOLS, STRUCTURED_OUTPUT, VISION, USAGE, STREAMING }
    public enum State { SUPPORTED, UNSUPPORTED, UNKNOWN }
    public static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    public final String fingerprint;
    public final String probeFingerprint;
    public final long observedAt, expiresAt, invalidatedAt;
    private final EnumMap<Capability, State> evidence = new EnumMap<Capability, State>(Capability.class);

    public ModelCapabilityProfile(String fingerprint, String probeFingerprint, long observedAt,
                                  long expiresAt, long invalidatedAt, Map<Capability, State> states) {
        this.fingerprint = fingerprint;
        this.probeFingerprint = probeFingerprint;
        this.observedAt = observedAt;
        this.expiresAt = expiresAt;
        this.invalidatedAt = invalidatedAt;
        for (Capability c : Capability.values()) evidence.put(c,
                states != null && states.get(c) != null ? states.get(c) : State.UNKNOWN);
    }

    public static ModelCapabilityProfile unknown(String fingerprint) {
        return new ModelCapabilityProfile(fingerprint, "", 0, 0, 0, null);
    }

    public boolean isCurrent(long now) {
        return observedAt > 0 && observedAt <= now && now < expiresAt && invalidatedAt == 0;
    }

    public State state(Capability c, long now) { return isCurrent(now) ? evidence.get(c) : State.UNKNOWN; }

    /** 展示时同时返回有效状态和历史证据，不把过期支持继续当作支持。 */
    public JsonObject describe(long now) {
        JsonObject out = stored();
        out.addProperty("current", isCurrent(now));
        JsonObject effective = new JsonObject();
        for (Capability c : Capability.values()) effective.addProperty(c.name(), state(c, now).name());
        out.add("effectiveCapabilities", effective);
        return out;
    }

    JsonObject stored() {
        JsonObject out = new JsonObject();
        out.addProperty("schemaVersion", 1);
        out.addProperty("fingerprint", fingerprint);
        out.addProperty("probeFingerprint", probeFingerprint);
        out.addProperty("observedAt", observedAt);
        out.addProperty("expiresAt", expiresAt);
        out.addProperty("invalidatedAt", invalidatedAt);
        out.addProperty("scope", "synthetic; thinking=disabled; max_tokens=1024; context_window=unknown; structured=json_object_only");
        JsonObject states = new JsonObject();
        for (Capability c : Capability.values()) states.addProperty(c.name(), evidence.get(c).name());
        out.add("capabilities", states);
        return out;
    }

    ModelCapabilityProfile invalidate(long now) {
        return new ModelCapabilityProfile(fingerprint, probeFingerprint, observedAt, expiresAt,
                Math.max(1, now), evidence);
    }
}
