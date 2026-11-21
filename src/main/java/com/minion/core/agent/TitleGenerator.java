package com.minion.core.agent;

/** 新会话标题生成：本地截取兜底 + SideQuery LLM 升级（≤8 字，失败不阻塞，v2.4 §6.3） */
public class TitleGenerator {

    public static final int MAX_TITLE_LEN = 20;
    public static final int LLM_TITLE_LEN = 8;

    /** 本地标题：去换行/首尾空白，截取前 20 字；空输入回退「新会话」 */
    public static String localTitle(String text) {
        // \r\n 先整体替换为单空格，否则 \r、\n 各自替换会出现双空格（测试 localTitle_normalizesNewlinesAndTrim 断言）
        String t = text == null ? "" : text.trim().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (t.length() > MAX_TITLE_LEN) t = t.substring(0, MAX_TITLE_LEN);
        return t.isEmpty() ? "新会话" : t;
    }

    /**
     * LLM 标题（§6.3）：SideQuery 生成 ≤8 字；失败/空/畸形返回 null（调用方保留本地标题）。
     * 不在主循环线程调用——由会话线程池异步升级。
     */
    public static String llmTitle(com.minion.core.llm.LlmClient llm, String text) {
        if (llm == null || text == null || text.trim().isEmpty()) return null;
        String raw = com.minion.core.llm.SideQuery.complete(llm,
                java.util.Collections.singletonList(com.minion.core.llm.Message.user(text)),
                "为这段用户请求生成一个不超过 " + LLM_TITLE_LEN + " 字的会话标题。只输出标题本身，"
                        + "不要标点、引号、前缀或解释。", "title");
        if (raw == null) return null;
        String t = raw.trim().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        // 去常见包裹（模型爱加引号/书名号）
        while (t.length() >= 2 && (t.startsWith("「") && t.endsWith("」")
                || t.startsWith("\"") && t.endsWith("\"")
                || t.startsWith("《") && t.endsWith("》")))
            t = t.substring(1, t.length() - 1).trim();
        if (t.isEmpty()) return null;
        return t.length() > LLM_TITLE_LEN ? t.substring(0, LLM_TITLE_LEN) : t;
    }
}
