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

    /** 运行期证据合并：在现有 profile 基础上覆盖一项能力状态（其余状态与有效期保留）。
     *  上游明确拒绝图片输入（如 400/1210）时用它把 VISION 标记为 UNSUPPORTED——
     *  是真实生产证据，有效期等同显式探测。基础 profile 为 unknown（无探测指纹）时
     *  生成确定性 SHA-256 指纹，保证存储层 load 校验（64 位 hex）能读回。 */
    public ModelCapabilityProfile withCapability(Capability capability, State state) {
        java.util.EnumMap<Capability, State> merged = new java.util.EnumMap<Capability, State>(evidence);
        merged.put(capability, state);
        long now = System.currentTimeMillis();
        long expires = isCurrent(now) ? expiresAt : now + DEFAULT_TTL_MS;
        String probe = probeFingerprint;
        if (probe == null || !probe.matches("[0-9a-f]{64}")) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest((fingerprint + "|runtime|" + capability + "|" + now).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                probe = sb.toString();
            } catch (java.security.NoSuchAlgorithmException impossible) { probe = null; }
        }
        return new ModelCapabilityProfile(fingerprint, probe,
                Math.max(observedAt, now), expires, 0, merged);
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
