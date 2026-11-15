package com.minion.gui;

import com.google.gson.*;

/** Reject incomplete previews rather than enabling approval on a status/error response. */
public final class HooksPreviewHash {
    private HooksPreviewHash() {}
    public static String parse(String preview) {
        JsonObject o=JsonParser.parseString(preview).getAsJsonObject();
        if(!o.has("config")||o.get("config").isJsonNull()||!o.has("path")||o.get("path").getAsString().trim().isEmpty()
                ||!o.has("hash")||!o.get("hash").isJsonPrimitive())throw new IllegalArgumentException("配置预览不完整，不能批准");
        String hash=o.get("hash").getAsString();
        if(!hash.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("配置 hash 无效，不能批准");
        return hash;
    }
}
