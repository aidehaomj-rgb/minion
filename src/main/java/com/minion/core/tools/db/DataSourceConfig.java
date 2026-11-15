package com.minion.core.tools.db;

/**
 * 数据源配置项（tools.json 里 dataSources 数组元素）。
 * 字段名即 JSON 键名，gson 直接序列化；密码按设计决策明文存（与 model.json 的 apiKey 一致）。
 */
public class DataSourceConfig {
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 300;
    public static final int MAX_QUERY_TIMEOUT_SECONDS = 86400;
    /** 标识名：同一数据库类型内唯一，可修改 */
    public String name = "";
    public String url = "";
    public String user = "";
    public String password = "";
    /** 用户数据源上限；旧 tools.json 缺省为 300 秒，模型不得覆盖。 */
    public int queryTimeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;

    public int effectiveQueryTimeoutSeconds() {
        return queryTimeoutSeconds <= 0 ? DEFAULT_QUERY_TIMEOUT_SECONDS
                : Math.min(queryTimeoutSeconds, MAX_QUERY_TIMEOUT_SECONDS);
    }

    /** 提交时冻结配置，设置页之后切源/改超时不改变已登记查询。 */
    public DataSourceConfig snapshot() {
        DataSourceConfig copy = new DataSourceConfig(name, url, user, password);
        copy.queryTimeoutSeconds = effectiveQueryTimeoutSeconds();
        return copy;
    }

    public DataSourceConfig() { }

    public DataSourceConfig(String name, String url, String user, String password) {
        this.name = name;
        this.url = url;
        this.user = user;
        this.password = password;
    }
}
