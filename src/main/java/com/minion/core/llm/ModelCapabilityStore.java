package com.minion.core.llm;

import com.google.gson.*;
import com.minion.core.tools.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.EnumMap;

/** 惰性按指纹读写一个档案；不枚举模型，不联网，不保存接口地址、凭据或探测正文。 */
public final class ModelCapabilityStore {
    private final Path root;
    public ModelCapabilityStore(Path root) { this.root = root; }

    private Path path(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("无效的模型配置指纹");
        return root.resolve(fingerprint + ".json");
    }

    public void save(ModelCapabilityProfile profile) throws IOException {
        AtomicFiles.writeText(path(profile.fingerprint), profile.stored().toString(), StandardCharsets.UTF_8);
    }

    public ModelCapabilityProfile load(String fingerprint) throws IOException {
        Path file = path(fingerprint);
        if (!Files.exists(file)) return ModelCapabilityProfile.unknown(fingerprint);
        try {
            JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.get("schemaVersion").getAsInt() != 1 || !fingerprint.equals(o.get("fingerprint").getAsString()))
                return ModelCapabilityProfile.unknown(fingerprint);
            String probe = o.get("probeFingerprint").getAsString();
            if (!probe.matches("[0-9a-f]{64}")) return ModelCapabilityProfile.unknown(fingerprint);
            EnumMap<ModelCapabilityProfile.Capability, ModelCapabilityProfile.State> states =
                    new EnumMap<ModelCapabilityProfile.Capability, ModelCapabilityProfile.State>(ModelCapabilityProfile.Capability.class);
            JsonObject saved = o.getAsJsonObject("capabilities");
            for (ModelCapabilityProfile.Capability c : ModelCapabilityProfile.Capability.values()) {
                try { states.put(c, ModelCapabilityProfile.State.valueOf(saved.get(c.name()).getAsString())); }
                catch (RuntimeException ignored) { states.put(c, ModelCapabilityProfile.State.UNKNOWN); }
            }
            return new ModelCapabilityProfile(fingerprint, probe, o.get("observedAt").getAsLong(),
                    o.get("expiresAt").getAsLong(), o.get("invalidatedAt").getAsLong(), states);
        } catch (RuntimeException corrupt) { return ModelCapabilityProfile.unknown(fingerprint); }
    }

    public void invalidate(String fingerprint, long now) throws IOException {
        ModelCapabilityProfile profile = load(fingerprint);
        if (profile.observedAt > 0) save(profile.invalidate(now));
    }
}
