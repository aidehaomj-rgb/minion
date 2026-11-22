package com.minion.core.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * §12.2 SDK 帧读写（NDJSON 单行一帧）：单行帧字节上限（默认 1MiB——超限读弃该行并
 * 返回 overrun，上层回协议错误不执行）；JSON 解析畸形返 null；主版本校验
 * （major 相等即兼容，minor 差异容忍——服务端附加字段不破坏客户端）。
 */
public final class SdkFrameIo {

    public static final int MAX_FRAME_BYTES = 1024 * 1024;

    private SdkFrameIo() { }

    /** 读取结果：line=帧文本（null=EOF）；overrun=超限丢弃 */
    public static final class Read {
        public final String line;
        public final boolean overrun;
        Read(String line, boolean overrun) { this.line = line; this.overrun = overrun; }
    }

    /** 读一行（UTF-8 字符数近似字节上限；超限丢弃整行返回 overrun=true） */
    public static Read readFrame(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        int c = in.read();
        if (c == -1) return new Read(null, false);
        boolean overrun = false;
        while (c != -1 && c != '\n') {
            if (!overrun) {
                sb.append((char) c);
                if (sb.length() > MAX_FRAME_BYTES) {
                    overrun = true;
                    sb.setLength(0);   // 丢弃已积累内容（继续吃掉本行剩余）
                }
            }
            c = in.read();
        }
        if (overrun) return new Read(null, true);
        String line = sb.toString().trim();
        return new Read(line.isEmpty() ? null : line, false);   // 空行跳过
    }

    /** 解析为 JSON 对象；畸形/非对象返回 null */
    public static JsonObject parse(String line) {
        if (line == null) return null;
        try {
            com.google.gson.JsonElement e = JsonParser.parseString(line);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception malformed) {
            return null;
        }
    }

    /** 主版本兼容（major 相等——protocolVersion 即 major，v1 客户端与 v1 服务端兼容；
     *  缺省版本视为 1；非法数字拒绝） */
    public static boolean versionCompatible(JsonObject frame, int serverVersion) {
        int client = 1;
        if (frame.has("protocolVersion") && frame.get("protocolVersion").isJsonPrimitive())
            try { client = frame.get("protocolVersion").getAsInt(); }
            catch (NumberFormatException ignored) { return false; }
        return client == serverVersion;
    }
}
