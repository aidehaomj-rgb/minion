package com.minion.core.tools.computeruse;

import java.awt.image.BufferedImage;

/** 截图 diff 纯逻辑：逐像素通道差（阈值 24），输出变化占比与 bbox。
 *  基线缩到 ≤640 边（内存护栏 1 张软引用），纯 Java 无第三方依赖，headless 可测。 */
final class ScreenDiff {
    private ScreenDiff() {}

    static final int CHANNEL_THRESHOLD = 24;
    static final int BASELINE_MAX_EDGE = 640;

    static final class Result {
        final boolean sizeMismatch;
        final double ratio;
        final int minX, minY, maxX, maxY;   // 变化像素 bbox（含端点）；无变化时 minX=-1
        final int width, height;
        Result(boolean sizeMismatch, double ratio, int minX, int minY, int maxX, int maxY, int width, int height) {
            this.sizeMismatch = sizeMismatch; this.ratio = ratio;
            this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
            this.width = width; this.height = height;
        }
        boolean changed() { return minX >= 0; }
    }

    static Result compare(BufferedImage prev, BufferedImage cur) {
        int w = cur.getWidth(), h = cur.getHeight();
        if (prev.getWidth() != w || prev.getHeight() != h) {
            return new Result(true, 1.0, 0, 0, w - 1, h - 1, w, h);
        }
        long changed = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = prev.getRGB(x, y), b = cur.getRGB(x, y);
                int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
                int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
                int db = Math.abs((a & 0xFF) - (b & 0xFF));
                if (dr > CHANNEL_THRESHOLD || dg > CHANNEL_THRESHOLD || db > CHANNEL_THRESHOLD) {
                    changed++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        double ratio = changed / (double) (w * h);
        return new Result(false, ratio, changed > 0 ? minX : -1, changed > 0 ? minY : -1, maxX, maxY, w, h);
    }

    /** 基线降采样：长边 ≤640；已达标原样返回 */
    static BufferedImage baseline(BufferedImage src) {
        int longEdge = Math.max(src.getWidth(), src.getHeight());
        if (longEdge <= BASELINE_MAX_EDGE) return src;
        double s = longEdge / (double) BASELINE_MAX_EDGE;
        return ScreenTool.scale(src, Math.max(1, (int) Math.round(src.getWidth() / s)),
                Math.max(1, (int) Math.round(src.getHeight() / s)));
    }

    static String describe(Result r) {
        if (r.sizeMismatch) {
            return "屏幕尺寸已变化（当前基线 " + r.width + "x" + r.height + "），无法逐像素对齐，按全变化处理。"
                    + "建议重新 screenshot 建立基线。";
        }
        if (!r.changed()) {
            return "与上次全屏截图无显著变化（变化占比 0.00%，通道差阈值 " + CHANNEL_THRESHOLD + "）。操作可能未生效，建议检查目标控件。";
        }
        return String.format("与上次全屏截图相比变化占比 %.2f%%；变化区域 bbox=(%d,%d)-(%d,%d)（基线坐标，长边≤%d）。"
                        + "建议结合最新 screenshot 确认操作效果。",
                r.ratio * 100, r.minX, r.minY, r.maxX, r.maxY, BASELINE_MAX_EDGE);
    }
}