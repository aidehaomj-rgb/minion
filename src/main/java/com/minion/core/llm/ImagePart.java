package com.minion.core.llm;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 图片内容块（OpenAI 兼容视觉协议 image_url part）。仅 user 消息使用。 */
public class ImagePart {

    private static final Pattern ATTACHMENT = Pattern.compile(
            "<user_attachment name=\\\"([^\\\"]*)\\\"[^>]*>.*?</user_attachment>", Pattern.DOTALL);

    /** 单张图片大小上限（5MB，用户确认） */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    /** 每条消息最多图片数（用户确认） */
    public static final int MAX_IMAGES = 3;
    /** 单图 token 粗估（压缩阈值估算用；不做像素级精确计算） */
    public static final int IMAGE_TOKENS = 500;

    public String mime;    // image/png 等
    public String base64;  // 纯 base64（不含 data: 前缀）
    public String name;    // 原始文件名（占位展示）

    /** 聊天区占位展示文本：附件正文折叠为文件名，图片也只展示文件名。 */
    public static String displayText(List<ImagePart> images, String text) {
        StringBuilder sb = new StringBuilder();
        String visible = text == null ? "" : text;
        Matcher matcher = ATTACHMENT.matcher(visible);
        while (matcher.find()) {
            appendPart(sb, "文件：" + unescapeAttribute(matcher.group(1)));
        }
        visible = matcher.replaceAll("").trim();
        if (images != null) {
            for (ImagePart ip : images) {
                if (ip == null || ip.name == null) continue;
                appendPart(sb, "图片：" + ip.name);
            }
        }
        if (!visible.isEmpty()) appendPart(sb, visible);
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(part);
    }

    private static String unescapeAttribute(String s) {
        return s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
