package com.minion.core.llm;

import java.util.Base64;

/** 图片 token 本地粗估：按真实像素尺寸（Qwen2.5-VL 公开口径 ≈ 每 28×28 patch 1 token）。
 *  下限 500 保持小图旧粗估（保守方向），上限 16384 对应默认 max_pixels≈12.8M。
 *  解析失败回退 500。这是本地启发式，不是服务端分项 usage——总量仍由 usage 校准。 */
public final class ImageTokens {

    public static final int FALLBACK = ImagePart.IMAGE_TOKENS;
    public static final int MAX_TOKENS = 16384;
    private static final int PATCH = 28;
    /** base64 解码上限：JPEG SOF 可能在 EXIF 之后，给足头部空间又不整图解码 */
    private static final int BASE64_PREFIX_CHARS = 256 * 1024;

    private ImageTokens() { }

    public static int estimate(ImagePart image) {
        if (image == null) return 0;
        if (image.tokenHint > 0) return image.tokenHint;
        int tokens = FALLBACK;
        int[] wh = dimensions(image.base64);
        if (wh != null) {
            long patches = (long) Math.ceil(wh[0] / (double) PATCH) * (long) Math.ceil(wh[1] / (double) PATCH);
            tokens = (int) Math.max(FALLBACK, Math.min(MAX_TOKENS, patches));
        }
        image.tokenHint = tokens;
        return tokens;
    }

    /** 从 base64 头部解析宽高；支持 PNG/JPEG/GIF/BMP，失败返回 null。 */
    static int[] dimensions(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        byte[] b;
        try {
            String prefix = base64.length() <= BASE64_PREFIX_CHARS ? base64
                    : base64.substring(0, BASE64_PREFIX_CHARS / 4 * 4);
            b = Base64.getMimeDecoder().decode(prefix);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int[] wh = png(b);
        if (wh == null) wh = jpeg(b);
        if (wh == null) wh = gif(b);
        if (wh == null) wh = bmp(b);
        if (wh == null || wh[0] <= 0 || wh[1] <= 0 || wh[0] > 100000 || wh[1] > 100000) return null;
        return wh;
    }

    private static int[] png(byte[] b) {
        if (b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
        return new int[]{beInt(b, 16), beInt(b, 20)};
    }

    private static int[] jpeg(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return null;
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
            if (marker == 0xDA || marker == 0xD9) return null; // 扫描开始/结束仍无 SOF
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            boolean sof = (marker >= 0xC0 && marker <= 0xC3) || (marker >= 0xC5 && marker <= 0xC7)
                    || (marker >= 0xC9 && marker <= 0xCB) || marker == 0xCD || marker == 0xCE || marker == 0xCF;
            if (sof) {
                if (i + 9 >= b.length) return null;
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return new int[]{w, h};
            }
            if (len < 2) return null;
            i += 2 + len;
        }
        return null;
    }

    private static int[] gif(byte[] b) {
        if (b.length < 10 || b[0] != 'G' || b[1] != 'I' || b[2] != 'F') return null;
        return new int[]{leInt16(b, 6), leInt16(b, 8)};
    }

    private static int[] bmp(byte[] b) {
        if (b.length < 26 || b[0] != 'B' || b[1] != 'M') return null;
        return new int[]{leInt32(b, 18), Math.abs(leInt32(b, 22))};
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int leInt16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int leInt32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}