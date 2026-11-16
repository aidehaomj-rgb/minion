package com.minion.core.context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 按模型指纹持久化的输入 token 校准比例（学习态数据，非用户配置）。
 *  落盘 <jarDir>/.session/token-calibration.json；节流写盘（≥30s），关闭钩子兜底 flush。
 *  文件缺失/损坏静默从 1.0 重新学习，不影响任何配置窗口或 150K 压缩目标。 */
public final class TokenCalibrationStore {

    public static final class Entry {
        public final double scale;
        public final long samples;
        public final long updatedAt;
        Entry(double scale, long samples, long updatedAt) {
            this.scale = scale; this.samples = samples; this.updatedAt = updatedAt;
        }
    }

    private static final long WRITE_INTERVAL_MS = 30_000;

    private final Path file;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private boolean loaded;
    private boolean dirty;
    private long lastWrite;

    public TokenCalibrationStore(Path file) {
        this.file = file;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { flush(); }
        }, "token-calibration-flush"));
    }

    /** 模型指纹：provider|url|modelName 的 SHA-256（换模型不串校准）。 */
    public static String key(String provider, String url, String modelName) {
        String raw = (provider == null ? "" : provider) + "|" + (url == null ? "" : url)
                + "|" + (modelName == null ? "" : modelName);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return raw;
        }
    }

    public synchronized Entry entry(String key) {
        ensureLoaded();
        return key == null ? null : entries.get(key);
    }

    public synchronized void observe(String key, double scale, long samples) {
        if (key == null || !(scale >= 1.0) || Double.isInfinite(scale)) return;
        ensureLoaded();
        entries.put(key, new Entry(scale, samples, System.currentTimeMillis()));
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastWrite >= WRITE_INTERVAL_MS) flushLocked(now);
    }

    public synchronized void flush() {
        if (dirty) flushLocked(System.currentTimeMillis());
    }

    private void flushLocked(long now) {
        try {
            JsonObject root = new JsonObject();
            Gson gson = new Gson();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("scale", e.getValue().scale);
                o.addProperty("samples", e.getValue().samples);
                o.addProperty("updatedAt", e.getValue().updatedAt);
                root.add(e.getKey(), o);
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            lastWrite = now;
        } catch (IOException ignored) {
            // 学习态数据落盘失败不阻断会话；下次 observe 再试
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                double scale = o.get("scale").getAsDouble();
                long samples = o.has("samples") ? o.get("samples").getAsLong() : 0;
                long updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0;
                if (scale >= 1.0 && !Double.isInfinite(scale)) {
                    entries.put(e.getKey(), new Entry(scale, samples, updatedAt));
                }
            }
        } catch (Exception ignored) {
            entries.clear();
        }
    }
}