package com.minion.core.config;

/** 模型配置项（model.json 条目，字段名 = JSON 键） */
public class ModelConfig {

    public String displayName;
    public String url;
    public String apiKey;
    public String modelName;
    public String provider;
    public boolean thinking;
    public String reasoningEffort;
    /** 单次回复最大 token；与上下文窗口是两个独立限制。 */
    public int maxOutputTokens;
    public int maxContextTokens;
    public double compressThreshold;
    public int keepRecentMessages;

    public ModelConfig() { }

    /** 深拷贝（编辑表单用，避免污染列表中对象） */
    public ModelConfig copy() {
        ModelConfig c = new ModelConfig();
        c.displayName = displayName;
        c.url = url;
        c.apiKey = apiKey;
        c.modelName = modelName;
        c.provider = provider;
        c.thinking = thinking;
        c.reasoningEffort = reasoningEffort;
        c.maxOutputTokens = maxOutputTokens;
        c.maxContextTokens = maxContextTokens;
        c.compressThreshold = compressThreshold;
        c.keepRecentMessages = keepRecentMessages;
        return c;
    }
}
